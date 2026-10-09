package com.dkc.fileserverclient.transfer

import android.content.Context
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

    private lateinit var appContext: Context
    private val service by lazy { FileServerService(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(MAX_CONCURRENT)

    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val runningJobs = mutableMapOf<String, Job>()
    private val userPaused = mutableSetOf<String>()

    @Volatile private var isNetworkAvailable = true

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
     * 注册一个服务端任务（move / copy）。
     * 不走本地 Semaphore 调度，由内部轮询 task/{taskId} 驱动状态。
     */
    fun registerServerTask(
        serverUrl: String,
        serverTaskId: String,
        fileName: String,
        operationType: TransferType,   // SERVER_MOVE 或 SERVER_COPY
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

        updateTask(taskId) { it.copy(state = TaskState.RUNNING, errorMessage = null) }

        try {
            when (task.type) {
                TransferType.UPLOAD -> doUpload(taskId)
                TransferType.DOWNLOAD -> { /* 未实现 */ }
                TransferType.SERVER_MOVE,
                TransferType.SERVER_COPY -> { /* 由轮询驱动，不在这里执行 */ }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFailure(taskId, e.message ?: "未知错误")
        }
    }

    private suspend fun doUpload(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val localFile = File(task.localPath)
        if (!localFile.exists()) {
            handleFailure(taskId, "本地文件不存在")
            return
        }

        val result = service.uploadFileWithProgress(
            serverUrl = task.serverUrl,
            file = localFile,
            targetPath = task.remotePath.substringBeforeLast('/', ""),
            onProgress = { written, total ->
                val newProgress = if (total > 0) ((written * 100) / total).toInt() else 0
                if (newProgress != task.progress) {
                    updateTask(taskId) { it.copy(transferredBytes = written, totalBytes = total) }
                }
            }
        )

        if (result.success) {
            updateTask(taskId) {
                it.copy(state = TaskState.COMPLETED, transferredBytes = it.totalBytes)
            }
        } else {
            handleFailure(taskId, result.message.ifEmpty { "上传失败" })
        }
    }

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

    fun pauseTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (task.type == TransferType.SERVER_MOVE || task.type == TransferType.SERVER_COPY) return
        userPaused.add(taskId)
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

        userPaused.remove(taskId)
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        updateTask(taskId) { it.copy(state = TaskState.CANCELLED) }
    }

    fun retryTask(taskId: String) {
        updateTask(taskId) { it.copy(state = TaskState.QUEUED, retryCount = 0, errorMessage = null) }
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

    private fun pauseRunningTasksForNetwork() {
        _tasks.value
            .filter { it.state == TaskState.RUNNING || it.state == TaskState.RETRYING }
            .forEach { task ->
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

    private inline fun updateTask(taskId: String, transform: (TransferTask) -> TransferTask) {
        val oldTask = _tasks.value.firstOrNull { it.id == taskId }
        val newTask = oldTask?.let { transform(it).copy(updatedAt = System.currentTimeMillis()) }

        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) newTask ?: task else task
        }

        // 状态从"活动"变为"终止"时，清理入队时产生的临时文件
        if (oldTask != null && newTask != null &&
            !oldTask.isFinished && newTask.isFinished) {
            cleanupTempFileIfNeeded(newTask)
        }

        persist()
    }

    /**
     * 只清理 cacheDir 下以 upload_ 开头的临时文件。
     * 调用方（FileListActivity / ImageGalleryActivity）在入队前用
     * File.createTempFile("upload_", ...) 生成，任务终止后由这里统一删除。
     * 其他文件（例如用户从相册选择但保存到 filesDir 的正式文件）不动。
     */
    private fun cleanupTempFileIfNeeded(task: TransferTask) {
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