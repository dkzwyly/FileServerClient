package com.dkc.fileserverclient.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dkc.fileserverclient.FileServerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

object TransferManager {

    private const val TAG = "TransferManager"
    private const val MAX_CONCURRENT = 2
    private const val MAX_RETRY = 5
    private const val BASE_BACKOFF = 2000L
    private const val MAX_BACKOFF = 60_000L
    private const val CHUNK_THRESHOLD = 15L * 1024 * 1024
    /** 分片阈值：≥ 该大小的 URI 上传走分片并行 */


    private lateinit var appContext: Context
    private val service by lazy { FileServerService(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val runningJobs = mutableMapOf<String, Job>()
    private val userPaused = mutableSetOf<String>()

    /** updateTask 的读-改-写锁（MutableStateFlow 本身原子，但 map 重建需要锁） */
    private val taskUpdateLock = Any()

    @Volatile private var isNetworkAvailable = true

    // ============================================================
    // 初始化
    // ============================================================
    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            val restored = TransferTaskStore.load(appContext)
            _tasks.value = restored
            Log.d(TAG, "恢复 ${restored.size} 个任务")

            launch {
                NetworkMonitor.observe(appContext).collect { connected ->
                    val wasAvailable = isNetworkAvailable
                    isNetworkAvailable = connected
                    Log.d(TAG, "网络: $connected")

                    if (!wasAvailable && connected) resumeNetworkPausedTasks()
                    else if (wasAvailable && !connected) pauseRunningTasksForNetwork()
                }
            }

            restored.filter { it.isActive }.forEach { task ->
                when (task.type) {
                    TransferType.SERVER_MOVE, TransferType.SERVER_COPY -> {
                        task.serverTaskId?.let { startServerTaskPolling(task.id, it) }
                    }
                    else -> scheduleTask(task.id)
                }
            }
        }
    }

    // ============================================================
    // 入队
    // ============================================================

    /** 本地 File 上传 */
    fun enqueueUpload(serverUrl: String, localFile: File, remoteDir: String): String {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            type = TransferType.UPLOAD,
            serverUrl = serverUrl,
            localPath = localFile.absolutePath,
            remotePath = "$remoteDir/${localFile.name}".trimStart('/'),
            fileName = localFile.name,
            totalBytes = localFile.length(),
            state = TaskState.QUEUED
        )
        _tasks.value = _tasks.value + task
        persist()
        scheduleTask(task.id)
        return task.id
    }

    /**
     * Uri 流式上传。
     * 文件 ≥ 8MB 时自动走分片并行（详见 doUpload）。
     *
     * @param fileSize 从 MediaStore/ContentResolver 查到的大小；未知传 -1
     */
    fun enqueueUploadUri(
        serverUrl: String,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        remoteDir: String
    ): String {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            type = TransferType.UPLOAD,
            serverUrl = serverUrl,
            localPath = "",
            localUri = uri.toString(),
            contentLength = fileSize,
            remotePath = "$remoteDir/$fileName".trimStart('/'),
            fileName = fileName,
            totalBytes = fileSize.coerceAtLeast(0),
            state = TaskState.QUEUED
        )
        _tasks.value = _tasks.value + task
        persist()
        scheduleTask(task.id)
        return task.id
    }

    /** 注册服务端任务（move / copy），由轮询驱动状态 */
    fun registerServerTask(
        serverUrl: String,
        serverTaskId: String,
        fileName: String,
        operationType: TransferType,
        remotePath: String = ""
    ): String {
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            type = operationType,
            serverUrl = serverUrl,
            localPath = "",
            remotePath = remotePath,
            fileName = fileName,
            totalBytes = 0L,
            state = TaskState.QUEUED,
            serverTaskId = serverTaskId
        )
        _tasks.value = _tasks.value + task
        persist()
        startServerTaskPolling(task.id, serverTaskId)
        return task.id
    }

    // ============================================================
    // 服务端任务轮询（move / copy）
    // ============================================================
    private fun startServerTaskPolling(localTaskId: String, serverTaskId: String) {
        if (runningJobs.containsKey(localTaskId)) return

        val job = scope.launch {
            val startTime = System.currentTimeMillis()
            val totalTimeoutMs = 10 * 60 * 1000L
            var delayMs = 500L
            var lastStatus: String? = null
            var noChangeCount = 0

            while (isActive) {
                delay(delayMs)

                if (System.currentTimeMillis() - startTime > totalTimeoutMs) {
                    updateTask(localTaskId) {
                        it.copy(state = TaskState.FAILED, errorMessage = "轮询超时")
                    }
                    return@launch
                }

                val current = _tasks.value.firstOrNull { it.id == localTaskId } ?: return@launch
                if (current.isFinished) return@launch

                val status = try {
                    service.getTaskStatus(current.serverUrl, serverTaskId)
                } catch (e: Exception) {
                    null
                }

                if (status == null) {
                    delayMs = 2000L
                    continue
                }

                val newState = when (status.status) {
                    "Queued" -> TaskState.QUEUED
                    "Processing" -> TaskState.RUNNING
                    "Completed" -> TaskState.COMPLETED
                    "Failed" -> TaskState.FAILED
                    "Cancelled" -> TaskState.CANCELLED
                    else -> TaskState.RUNNING
                }

                updateTask(localTaskId) {
                    it.copy(
                        state = newState,
                        transferredBytes = status.copiedBytes,
                        totalBytes = status.totalBytes,
                        errorMessage = status.errorMessage,
                        retryCount = status.retryCount
                    )
                }

                if (status.isFinished) return@launch

                if (status.status != lastStatus) {
                    lastStatus = status.status
                    noChangeCount = 0
                    delayMs = 1000L
                } else {
                    noChangeCount++
                    delayMs = when {
                        noChangeCount >= 15 -> 5000L
                        noChangeCount >= 5 -> 2000L
                        else -> 1000L
                    }
                }
            }
        }

        runningJobs[localTaskId] = job
        job.invokeOnCompletion { runningJobs.remove(localTaskId) }
    }

    // ============================================================
    // 调度
    // ============================================================
    private fun scheduleTask(taskId: String) {
        if (runningJobs.containsKey(taskId)) return
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (!task.isActive) return
        if (task.type == TransferType.SERVER_MOVE || task.type == TransferType.SERVER_COPY) return

        val job = scope.launch {
            semaphore.withPermit { executeTask(taskId) }
        }
        runningJobs[taskId] = job
        job.invokeOnCompletion { runningJobs.remove(taskId) }
    }

    private suspend fun executeTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (!isNetworkAvailable) {
            updateTask(taskId) { it.copy(state = TaskState.PAUSED, errorMessage = "等待网络") }
            return
        }

        // 进入 RUNNING 时重置 transferredBytes，避免 retry 后进度条"卡在 90%"
        updateTask(taskId) {
            it.copy(state = TaskState.RUNNING, errorMessage = null, transferredBytes = 0L)
        }

        try {
            when (task.type) {
                TransferType.UPLOAD -> doUpload(taskId)
                TransferType.DOWNLOAD -> { /* 未实现 */ }
                TransferType.SERVER_MOVE,
                TransferType.SERVER_COPY -> { /* 由轮询驱动 */ }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(taskId, e.message ?: "未知错误")
        }
    }

    // ============================================================
    // 上传（分流：分片并行 / URI 单流 / File 单流）
    // ============================================================
    private suspend fun doUpload(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return

        val useChunked = task.hasLocalUri && task.totalBytes >= CHUNK_THRESHOLD

        val result = when {
            // ---------- 分片并行上传 ----------
            useChunked -> {
                val uri = Uri.parse(task.localUri)
                val chunkSize = pickChunkSize(task.totalBytes)
                val parallelism = pickParallelism(task.totalBytes)

                Log.d(
                    TAG,
                    "分片上传: ${task.fileName}, size=${task.totalBytes}, " +
                            "chunk=$chunkSize, parallel=$parallelism"
                )

                service.uploadChunkedWithProgress(
                    serverUrl = task.serverUrl,
                    context = appContext,
                    uri = uri,
                    fileName = task.fileName,
                    totalSize = task.totalBytes,
                    targetDir = task.remotePath.substringBeforeLast('/', ""),
                    // fileId 用 task.id 去掉横杠，重启后 task.id 不变可命中已传分片
                    fileId = task.id.replace("-", ""),
                    chunkSize = chunkSize,
                    parallelChunks = parallelism,
                    onProgress = { uploaded, total -> throttledProgress(taskId, uploaded, total) }
                )
            }

            // ---------- URI 单流上传 ----------
            task.hasLocalUri -> {
                val uri = Uri.parse(task.localUri)
                service.uploadUriWithProgress(
                    serverUrl = task.serverUrl,
                    context = appContext,
                    uri = uri,
                    fileSize = task.contentLength,
                    fileName = task.fileName,
                    targetPath = task.remotePath.substringBeforeLast('/', ""),
                    onProgress = { written, total -> throttledProgress(taskId, written, total) }
                )
            }

            // ---------- 本地 File 单流上传 ----------
            else -> {
                val localFile = File(task.localPath)
                if (!localFile.exists()) {
                    handleFailure(taskId, "本地文件不存在")
                    return
                }
                service.uploadFileWithProgress(
                    serverUrl = task.serverUrl,
                    file = localFile,
                    targetPath = task.remotePath.substringBeforeLast('/', ""),
                    onProgress = { written, total -> throttledProgress(taskId, written, total) }
                )
            }
        }

        if (result.success) {
            updateTask(taskId) {
                it.copy(state = TaskState.COMPLETED, transferredBytes = it.totalBytes)
            }
        } else {
            handleFailure(taskId, result.message.ifEmpty { "上传失败" })
        }
    }

    /** 分片大小：小 4MB，中 8MB，大 16MB */
    private fun pickChunkSize(totalSize: Long): Int = when {
        totalSize < 50L * 1024 * 1024 -> 2 * 1024 * 1024    // 15~50MB：2MB/片，约 8~25 片
        totalSize < 500L * 1024 * 1024 -> 8 * 1024 * 1024   // 50~500MB：8MB/片，约 6~63 片
        else -> 16 * 1024 * 1024                            // >500MB：16MB/片，32+ 片
    }

    /** 分片并行度：大文件多开几路 */
    private fun pickParallelism(totalSize: Long): Int = when {
        totalSize < 50L * 1024 * 1024 -> 3
        totalSize < 500L * 1024 * 1024 -> 4
        else -> 6
    }

    /**
     * 进度节流：
     * - 单调递增保护（并发分片回调时丢弃旧值，防止进度回退）
     * - 百分比变化或到达 100% 才更新，减少 map 重建 + persist
     *
     * 注：executeTask 进 RUNNING 时已把 transferredBytes 置 0，
     * 所以 retry 从头开始不会触发单调保护拦截。
     */
    private fun throttledProgress(taskId: String, uploaded: Long, total: Long) {
        val cur = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (uploaded < cur.transferredBytes) return
        val newPercent = if (total > 0) ((uploaded * 100) / total).toInt() else 0
        if (newPercent != cur.progress || (total > 0 && uploaded == total)) {
            updateTask(taskId) {
                it.copy(transferredBytes = uploaded, totalBytes = total)
            }
        }
    }

    // ============================================================
    // 失败 / 重试
    // ============================================================
    private fun handleFailure(taskId: String, message: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return

        if (!isNetworkAvailable) {
            updateTask(taskId) {
                it.copy(state = TaskState.PAUSED, errorMessage = "网络不可用，等待恢复")
            }
            return
        }

        if (task.retryCount >= MAX_RETRY) {
            updateTask(taskId) { it.copy(state = TaskState.FAILED, errorMessage = message) }
            return
        }

        val backoff = (BASE_BACKOFF * (1L shl task.retryCount)).coerceAtMost(MAX_BACKOFF)
        updateTask(taskId) {
            it.copy(
                state = TaskState.RETRYING,
                retryCount = it.retryCount + 1,
                errorMessage = "$message（${backoff / 1000}s 后重试）"
            )
        }

        scope.launch {
            delay(backoff)
            val cur = _tasks.value.firstOrNull { it.id == taskId } ?: return@launch
            if (cur.state == TaskState.RETRYING) scheduleTask(taskId)
        }
    }

    // ============================================================
    // 用户操作
    // ============================================================
    fun pauseTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.type == TransferType.SERVER_MOVE || task.type == TransferType.SERVER_COPY) return

        userPaused.add(taskId)

        // 分片任务：先主动断 in-flight call，让服务端尽早感知 socket 关闭
        if (task.hasLocalUri && task.totalBytes >= CHUNK_THRESHOLD) {
            service.cancelActiveChunkUploads(task.id.replace("-", ""))
        }

        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        updateTask(taskId) { it.copy(state = TaskState.PAUSED, errorMessage = "已暂停") }
    }

    fun resumeTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.type == TransferType.SERVER_MOVE || task.type == TransferType.SERVER_COPY) return
        userPaused.remove(taskId)
        updateTask(taskId) { it.copy(state = TaskState.QUEUED, errorMessage = null) }
        scheduleTask(taskId)
    }

    fun cancelTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return

        // 服务端任务：调服务端取消 + 停轮询
        if (task.type == TransferType.SERVER_MOVE || task.type == TransferType.SERVER_COPY) {
            val serverTaskId = task.serverTaskId
            if (serverTaskId != null) {
                scope.launch {
                    try { service.cancelServerTask(task.serverUrl, serverTaskId) }
                    catch (_: Exception) {}
                }
            }
            runningJobs[taskId]?.cancel()
            runningJobs.remove(taskId)
            updateTask(taskId) { it.copy(state = TaskState.CANCELLED) }
            return
        }

        val fileId = task.id.replace("-", "")
        val isChunked = task.hasLocalUri && task.totalBytes >= CHUNK_THRESHOLD

        // ★ 第一步：立即断掉客户端 in-flight chunk 请求（socket 关闭）
        if (isChunked) {
            service.cancelActiveChunkUploads(fileId)
        }

        // ★ 第二步：取消协程（awaitResponse 的 invokeOnCancellation 兜底 cancel）
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        userPaused.remove(taskId)

        // ★ 第三步：等服务端感知 RequestAborted（约 100~300ms），再 DELETE 清理分片
        if (isChunked) {
            scope.launch {
                delay(500)
                try { service.cancelChunkedUpload(task.serverUrl, fileId) } catch (_: Exception) {}
            }
        }

        updateTask(taskId) { it.copy(state = TaskState.CANCELLED) }
    }

    fun retryTask(taskId: String) {
        updateTask(taskId) {
            it.copy(state = TaskState.QUEUED, retryCount = 0, errorMessage = null, transferredBytes = 0L)
        }
        scheduleTask(taskId)
    }

    fun removeTask(taskId: String) {
        cancelTask(taskId)
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        persist()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.isFinished }
        persist()
    }

    // ============================================================
    // 网络变化
    // ============================================================
    private fun pauseRunningTasksForNetwork() {
        _tasks.value
            .filter { it.state == TaskState.RUNNING || it.state == TaskState.RETRYING }
            .forEach { task ->
                if (task.hasLocalUri && task.totalBytes >= CHUNK_THRESHOLD) {
                    service.cancelActiveChunkUploads(task.id.replace("-", ""))
                }
                runningJobs[task.id]?.cancel()
                runningJobs.remove(task.id)
                updateTask(task.id) {
                    it.copy(state = TaskState.PAUSED, errorMessage = "网络断开，等待恢复")
                }
            }
    }

    private fun resumeNetworkPausedTasks() {
        _tasks.value
            .filter { it.state == TaskState.PAUSED && !userPaused.contains(it.id) }
            .forEach { task ->
                updateTask(task.id) { it.copy(state = TaskState.QUEUED, errorMessage = null) }
                scheduleTask(task.id)
            }
    }

    // ============================================================
    // 状态更新 / 持久化
    // ============================================================
    /**
     * 读-改-写全程持锁：
     * - 并发 updateTask（多个分片进度回调）不会丢更新
     * - 状态变化（如 RUNNING → PAUSED）不会被旧快照覆盖
     *
     * 待清理任务用 synchronized 块的返回值带出，避免 inline lambda 捕获外部 var。
     */
    private fun updateTask(taskId: String, transform: (TransferTask) -> TransferTask) {
        val cleanupTask: TransferTask? = synchronized(taskUpdateLock) {
            val oldTask = _tasks.value.firstOrNull { it.id == taskId }
            if (oldTask == null) {
                null
            } else {
                val newTask = transform(oldTask).copy(updatedAt = System.currentTimeMillis())
                _tasks.value = _tasks.value.map { if (it.id == taskId) newTask else it }
                if (!oldTask.isFinished && newTask.isFinished) newTask else null
            }
        }

        cleanupTask?.let { cleanupTempFileIfNeeded(it) }
        persist()
    }

    /**
     * 只清理 cacheDir 下以 upload_ 开头的临时文件。
     * URI 任务（含分片）无临时文件，直接跳过。
     */
    private fun cleanupTempFileIfNeeded(task: TransferTask) {
        if (task.hasLocalUri) return

        try {
            val file = File(task.localPath)
            if (!file.exists()) return
            val isInCache = file.parentFile?.absolutePath == appContext.cacheDir.absolutePath
            val isTemp = file.name.startsWith("upload_")
            if (isInCache && isTemp) {
                val deleted = file.delete()
                Log.d(TAG, "清理临时文件: ${file.name}, deleted=$deleted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理临时文件失败: ${task.localPath}", e)
        }
    }

    private var persistJob: Job? = null
    private fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(500)
            TransferTaskStore.save(appContext, _tasks.value)
        }
    }
}