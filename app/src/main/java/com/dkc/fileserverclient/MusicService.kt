package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("audio_cache", Context.MODE_PRIVATE)
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .build()
    }

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            player.stop()
            stopSelf()
        }
    }

    // 1. 修正 onPlaybackResumption 方法
    // 确认此方法在你使用的 Media3 版本中存在。如果不存在，请考虑升级依赖或查阅对应版本的官方文档。
    @OptIn(UnstableApi::class)
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {

        val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()

        try {
            val itemsJson = prefs.getString("last_playlist", null)
            if (itemsJson.isNullOrEmpty()) {
                // 返回空的媒体项列表
                settableFuture.set(
                    MediaSession.MediaItemsWithStartPosition.create(
                        emptyList(),   // List<MediaItem>
                        0,             // startIndex
                        0L             // startPositionMs
                    )
                )
                return settableFuture
            }

            // 解析保存的播放列表
            val mediaIds = itemsJson.split("|||")
            val mediaItems = mediaIds.map { id ->
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(Uri.EMPTY)   // 实际项目中应恢复真实的 URI
                    .build()
            }

            val startIndex = prefs.getInt("last_index", 0)
                .coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
            val startPosition = prefs.getLong("last_position", 0L)

            settableFuture.set(
                MediaSession.MediaItemsWithStartPosition.create(
                    mediaItems,
                    startIndex,
                    startPosition
                )
            )
        } catch (e: Exception) {
            // 发生异常时也返回空列表，保证 future 必定完成
            settableFuture.set(
                MediaSession.MediaItemsWithStartPosition.create(emptyList(), 0, 0L)
            )
        }

        return settableFuture
    }

    private inner class MediaSessionCallback : MediaSession.Callback() {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // 直接返回传入的列表，不做修改
            return Futures.immediateFuture(mediaItems)
        }

        // 3. 修正 onSetMediaItems 方法的返回类型
        // 确保返回类型是 ListenableFuture<MediaSession.MediaItemsWithStartPosition>
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // 设置播放列表并准备
            player.setMediaItems(mediaItems, startIndex, startPositionMs)
            player.prepare()
            // 返回封装的结果
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }
    }
}