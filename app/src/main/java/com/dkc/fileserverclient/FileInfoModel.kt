package com.dkc.fileserverclient

data class FileInfoModel(
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    val sizeFormatted: String = "",
    val extension: String = "",
    val lastModified: String = "",
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val mimeType: String = "",
    val encoding: String = ""
) {
    // 判断是否为图片文件 - 修复扩展名判断
    val isImage: Boolean
        get() {
            val ext = extension.lowercase().removePrefix(".")
            return ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico", "tiff", "tif")
        }

    // 判断是否为文本文件
    val isText: Boolean
        get() {
            val ext = extension.lowercase().removePrefix(".")
            return ext in listOf("txt", "log", "xml", "json", "csv", "html", "htm", "css", "js", "md", "cs", "java", "cpp", "c", "py", "php", "rb", "config", "yml", "yaml", "ini", "sql")
        }

    // 判断是否为媒体文件（音频或视频）
    val isMedia: Boolean
        get() {
            val ext = extension.lowercase().removePrefix(".")
            return isVideo || isAudio || ext in listOf("mp4", "avi", "mov", "wmv", "flv", "webm", "mkv", "mp3", "wav", "ogg", "flac", "aac", "m4a")
        }
}