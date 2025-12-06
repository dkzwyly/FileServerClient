package com.dkc.fileserverclient

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class FileSystemItem(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("size") val size: Long = 0,
    @SerializedName("sizeFormatted") val sizeFormatted: String = "",
    @SerializedName("extension") val extension: String = "",
    @SerializedName("lastModified") val lastModified: String = "",
    @SerializedName("isVideo") val isVideo: Boolean = false,
    @SerializedName("isAudio") val isAudio: Boolean = false,
    @SerializedName("mimeType") val mimeType: String = "",
    @SerializedName("encoding") val encoding: String = "",
    @SerializedName("hasThumbnail") val hasThumbnail: Boolean = false
) : Parcelable {
    // 修复：更严格的文件夹判断
    val isDirectory: Boolean
        get() = mimeType == "inode/directory" || (size == 0L && extension.isEmpty() && !isImage)

    // 修复：更严格的图片文件判断
    val isImage: Boolean
        get() {
            if (isDirectory) return false // 确保文件夹不会被识别为图片
            val ext = extension.lowercase()
            return ext in listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".jfif")
        }

    val displayName: String
        get() = name.ifEmpty {
            path.substringAfterLast('/').ifEmpty { "未命名文件" }
        }
}

@Parcelize
data class ApiListResponse(
    @SerializedName("currentPath") val currentPath: String = "",
    @SerializedName("parentPath") val parentPath: String = "",
    @SerializedName("directories") val directories: List<DirectoryInfo> = emptyList(),
    @SerializedName("files") val files: List<FileInfoResponse> = emptyList()
) : Parcelable

@Parcelize
data class DirectoryInfo(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = ""
) : Parcelable

@Parcelize
data class FileInfoResponse(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("size") val size: Long = 0,
    @SerializedName("sizeFormatted") val sizeFormatted: String = "",
    @SerializedName("extension") val extension: String = "",
    @SerializedName("lastModified") val lastModified: String = "",
    @SerializedName("isVideo") val isVideo: Boolean = false,
    @SerializedName("isAudio") val isAudio: Boolean = false,
    @SerializedName("mimeType") val mimeType: String = "",
    @SerializedName("encoding") val encoding: String = ""
) : Parcelable

// ====== 新增：上传文件详细信息 ======
@Parcelize
data class UploadedFileInfo(
    @SerializedName("originalName") val originalName: String = "",
    @SerializedName("savedName") val savedName: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("size") val size: Long = 0,
    @SerializedName("wasRenamed") val wasRenamed: Boolean = false,
    @SerializedName("renameReason") val renameReason: String = "",
    @SerializedName("uploadTime") val uploadTime: String = "",
    @SerializedName("success") val success: Boolean = true,
    @SerializedName("errorMessage") val errorMessage: String = ""
) : Parcelable

// ====== 新增：冲突解决信息 ======
@Parcelize
data class ConflictResolutionInfo(
    @SerializedName("originalName") val originalName: String = "",
    @SerializedName("finalName") val finalName: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("timestamp") val timestamp: String = "",
    @SerializedName("resolutionStrategy") val resolutionStrategy: String = "",
    @SerializedName("action") val action: String = "Renamed"
) : Parcelable

// ====== 修改后的 UploadResult ======
@Parcelize
data class UploadResult(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",

    // 保持向后兼容：原有字段
    @Deprecated("请使用 uploadedFiles 字段替代")
    @SerializedName("files") val files: List<String> = emptyList(),

    @SerializedName("totalSize") val totalSize: Long = 0,
    @SerializedName("totalSizeFormatted") val totalSizeFormatted: String = "",

    // ====== 新增字段 ======
    @SerializedName("uploadedFiles") val uploadedFiles: List<UploadedFileInfo> = emptyList(),
    @SerializedName("resolvedConflicts") val resolvedConflicts: List<ConflictResolutionInfo> = emptyList(),
    @SerializedName("totalFiles") val totalFiles: Int = 0,
    @SerializedName("successfulUploads") val successfulUploads: Int = 0,
    @SerializedName("conflictsResolved") val conflictsResolved: Int = 0,
    @SerializedName("failedUploads") val failedUploads: Int = 0,
    @SerializedName("uploadTime") val uploadTime: String = "",
    @SerializedName("requestId") val requestId: String = "",
    @SerializedName("processingTime") val processingTime: String = ""
) : Parcelable {

    // 辅助方法：获取成功上传的文件列表
    val successfulFiles: List<UploadedFileInfo>
        get() = uploadedFiles.filter { it.success }

    // 辅助方法：获取失败的文件列表
    val failedFiles: List<UploadedFileInfo>
        get() = uploadedFiles.filter { !it.success }

    // 辅助方法：获取重命名的文件列表
    val renamedFiles: List<UploadedFileInfo>
        get() = uploadedFiles.filter { it.wasRenamed }

    // 辅助方法：获取冲突解决数量
    fun getConflictResolutionCount(): Int {
        return resolvedConflicts.size
    }

    // 辅助方法：格式化显示时间
    fun getFormattedUploadTime(): String {
        return uploadTime.ifEmpty { "未知时间" }
    }

    // 辅助方法：获取处理时间（毫秒）
    fun getProcessingTimeMillis(): Long {
        return processingTime.toLongOrNull() ?: 0
    }
}