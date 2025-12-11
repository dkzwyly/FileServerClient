package com.dkc.fileserverclient

import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import java.net.URLEncoder

class AutoPlayManager(
    private val handler: Handler,
    private val coroutineScope: CoroutineScope
) {
    private var autoPlayEnabled = false
    private var mediaFileList: ArrayList<FileSystemItem>? = null
    private var audioTrackList: List<AudioTrack>? = null
    private var currentMediaIndex = -1
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""

    // 新增：播放模式标识
    private var playbackMode: PlaybackMode = PlaybackMode.VIDEO

    enum class PlaybackMode {
        VIDEO,    // 视频模式：使用原有的自动连播逻辑
        AUDIO     // 音频模式：使用后台服务状态管理
    }

    // 监听器接口
    interface AutoPlayListener {
        fun onLoadMediaFile(fileName: String, fileUrl: String, fileType: String, index: Int, filePath: String)
        fun onLoadAudioTrack(track: AudioTrack, index: Int)
        fun onAutoPlayError(message: String)
    }

    private var autoPlayListener: AutoPlayListener? = null

    fun setAutoPlayListener(listener: AutoPlayListener) {
        this.autoPlayListener = listener
    }

    fun setupAutoPlay(
        enabled: Boolean,
        fileList: ArrayList<FileSystemItem>?,
        audioTracks: List<AudioTrack>?,
        currentIndex: Int,
        serverUrl: String,
        directoryPath: String
    ) {
        autoPlayEnabled = enabled
        mediaFileList = fileList
        audioTrackList = audioTracks
        currentMediaIndex = currentIndex
        currentServerUrl = serverUrl
        currentDirectoryPath = directoryPath

        // 根据传入的数据判断播放模式
        playbackMode = if (audioTracks != null && audioTracks.isNotEmpty()) {
            PlaybackMode.AUDIO
        } else {
            PlaybackMode.VIDEO
        }

        Log.d("AutoPlayManager", "设置自动连播模式: $playbackMode, 启用: $enabled, 当前索引: $currentIndex")
    }

    // 视频模式：使用原有的自动连播逻辑
    fun playNextMedia() {
        if (!autoPlayEnabled) return

        // 根据播放模式执行不同的逻辑
        when (playbackMode) {
            PlaybackMode.AUDIO -> {
                // 音频模式：通过后台服务控制，这里不做任何事
                Log.d("AutoPlayManager", "音频模式，下一首由后台服务处理")
                return
            }
            PlaybackMode.VIDEO -> {
                // 视频模式：使用原有的逻辑
                if (mediaFileList == null) return

                val nextIndex = currentMediaIndex + 1
                if (nextIndex < mediaFileList!!.size) {
                    loadMediaFile(nextIndex)
                } else {
                    autoPlayListener?.onAutoPlayError("已经是最后一个文件")
                }
            }
        }
    }

    fun playPreviousMedia() {
        if (!autoPlayEnabled) return

        when (playbackMode) {
            PlaybackMode.AUDIO -> {
                // 音频模式：通过后台服务控制
                Log.d("AutoPlayManager", "音频模式，上一首由后台服务处理")
                return
            }
            PlaybackMode.VIDEO -> {
                // 视频模式：使用原有的逻辑
                if (mediaFileList == null) return

                val prevIndex = currentMediaIndex - 1
                if (prevIndex >= 0) {
                    loadMediaFile(prevIndex)
                } else {
                    autoPlayListener?.onAutoPlayError("已经是第一个文件")
                }
            }
        }
    }

    private fun loadMediaFile(index: Int) {
        try {
            val item = mediaFileList!![index]
            val fileType = getFileType(item)
            val encodedPath = URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            currentMediaIndex = index

            autoPlayListener?.onLoadMediaFile(item.name, fileUrl, fileType, index, item.path)

        } catch (e: Exception) {
            autoPlayListener?.onAutoPlayError("加载媒体文件失败: ${e.message}")
            if (autoPlayEnabled) {
                handler.postDelayed({
                    playNextMedia()
                }, 2000)
            }
        }
    }

    // 音频模式：通过后台服务加载音频轨道
    fun loadAudioTrack(index: Int) {
        try {
            val track = audioTrackList!![index]
            currentMediaIndex = index
            autoPlayListener?.onLoadAudioTrack(track, index)
        } catch (e: Exception) {
            autoPlayListener?.onAutoPlayError("加载音频失败: ${e.message}")
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

    fun getMediaListSize(): Int {
        return when (playbackMode) {
            PlaybackMode.AUDIO -> audioTrackList?.size ?: 0
            PlaybackMode.VIDEO -> mediaFileList?.size ?: 0
        }
    }

    fun getCurrentFileName(): String {
        return when (playbackMode) {
            PlaybackMode.AUDIO -> {
                if (audioTrackList != null && currentMediaIndex >= 0 && currentMediaIndex < audioTrackList!!.size) {
                    audioTrackList!![currentMediaIndex].name
                } else {
                    ""
                }
            }
            PlaybackMode.VIDEO -> {
                if (mediaFileList != null && currentMediaIndex >= 0 && currentMediaIndex < mediaFileList!!.size) {
                    mediaFileList!![currentMediaIndex].name
                } else {
                    ""
                }
            }
        }
    }

    /**
     * 获取当前文件的完整路径
     */
    fun getCurrentFilePath(): String {
        return when (playbackMode) {
            PlaybackMode.AUDIO -> {
                if (audioTrackList != null && currentMediaIndex >= 0 && currentMediaIndex < audioTrackList!!.size) {
                    audioTrackList!![currentMediaIndex].path
                } else {
                    ""
                }
            }
            PlaybackMode.VIDEO -> {
                if (mediaFileList != null && currentMediaIndex >= 0 && currentMediaIndex < mediaFileList!!.size) {
                    mediaFileList!![currentMediaIndex].path
                } else {
                    ""
                }
            }
        }
    }

    // 获取当前播放模式
    fun getPlaybackMode(): PlaybackMode = playbackMode

    // 判断是否为音频模式
    fun isAudioMode(): Boolean = playbackMode == PlaybackMode.AUDIO

    // 判断是否为视频模式
    fun isVideoMode(): Boolean = playbackMode == PlaybackMode.VIDEO

    // 获取当前音频轨道
    fun getCurrentAudioTrack(): AudioTrack? {
        return if (isAudioMode() && audioTrackList != null && currentMediaIndex in audioTrackList!!.indices) {
            audioTrackList!![currentMediaIndex]
        } else {
            null
        }
    }
}