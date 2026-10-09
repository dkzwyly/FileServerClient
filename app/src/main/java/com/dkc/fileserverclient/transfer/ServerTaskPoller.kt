package com.dkc.fileserverclient.transfer

import android.util.Log
import com.dkc.fileserverclient.FileServerService
import com.dkc.fileserverclient.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轮询服务端 move/copy 任务的状态。
 *
 * 特性：
 *  - 首次快速轮询（500ms），随后 1s
 *  - 状态长时间不变时自动退避（2s → 5s），降低请求频率
 *  - 总超时兜底（默认 10 分钟），防止服务端任务丢失时无限轮询
 *  - 支持主动取消服务端任务
 *
 * 用法：
 *   val poller = ServerTaskPoller(fileServerService, serverUrl, taskId, scope)
 *   poller.start(
 *       onUpdate = { status -> ... },
 *       onFinished = { success, final -> ... }
 *   )
 *   // 手动停止：poller.stop()
 */
class ServerTaskPoller(
    private val service: FileServerService,
    private val serverUrl: String,
    private val taskId: String,
    private val scope: CoroutineScope,
    private val config: Config = Config()
) {

    data class Config(
        val initialDelayMs: Long = 500,
        val normalIntervalMs: Long = 1000,
        val slowIntervalMs: Long = 2000,
        val verySlowIntervalMs: Long = 5000,
        /** 总超时：超过这个时间视为失败，防止服务端任务丢失时无限轮询 */
        val totalTimeoutMs: Long = 10 * 60 * 1000L,
        /** 连续 N 次状态无变化后进入慢速 */
        val noChangeThresholdForSlow: Int = 5,
        /** 连续 N 次状态无变化后进入极慢速 */
        val noChangeThresholdForVerySlow: Int = 15
    )

    private val _status = MutableStateFlow<TaskStatus?>(null)
    val status: StateFlow<TaskStatus?> = _status.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var stopped = false

    fun start(
        onUpdate: (TaskStatus) -> Unit = {},
        onFinished: (success: Boolean, final: TaskStatus?) -> Unit = { _, _ -> }
    ) {
        if (job?.isActive == true) return
        stopped = false

        job = scope.launch {
            val startTime = System.currentTimeMillis()
            var lastStatus: String? = null
            var noChangeCount = 0
            var delayMs = config.initialDelayMs

            while (!stopped && isActive) {
                delay(delayMs)

                // 总超时兜底
                if (System.currentTimeMillis() - startTime > config.totalTimeoutMs) {
                    Log.w(TAG, "任务 $taskId 轮询超时，停止")
                    withContext(Dispatchers.Main) {
                        onFinished(false, _status.value)
                    }
                    break
                }

                try {
                    val status = service.getTaskStatus(serverUrl, taskId)

                    if (status == null) {
                        // 查询失败（网络抖动等），稍后重试
                        delayMs = config.slowIntervalMs
                        continue
                    }

                    _status.value = status
                    withContext(Dispatchers.Main) { onUpdate(status) }

                    // 状态变化检测 + 退避
                    if (status.status != lastStatus) {
                        lastStatus = status.status
                        noChangeCount = 0
                        delayMs = config.normalIntervalMs
                    } else {
                        noChangeCount++
                        delayMs = when {
                            noChangeCount >= config.noChangeThresholdForVerySlow ->
                                config.verySlowIntervalMs
                            noChangeCount >= config.noChangeThresholdForSlow ->
                                config.slowIntervalMs
                            else -> config.normalIntervalMs
                        }
                    }

                    // 结束
                    if (status.isFinished) {
                        Log.d(TAG, "任务 $taskId 结束: ${status.status}")
                        withContext(Dispatchers.Main) {
                            onFinished(status.isSuccess, status)
                        }
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "轮询异常: $taskId", e)
                    delayMs = config.slowIntervalMs
                }
            }
        }
    }

    /** 请求服务端取消此任务 */
    suspend fun cancel(): Boolean = service.cancelServerTask(serverUrl, taskId)

    /** 停止轮询（不会取消服务端任务） */
    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "ServerTaskPoller"
    }
}