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
    @SerializedName("dateTaken") val dateTaken: String? = null,
    @SerializedName("hasThumbnail") val hasThumbnail: Boolean = false

) : Parcelable {
    val isDirectory: Boolean
        get() = mimeType == "inode/directory" || (size == 0L && extension.isEmpty() && !isImage)

    val isImage: Boolean
        get() {
            if (isDirectory) return false
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

@Parcelize
data class ConflictResolutionInfo(
    @SerializedName("originalName") val originalName: String = "",
    @SerializedName("finalName") val finalName: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("timestamp") val timestamp: String = "",
    @SerializedName("resolutionStrategy") val resolutionStrategy: String = "",
    @SerializedName("action") val action: String = "Renamed"
) : Parcelable

@Parcelize
data class UploadResult(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",

    @Deprecated("请使用 uploadedFiles 字段替代")
    @SerializedName("files") val files: List<String> = emptyList(),

    @SerializedName("totalSize") val totalSize: Long = 0,
    @SerializedName("totalSizeFormatted") val totalSizeFormatted: String = "",

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

    val successfulFiles: List<UploadedFileInfo>
        get() = uploadedFiles.filter { it.success }

    val failedFiles: List<UploadedFileInfo>
        get() = uploadedFiles.filter { !it.success }

    val renamedFiles: List<UploadedFileInfo>
        get() = uploadedFiles.filter { it.wasRenamed }

    fun getConflictResolutionCount(): Int {
        return resolvedConflicts.size
    }

    fun getFormattedUploadTime(): String {
        return uploadTime.ifEmpty { "未知时间" }
    }

    fun getProcessingTimeMillis(): Long {
        return processingTime.toLongOrNull() ?: 0
    }
}

// ==================== 新增：影视库树节点 ====================
data class VideoLibraryNode(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("type") val type: String = "",               // "root", "season", "video"
    @SerializedName("children") val children: List<VideoLibraryNode>? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("sizeFormatted") val sizeFormatted: String? = null
)