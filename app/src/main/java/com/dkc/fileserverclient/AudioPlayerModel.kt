package com.dkc.fileserverclient

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.net.URLEncoder

/**
 * 音频播放数据模型（增强版，支持元数据）
 */
@Parcelize
data class AudioTrack(
    val id: String,
    val name: String,
    val url: String,
    val serverUrl: String,
    val path: String,
    val duration: Long = 0L,
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val coverUrl: String? = null,
    val fileExtension: String = "",
    val sizeFormatted: String = ""
) : Parcelable {

    companion object {
        fun fromFileSystemItem(item: FileSystemItem, serverUrl: String): AudioTrack {
            return AudioTrack(
                id = "audio_${item.path.hashCode().toString().replace("-", "n")}",
                name = item.name,
                url = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${URLEncoder.encode(item.path, "UTF-8")}",
                serverUrl = serverUrl,
                path = item.path,
                duration = 0L,
                artist = null,
                album = null,
                title = null,
                coverUrl = null,
                fileExtension = item.extension,
                sizeFormatted = item.sizeFormatted
            )
        }

        /**
         * 使用服务器返回的元数据更新 AudioTrack
         */
        fun fromMetadata(track: AudioTrack, metadata: SongMetadata): AudioTrack {
            return track.copy(
                title = if (metadata.title.isNotEmpty()) metadata.title else track.name,
                artist = if (metadata.artist.isNotEmpty()) metadata.artist else track.artist,
                album = if (metadata.album.isNotEmpty()) metadata.album else track.album,
                coverUrl = if (metadata.hasCover && !metadata.customCoverPath.isNullOrEmpty()) {
                    "${track.serverUrl}/covers/${metadata.customCoverPath}"
                } else null
            )
        }
    }
}

enum class PlaybackState {
    IDLE, LOADING, READY, PLAYING, PAUSED, BUFFERING, ENDED, ERROR
}

enum class RepeatMode {
    NONE, ONE, ALL
}

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<AudioTrack> = emptyList(),
    val currentIndex: Int = 0
)

data class AudioPlaybackStatus(
    val state: PlaybackState,
    val currentTrack: AudioTrack? = null,
    val position: Long = 0L,
    val duration: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val shuffleEnabled: Boolean = false,
    val errorMessage: String? = null
)