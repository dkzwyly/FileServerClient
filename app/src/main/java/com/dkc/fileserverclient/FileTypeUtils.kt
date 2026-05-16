package com.dkc.fileserverclient

import android.util.Log

object FileTypeUtils {

    // 支持的图片扩展名（不带点）
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "jfif")

    // 支持的文本扩展名（不带点）
    private val textExtensions = setOf(
        "txt", "log", "json", "xml", "csv", "md",
        "html", "htm", "css", "js", "java", "kt", "py"
    )

    /**
     * 获取文件类型
     * @return "image", "video", "audio", "text", "general"
     */
    fun getFileType(item: FileSystemItem): String {
        val ext = item.extension.removePrefix(".").lowercase()

        return when {
            // 优先按扩展名判断，避免服务器错误标记
            ext in imageExtensions -> "image"
            ext in textExtensions -> "text"
            item.isVideo -> "video"
            item.isAudio -> "audio"
            else -> "general"
        }
    }

    /**
     * 带日志的版本，用于调试
     */
    fun getFileTypeWithLog(item: FileSystemItem, tag: String = "FileTypeUtils"): String {
        val type = getFileType(item)
        Log.d(tag, "getFileType: name=${item.name}, ext=${item.extension}, isVideo=${item.isVideo}, isAudio=${item.isAudio}, result=$type")
        return type
    }
}