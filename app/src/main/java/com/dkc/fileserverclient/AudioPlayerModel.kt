package com.dkc.fileserverclient

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 音频播放数据模型
 */
@Parcelize
data class AudioTrack(
    val id: String,
    val name: String,
    val url: String,
    val serverUrl: String,
    val path: String,           // 用于歌词查找
    val duration: Long = 0L,    // 时长（毫秒）
    val artist: String? = null,
    val album: String? = null,
    val coverUrl: String? = null,
    val fileExtension: String = "",
    val sizeFormatted: String = ""
) : Parcelable {
    companion object {
        fun fromFileSystemItem(item: FileSystemItem, serverUrl: String): AudioTrack {
            return AudioTrack(
                id = "audio_${item.path.hashCode().toString().replace("-", "n")}",
                name = item.name,
                url = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${java.net.URLEncoder.encode(item.path, "UTF-8")}",
                serverUrl = serverUrl,
                path = item.path,
                duration = 0L, // 需要从元数据获取
                fileExtension = item.extension,
                sizeFormatted = item.sizeFormatted
            )
        }
    }
}

/**
 * 播放状态枚举
 */
enum class PlaybackState {
    IDLE,           // 空闲
    LOADING,        // 加载中
    READY,          // 准备就绪
    PLAYING,        // 播放中
    PAUSED,         // 已暂停
    BUFFERING,      // 缓冲中
    ENDED,          // 播放结束
    ERROR           // 错误
}

/**
 * 重复模式
 */
enum class RepeatMode {
    NONE,           // 不重复
    ONE,            // 单曲循环
    ALL             // 列表循环
}

/**
 * 播放列表数据
 */
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<AudioTrack> = emptyList(),
    val currentIndex: Int = 0
)

/**
 * 音频播放状态
 */
data class AudioPlaybackStatus(
    val state: PlaybackState,
    val currentTrack: AudioTrack? = null,
    val position: Long = 0L,           // 当前播放位置（毫秒）
    val duration: Long = 0L,           // 总时长（毫秒）
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val shuffleEnabled: Boolean = false,
    val errorMessage: String? = null
)