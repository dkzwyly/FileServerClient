package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.ui.PlayerView

/**
 * 媒体播放器工厂
 * 负责创建和管理各种类型的媒体播放控制器
 */
object MediaPlaybackFactory {

    private const val TAG = "MediaPlaybackFactory"

    /**
     * 创建视频播放控制器（新版）
     */
    fun createVideoController(
        httpClient: okhttp3.OkHttpClient,
        playerView: PlayerView,
        videoLoadingProgress: ProgressBar,
        playPauseButton: ImageButton,
        seekBar: SeekBar,
        currentTimeTextView: TextView,
        durationTextView: TextView,
        uiHandler: Handler
    ): MediaPlaybackController {
        Log.d(TAG, "创建视频播放控制器")
        return VideoPlaybackController(
            httpClient = httpClient,
            playerView = playerView,
            videoLoadingProgress = videoLoadingProgress,
            playPauseButton = playPauseButton,
            seekBar = seekBar,
            currentTimeTextView = currentTimeTextView,
            durationTextView = durationTextView,
            uiHandler = uiHandler
        )
    }

    /**
     * 创建音频播放控制器
     */
    fun createAudioController(
        httpClient: okhttp3.OkHttpClient
    ): MediaPlaybackController {
        Log.d(TAG, "创建音频播放控制器")
        return AudioPlaybackController(httpClient)
    }

    /**
     * 根据文件类型创建播放控制器（推荐使用此方法）
     */
    fun createController(
        type: PlaybackType,
        httpClient: okhttp3.OkHttpClient,
        playerView: PlayerView? = null,
        videoLoadingProgress: ProgressBar? = null,
        playPauseButton: ImageButton? = null,
        seekBar: SeekBar? = null,
        currentTimeTextView: TextView? = null,
        durationTextView: TextView? = null,
        uiHandler: Handler? = null
    ): MediaPlaybackController {
        Log.d(TAG, "创建播放控制器，类型: $type")

        return when (type) {
            PlaybackType.VIDEO -> {
                // 验证视频播放器所需的所有UI组件
                if (playerView == null || videoLoadingProgress == null ||
                    playPauseButton == null || seekBar == null ||
                    currentTimeTextView == null || durationTextView == null ||
                    uiHandler == null) {
                    throw IllegalArgumentException("Video playback requires all UI components: " +
                            "playerView, videoLoadingProgress, playPauseButton, " +
                            "seekBar, currentTimeTextView, durationTextView, uiHandler")
                }

                createVideoController(
                    httpClient = httpClient,
                    playerView = playerView,
                    videoLoadingProgress = videoLoadingProgress,
                    playPauseButton = playPauseButton,
                    seekBar = seekBar,
                    currentTimeTextView = currentTimeTextView,
                    durationTextView = durationTextView,
                    uiHandler = uiHandler
                )
            }

            PlaybackType.AUDIO -> {
                createAudioController(httpClient)
            }
        }
    }

    /**
     * 根据FileSystemItem创建播放控制器
     */
    fun createControllerForFileItem(
        fileItem: FileSystemItem,
        serverUrl: String,
        httpClient: okhttp3.OkHttpClient,
        playerView: PlayerView? = null,
        videoLoadingProgress: ProgressBar? = null,
        playPauseButton: ImageButton? = null,
        seekBar: SeekBar? = null,
        currentTimeTextView: TextView? = null,
        durationTextView: TextView? = null,
        uiHandler: Handler? = null
    ): MediaPlaybackController {
        Log.d(TAG, "为文件创建播放控制器: ${fileItem.name}, 扩展名: ${fileItem.extension}")

        val type = getFileType(fileItem)
        return createController(
            type = type,
            httpClient = httpClient,
            playerView = playerView,
            videoLoadingProgress = videoLoadingProgress,
            playPauseButton = playPauseButton,
            seekBar = seekBar,
            currentTimeTextView = currentTimeTextView,
            durationTextView = durationTextView,
            uiHandler = uiHandler
        )
    }

    /**
     * 判断FileSystemItem是否为视频文件
     */
    fun isVideoFile(item: FileSystemItem): Boolean {
        return when (item.extension.lowercase()) {
            ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm",
            ".m4v", ".3gp", ".mpeg", ".mpg", ".ts", ".m2ts" -> true
            else -> false
        }
    }

    /**
     * 判断FileSystemItem是否为音频文件
     */
    fun isAudioFile(item: FileSystemItem): Boolean {
        return AudioUtils.isAudioFile(item)
    }

    /**
     * 获取文件的播放类型
     */
    fun getFileType(item: FileSystemItem): PlaybackType {
        return when {
            isVideoFile(item) -> PlaybackType.VIDEO
            isAudioFile(item) -> PlaybackType.AUDIO
            else -> throw IllegalArgumentException("不支持的文件类型: ${item.extension}")
        }
    }

    /**
     * 根据文件扩展名获取MIME类型
     */
    fun getMimeTypeForExtension(extension: String): String {
        return when (extension.lowercase()) {
            ".mp4", ".m4v" -> "video/mp4"
            ".avi" -> "video/x-msvideo"
            ".mkv" -> "video/x-matroska"
            ".mov" -> "video/quicktime"
            ".wmv" -> "video/x-ms-wmv"
            ".flv" -> "video/x-flv"
            ".webm" -> "video/webm"
            ".3gp" -> "video/3gpp"
            ".mpeg", ".mpg" -> "video/mpeg"
            ".ts" -> "video/mp2t"
            ".m2ts" -> "video/mp2t"

            ".mp3" -> "audio/mpeg"
            ".ogg", ".oga" -> "audio/ogg"
            ".wav" -> "audio/wav"
            ".flac" -> "audio/flac"
            ".aac" -> "audio/aac"
            ".m4a" -> "audio/mp4"
            ".opus" -> "audio/opus"

            else -> "*/*"
        }
    }

    /**
     * 创建MediaPlaybackItem
     */
    fun createMediaPlaybackItem(
        fileItem: FileSystemItem,
        serverUrl: String
    ): MediaPlaybackItem {
        val isAudio = isAudioFile(fileItem)
        val type = if (isAudio) PlaybackType.AUDIO else PlaybackType.VIDEO

        return MediaPlaybackItem(
            id = "${type.name.lowercase()}_${fileItem.path.hashCode().toString().replace("-", "n")}",
            name = fileItem.name,
            url = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/" +
                    java.net.URLEncoder.encode(fileItem.path, "UTF-8"),
            path = fileItem.path,
            type = type,
            duration = 0L,
            metadata = mapOf(
                "extension" to fileItem.extension,
                "sizeFormatted" to fileItem.sizeFormatted,
                "isDirectory" to fileItem.isDirectory,
                "isVideo" to !isAudio,
                "isAudio" to isAudio,
                "serverUrl" to serverUrl,
                "lastModified" to fileItem.lastModified
            )
        )
    }

    /**
     * 创建播放列表
     */
    fun createPlaylist(
        fileItems: List<FileSystemItem>,
        serverUrl: String,
        filterByType: PlaybackType? = null
    ): List<MediaPlaybackItem> {
        return fileItems
            .filter { item ->
                when (filterByType) {
                    PlaybackType.VIDEO -> isVideoFile(item)
                    PlaybackType.AUDIO -> isAudioFile(item)
                    null -> isVideoFile(item) || isAudioFile(item)
                }
            }
            .map { item ->
                createMediaPlaybackItem(item, serverUrl)
            }
    }

    /**
     * 从AudioTrack列表创建播放列表
     */
    fun createPlaylistFromAudioTracks(tracks: List<AudioTrack>): List<MediaPlaybackItem> {
        return tracks.map { track ->
            MediaPlaybackItem.fromAudioTrack(track)
        }
    }
}