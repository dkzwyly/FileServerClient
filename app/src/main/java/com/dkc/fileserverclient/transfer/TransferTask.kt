package com.dkc.fileserverclient.transfer

import com.google.gson.annotations.SerializedName
import java.io.File

enum class TransferType {
    UPLOAD,
    DOWNLOAD,
    SERVER_MOVE,   // 新增
    SERVER_COPY    // 新增
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
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis()
) {
    val progress: Int
        get() = if (totalBytes <= 0) 0
        else ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

    val isFinished: Boolean
        get() = state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED)

    val isActive: Boolean
        get() = state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.RETRYING, TaskState.PAUSED)

    fun localFile(): File = File(localPath)
}