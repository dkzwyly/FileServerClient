package com.dkc.fileserverclient

import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import java.net.URLEncoder

class AutoPlayManager(
    private val handler: Handler,
    private val coroutineScope: CoroutineScope
) {
    private var autoPlayEnabled = false
    private var mediaFileList: ArrayList<FileSystemItem>? = null
    private var currentMediaIndex = -1
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""

    // 监听器接口
    interface AutoPlayListener {
        fun onLoadMediaFile(fileName: String, fileUrl: String, fileType: String, index: Int, filePath: String)
        fun onAutoPlayError(message: String)
    }

    private var autoPlayListener: AutoPlayListener? = null

    fun setAutoPlayListener(listener: AutoPlayListener) {
        this.autoPlayListener = listener
    }

    fun setupAutoPlay(
        enabled: Boolean,
        fileList: ArrayList<FileSystemItem>?,
        currentIndex: Int,
        serverUrl: String,
        directoryPath: String
    ) {
        autoPlayEnabled = enabled
        mediaFileList = fileList
        currentMediaIndex = currentIndex
        currentServerUrl = serverUrl
        currentDirectoryPath = directoryPath
    }

    fun playNextMedia() {
        if (!autoPlayEnabled || mediaFileList == null) return

        val nextIndex = currentMediaIndex + 1
        if (nextIndex < mediaFileList!!.size) {
            loadMediaFile(nextIndex)
        } else {
            // 已经是最后一个，显示提示
            autoPlayListener?.onAutoPlayError("已经是最后一个文件")
            // 可以选择循环播放
            // loadMediaFile(0)
        }
    }

    fun playPreviousMedia() {
        if (!autoPlayEnabled || mediaFileList == null) return

        val prevIndex = currentMediaIndex - 1
        if (prevIndex >= 0) {
            loadMediaFile(prevIndex)
        } else {
            // 已经是第一个文件
            autoPlayListener?.onAutoPlayError("已经是第一个文件")
        }
    }

    private fun loadMediaFile(index: Int) {
        try {
            val item = mediaFileList!![index]
            val fileType = getFileType(item)
            val encodedPath = URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            currentMediaIndex = index

            // 传递完整路径
            autoPlayListener?.onLoadMediaFile(item.name, fileUrl, fileType, index, item.path)

        } catch (e: Exception) {
            autoPlayListener?.onAutoPlayError("加载媒体文件失败: ${e.message}")
            // 加载失败时尝试播放下一个
            if (autoPlayEnabled) {
                handler.postDelayed({
                    playNextMedia()
                }, 2000)
            }
        }
    }

    private fun getFileType(item: FileSystemItem): String {
        return when {
            item.isVideo -> "video"
            item.isAudio -> "audio"
            item.extension in listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp") -> "image"
            item.extension in listOf(".txt", ".log", ".json", ".xml", ".csv", ".md",
                ".html", ".htm", ".css", ".js", ".java", ".kt", ".py") -> "text"
            else -> "general"
        }
    }

    fun getCurrentIndex(): Int = currentMediaIndex

    fun setCurrentIndex(index: Int) {
        currentMediaIndex = index
    }

    fun isAutoPlayEnabled(): Boolean = autoPlayEnabled

    fun getMediaListSize(): Int = mediaFileList?.size ?: 0

    fun getCurrentFileName(): String {
        return if (mediaFileList != null && currentMediaIndex >= 0 && currentMediaIndex < mediaFileList!!.size) {
            mediaFileList!![currentMediaIndex].name
        } else {
            ""
        }
    }

    // 新增方法：获取当前文件的完整路径
    fun getCurrentFilePath(): String {
        return if (mediaFileList != null && currentMediaIndex >= 0 && currentMediaIndex < mediaFileList!!.size) {
            mediaFileList!![currentMediaIndex].path
        } else {
            ""
        }
    }

    // 获取当前文件项
    fun getCurrentFileItem(): FileSystemItem? {
        return if (mediaFileList != null && currentMediaIndex >= 0 && currentMediaIndex < mediaFileList!!.size) {
            mediaFileList!![currentMediaIndex]
        } else {
            null
        }
    }
}