package com.dkc.fileserverclient

import com.google.gson.annotations.SerializedName

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
) {
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
        get() = if (name.isEmpty()) {
            if (path.isNotEmpty()) {
                path.substringAfterLast('/').ifEmpty { "未命名文件" }
            } else {
                "未命名文件"
            }
        } else {
            name
        }
}

data class ApiListResponse(
    @SerializedName("currentPath") val currentPath: String = "",
    @SerializedName("parentPath") val parentPath: String = "",
    @SerializedName("directories") val directories: List<DirectoryInfo> = emptyList(),
    @SerializedName("files") val files: List<FileInfoResponse> = emptyList()
)

data class DirectoryInfo(
    @SerializedName("name") val name: String = "",
    @SerializedName("path") val path: String = ""
)

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
)

data class UploadResult(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("files") val files: List<String> = emptyList(),
    @SerializedName("totalSize") val totalSize: Long = 0,
    @SerializedName("totalSizeFormatted") val totalSizeFormatted: String = ""
)