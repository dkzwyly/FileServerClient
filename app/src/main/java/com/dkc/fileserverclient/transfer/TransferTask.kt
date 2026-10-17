package com.dkc.fileserverclient.transfer

import com.google.gson.annotations.SerializedName
import java.io.File

enum class TransferType {
    UPLOAD,
    DOWNLOAD,
    SERVER_MOVE,   // 服务端移动
    SERVER_COPY    // 服务端复制
}

enum class TaskState {
    QUEUED, RUNNING, PAUSED, RETRYING, COMPLETED, FAILED, CANCELLED
}

data class TransferTask(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: TransferType,
    @SerializedName("serverUrl") val serverUrl: String,
    @SerializedName("localPath") val localPath: String,
    @SerializedName("remotePath") val remotePath: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("totalBytes") val totalBytes: Long,
    @SerializedName("transferredBytes") val transferredBytes: Long = 0L,
    @SerializedName("serverTaskId") val serverTaskId: String? = null,
    @SerializedName("state") val state: TaskState = TaskState.QUEUED,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    @SerializedName("retryCount") val retryCount: Int = 0,
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    // ---- URI 上传支持（新）----
    @SerializedName("localUri") val localUri: String? = null,
    @SerializedName("contentLength") val contentLength: Long = -1L
) {
    val progress: Int
        get() = if (totalBytes <= 0) 0
        else ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

    val isFinished: Boolean
        get() = state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED)

    val isActive: Boolean
        get() = state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.RETRYING, TaskState.PAUSED)

    /** 是否走 URI 流式上传（不落盘） */
    val hasLocalUri: Boolean
        get() = !localUri.isNullOrEmpty()

    fun localFile(): File = File(localPath)
}