package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler

/**
 * 统一媒体播放控制器接口
 */
interface MediaPlaybackController {

    // ==================== 生命周期管理 ====================

    /**
     * 初始化播放器
     */
    fun initialize(context: Context, handler: Handler)

    /**
     * 释放资源
     * @param keepAlive 是否保持服务运行（仅对支持后台播放的播放器有效）
     */
    fun release(keepAlive: Boolean)

    /**
     * 获取播放器类型
     */
    fun getType(): PlaybackType

    /**
     * 是否支持后台播放
     */
    fun supportsBackgroundPlayback(): Boolean

    /**
     * Activity生命周期回调 - 暂停
     * 用于处理Activity进入后台时的行为
     */
    fun onActivityPause()

    /**
     * Activity生命周期回调 - 恢复
     * 用于处理Activity回到前台时的行为
     */
    fun onActivityResume()

    // ==================== 播放控制 ====================

    /**
     * 播放媒体
     * @param item 要播放的媒体项
     * @param playlist 播放列表（可选）
     * @param startIndex 起始索引（可选）
     */
    fun play(item: MediaPlaybackItem, playlist: List<MediaPlaybackItem>?, startIndex: Int)

    /**
     * 播放指定URL的媒体
     */
    fun play(url: String, metadata: MediaPlaybackItem?)

    /**
     * 暂停播放
     */
    fun pause()

    /**
     * 恢复播放
     */
    fun resume()

    /**
     * 停止播放
     */
    fun stop()

    /**
     * 切换播放/暂停状态
     */
    fun togglePlayback()

    /**
     * 跳转到指定位置
     * @param position 位置（毫秒）
     */
    fun seekTo(position: Long)

    /**
     * 快进
     * @param milliseconds 快进毫秒数
     */
    fun fastForward(milliseconds: Long)

    /**
     * 快退
     * @param milliseconds 快退毫秒数
     */
    fun rewind(milliseconds: Long)

    // ==================== 播放列表管理 ====================

    /**
     * 设置播放列表
     */
    fun setPlaylist(tracks: List<MediaPlaybackItem>, startIndex: Int)

    /**
     * 播放下一首
     */
    fun playNext()

    /**
     * 播放上一首
     */
    fun playPrevious()

    /**
     * 跳转到播放列表中的指定位置
     */
    fun playAtIndex(index: Int)

    /**
     * 获取当前播放列表
     */
    fun getPlaylist(): List<MediaPlaybackItem>

    // ==================== 播放设置 ====================

    /**
     * 设置播放速度
     * @param speed 播放速度（0.5x, 1.0x, 1.5x, 2.0x等）
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * 设置重复模式
     */
    fun setRepeatMode(mode: RepeatMode)

    /**
     * 切换随机播放
     * @param enabled 是否启用随机播放
     */
    fun setShuffleEnabled(enabled: Boolean)

    // ==================== 状态获取 ====================

    /**
     * 获取当前播放状态
     */
    fun getPlaybackStatus(): MediaPlaybackStatus

    /**
     * 获取当前播放位置（毫秒）
     */
    fun getCurrentPosition(): Long

    /**
     * 获取媒体总时长（毫秒）
     */
    fun getDuration(): Long

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean

    /**
     * 获取当前播放的媒体项
     */
    fun getCurrentItem(): MediaPlaybackItem?

    /**
     * 获取当前播放索引
     */
    fun getCurrentIndex(): Int

    // ==================== 事件监听 ====================

    /**
     * 添加播放状态监听器
     */
    fun addPlaybackListener(listener: MediaPlaybackListener)

    /**
     * 移除播放状态监听器
     */
    fun removePlaybackListener(listener: MediaPlaybackListener)

    /**
     * 添加播放进度监听器
     */
    fun addProgressListener(listener: MediaProgressListener)

    /**
     * 移除播放进度监听器
     */
    fun removeProgressListener(listener: MediaProgressListener)

    // ==================== UI相关 ====================

    /**
     * 获取播放视图（仅视频播放器需要）
     */
    fun getPlaybackView(): Any?
}

/**
 * 媒体播放类型
 */
enum class PlaybackType {
    VIDEO,
    AUDIO
}

/**
 * 统一的媒体播放项
 */
data class MediaPlaybackItem(
    val id: String,
    val name: String,
    val url: String,
    val path: String,
    val type: PlaybackType,
    val duration: Long = 0L,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        /**
         * 从FileSystemItem创建媒体播放项
         */
        fun fromFileSystemItem(item: FileSystemItem, serverUrl: String): MediaPlaybackItem {
            val isAudio = AudioUtils.isAudioFile(item)
            val type = if (isAudio) PlaybackType.AUDIO else PlaybackType.VIDEO

            return MediaPlaybackItem(
                id = "${type.name.lowercase()}_${item.path.hashCode().toString().replace("-", "n")}",
                name = item.name,
                url = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${java.net.URLEncoder.encode(item.path, "UTF-8")}",
                path = item.path,
                type = type,
                duration = 0L,
                metadata = mapOf(
                    "extension" to item.extension,
                    "sizeFormatted" to item.sizeFormatted,
                    "isDirectory" to item.isDirectory,
                    "isVideo" to !isAudio,
                    "isAudio" to isAudio,
                    "serverUrl" to serverUrl
                )
            )
        }

        /**
         * 从AudioTrack创建媒体播放项
         */
        fun fromAudioTrack(track: AudioTrack): MediaPlaybackItem {
            return MediaPlaybackItem(
                id = track.id,
                name = track.name,
                url = track.url,
                path = track.path,
                type = PlaybackType.AUDIO,
                duration = track.duration,
                metadata = mapOf(
                    "artist" to (track.artist ?: ""),
                    "album" to (track.album ?: ""),
                    "coverUrl" to (track.coverUrl ?: ""),
                    "serverUrl" to track.serverUrl,
                    "fileExtension" to track.fileExtension,
                    "sizeFormatted" to track.sizeFormatted
                )
            )
        }
    }

    /**
     * 转换为AudioTrack（向后兼容）
     */
    fun toAudioTrack(): AudioTrack {
        return AudioTrack(
            id = id,
            name = name,
            url = url,
            serverUrl = metadata["serverUrl"] as? String ?: "",
            path = path,
            duration = duration,
            artist = metadata["artist"] as? String,
            album = metadata["album"] as? String,
            coverUrl = metadata["coverUrl"] as? String,
            fileExtension = metadata["fileExtension"] as? String ?: "",
            sizeFormatted = metadata["sizeFormatted"] as? String ?: ""
        )
    }
}

/**
 * 统一的媒体播放状态
 */
data class MediaPlaybackStatus(
    val state: PlaybackState,
    val currentItem: MediaPlaybackItem? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val shuffleEnabled: Boolean = false,
    val errorMessage: String? = null
) {
    /**
     * 转换为AudioPlaybackStatus（向后兼容）
     */
    fun toAudioPlaybackStatus(): AudioPlaybackStatus {
        return AudioPlaybackStatus(
            state = state,
            currentTrack = currentItem?.toAudioTrack(),
            position = position,
            duration = duration,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            errorMessage = errorMessage
        )
    }
}

/**
 * 媒体播放事件监听器
 */
interface MediaPlaybackListener {
    fun onPlaybackStateChanged(status: MediaPlaybackStatus)
    fun onTrackChanged(item: MediaPlaybackItem, index: Int)
    fun onPlaybackError(error: String)
    fun onPlaybackEnded()
    fun onMediaBuffering(isBuffering: Boolean)
}

/**
 * 媒体播放进度监听器
 */
interface MediaProgressListener {
    fun onProgressUpdated(position: Long, duration: Long)
    fun onBufferingProgress(percent: Int)
}