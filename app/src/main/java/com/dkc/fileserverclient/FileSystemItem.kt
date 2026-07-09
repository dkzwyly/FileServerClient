package com.dkc.fileserverclient

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

// ==================== 核心文件项 ====================
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

// ==================== 列表响应 ====================
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

// ==================== 上传相关 ====================
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

    fun getConflictResolutionCount(): Int = resolvedConflicts.size
    fun getFormattedUploadTime(): String = uploadTime.ifEmpty { "未知时间" }
    fun getProcessingTimeMillis(): Long = processingTime.toLongOrNull() ?: 0
}

// ==================== 操作结果（重命名/移动/复制） ====================
data class OperationResult(
    val success: Boolean,
    val message: String? = null
)

// ==================== 影视库树节点 ====================
data class VideoLibraryNode(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = "",
    @SerializedName("type") val type: String = "",               // "root", "season", "video"
    @SerializedName("children") val children: List<VideoLibraryNode>? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("sizeFormatted") val sizeFormatted: String? = null
)

// ==================== 歌词相关 ====================
data class LyricsMappingRequest(
    val songPath: String,
    val lyricsPath: String
)

@Parcelize
data class LyricsFileInfo(
    @SerializedName("path") val path: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("size") val size: Long = 0,
    @SerializedName("sizeFormatted") val sizeFormatted: String = "",
    @SerializedName("modifiedTime") val modifiedTime: String = ""
) : Parcelable

@Parcelize
data class LyricsResponse(
    @SerializedName("type") val type: String = "",
    @SerializedName("lyricsPath") val lyricsPath: String? = null,
    @SerializedName("fileName") val fileName: String? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("encoding") val encoding: String? = null,
    @SerializedName("files") val files: List<LyricsFileInfo>? = null,
    @SerializedName("message") val message: String? = null
) : Parcelable

@Parcelize
data class LyricsMappingResponse(
    @SerializedName("songPath") val songPath: String = "",
    @SerializedName("lyricsPath") val lyricsPath: String = "",
    @SerializedName("lyricsFileName") val lyricsFileName: String = "",
    @SerializedName("exists") val exists: Boolean = false,
    @SerializedName("isNoLyrics") val isNoLyrics: Boolean = false
) : Parcelable

// ==================== 歌曲元数据 ====================
@Parcelize
data class SongMetadata(
    @SerializedName("filePath") val filePath: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("artist") val artist: String = "",
    @SerializedName("album") val album: String = "",
    @SerializedName("hasCover") val hasCover: Boolean = false,
    @SerializedName("customCoverPath") val customCoverPath: String? = null
) : Parcelable

@Parcelize
data class SongMetadataResponse(
    @SerializedName("path") val path: String = "",
    @SerializedName("fileName") val fileName: String = "",
    @SerializedName("metadata") val metadata: SongMetadata
) : Parcelable

data class SaveMetadataMappingRequest(
    val songPath: String,
    val title: String?,
    val artist: String?,
    val album: String?
)

// ==================== 回收站 ====================
@Parcelize
data class TrashRecord(
    @SerializedName("id") val id: String = "",
    @SerializedName("originalPath") val originalPath: String = "",
    @SerializedName("deletedTime") val deletedTime: String = "",
    @SerializedName("isDirectory") val isDirectory: Boolean = false,
    @SerializedName("fileSize") val fileSize: Long = 0,
    @SerializedName("fingerprint") val fingerprint: String? = null
) : Parcelable

// ==================== 任务状态 ====================
@Parcelize
data class TaskStatus(
    @SerializedName("taskId") val taskId: String = "",
    @SerializedName("status") val status: String = "",          // Queued, Processing, Completed, Failed
    @SerializedName("progressPercent") val progressPercent: Int = 0,
    @SerializedName("errorMessage") val errorMessage: String? = null,
    @SerializedName("queueTime") val queueTime: String = "",
    @SerializedName("completeTime") val completeTime: String? = null
) : Parcelable

// ==================== 章节索引 ====================
@Parcelize
data class ChapterInfo(
    @SerializedName("title") val title: String = "",
    @SerializedName("page") val page: Int = 0,
    @SerializedName("preview") val preview: String = "",
    @SerializedName("startCharOffset") val startCharOffset: Int = 0
) : Parcelable

@Parcelize
data class ChapterIndex(
    @SerializedName("fileName") val fileName: String = "",
    @SerializedName("totalChapters") val totalChapters: Int = 0,
    @SerializedName("chapters") val chapters: List<ChapterInfo> = emptyList()
) : Parcelable

@Parcelize
data class ChapterIndexInfo(
    @SerializedName("exists") val exists: Boolean = false,
    @SerializedName("path") val path: String = "",
    @SerializedName("size") val size: Long = 0,
    @SerializedName("lastModified") val lastModified: String = ""
) : Parcelable

// ==================== 视频缩略图 ====================
data class VideoThumbnailRequest(
    val videoPath: String,
    val positionPercentage: Double = 10.0,
    val width: Int = 320,
    val height: Int = 180
)

@Parcelize
data class VideoThumbnailResult(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("thumbnailPath") val thumbnailPath: String? = null,
    @SerializedName("positionPercentage") val positionPercentage: Double? = null,
    @SerializedName("videoDurationFormatted") val videoDurationFormatted: String? = null,
    @SerializedName("thumbnailTimeFormatted") val thumbnailTimeFormatted: String? = null,
    @SerializedName("message") val message: String? = null
) : Parcelable

@Parcelize
data class VideoInfo(
    @SerializedName("fileName") val fileName: String = "",
    @SerializedName("fileSize") val fileSize: Long = 0,
    @SerializedName("fileSizeFormatted") val fileSizeFormatted: String = "",
    @SerializedName("duration") val duration: String? = null,          // "hh:mm:ss"
    @SerializedName("durationSeconds") val durationSeconds: Double? = null,
    @SerializedName("supportedForThumbnail") val supportedForThumbnail: Boolean = false
) : Parcelable

@Parcelize
data class ThumbnailGenerationStatus(
    @SerializedName("queueLength") val queueLength: Int = 0,
    @SerializedName("generatedCount") val generatedCount: Int = 0
) : Parcelable

// ==================== 照片搜索 ====================
@Parcelize
data class PhotoSearchResult(
    @SerializedName("page") val page: Int = 0,
    @SerializedName("pageSize") val pageSize: Int = 0,
    @SerializedName("totalCount") val totalCount: Int = 0,
    @SerializedName("totalPages") val totalPages: Int = 0,
    @SerializedName("items") val items: List<PhotoMetadataItem> = emptyList()
) : Parcelable

@Parcelize
data class PhotoMetadataItem(
    @SerializedName("path") val path: String = "",
    @SerializedName("dateTaken") val dateTaken: String? = null,        // ISO 8601
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("make") val make: String? = null,
    @SerializedName("model") val model: String? = null,
    @SerializedName("orientation") val orientation: Int? = null
    // 可根据实际响应补充更多字段
) : Parcelable

// ==================== 服务器状态（可选） ====================
// 使用 Map 直接解析，无需专用类