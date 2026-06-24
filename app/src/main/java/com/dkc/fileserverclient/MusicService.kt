package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import okhttp3.OkHttpClient
import java.net.URLEncoder

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val TAG = "MusicService"
    }

    private val mediaLibrarySessionCallback = object : MediaLibraryService.MediaLibrarySession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val serverUrl = prefs.getString("server_url", null).orEmpty().trimEnd('/')
            Log.d(TAG, "onAddMediaItems: serverUrl='$serverUrl'")

            val resolvedMediaItems = mediaItems.map { mediaItem ->
                val currentUri = mediaItem.localConfiguration?.uri
                Log.d(TAG, "Processing mediaId: ${mediaItem.mediaId}, currentUri: $currentUri")

                if (currentUri == null || currentUri == Uri.EMPTY) {
                    if (serverUrl.isNotEmpty() && (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))) {
                        val encodedPath = try {
                            mediaItem.mediaId.split("/").joinToString("/") { segment ->
                                URLEncoder.encode(segment, "UTF-8")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "URL encoding failed for ${mediaItem.mediaId}", e)
                            mediaItem.mediaId
                        }
                        val finalUri = "$serverUrl/api/fileserver/stream/$encodedPath"
                        Log.d(TAG, "Resolved URI: $finalUri")
                        mediaItem.buildUpon()
                            .setUri(finalUri)
                            .build()
                    } else {
                        Log.w(TAG, "Invalid serverUrl, keeping empty URI for mediaId: ${mediaItem.mediaId}")
                        mediaItem
                    }
                } else {
                    Log.d(TAG, "Keeping existing URI: $currentUri")
                    mediaItem
                }
            }
            return Futures.immediateFuture(resolvedMediaItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onSetMediaItems: count=${mediaItems.size}, startIndex=$startIndex, startPositionMs=$startPositionMs")
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onPlaybackResumption called")
            val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            try {
                val itemsJson = prefs.getString("last_playlist", null)
                if (itemsJson.isNullOrEmpty()) {
                    Log.d(TAG, "No saved playlist, returning empty")
                    settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                    return settableFuture
                }

                val serverUrl = prefs.getString("server_url", null).orEmpty().trimEnd('/')
                Log.d(TAG, "Resuming with serverUrl='$serverUrl'")

                val mediaIds = itemsJson.split("|||")
                val mediaItems = mediaIds.map { id ->
                    val uri = if (serverUrl.isNotEmpty() && (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))) {
                        val encodedPath = try {
                            id.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
                        } catch (e: Exception) {
                            id
                        }
                        "$serverUrl/api/fileserver/stream/$encodedPath"
                    } else {
                        ""
                    }
                    Log.d(TAG, "Resuming mediaId=$id, uri=$uri")
                    MediaItem.Builder()
                        .setMediaId(id)
                        .setUri(uri)
                        .build()
                }

                val startIndex = prefs.getInt("last_index", 0)
                    .coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
                val startPosition = prefs.getLong("last_position", 0L)

                Log.d(TAG, "Returning ${mediaItems.size} items, startIndex=$startIndex")
                settableFuture.set(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPosition)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in onPlaybackResumption", e)
                settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
            }
            return settableFuture
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("audio_cache", Context.MODE_PRIVATE)

        // 创建忽略 SSL 证书的 OkHttpClient（复用 UnsafeHttpClient）
        val unsafeOkHttpClient = UnsafeHttpClient.createUnsafeOkHttpClient()
        val httpDataSourceFactory = OkHttpDataSource.Factory(unsafeOkHttpClient)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory))
            )
            .build()

        Log.d(TAG, "MusicService onCreate, server_url in prefs: '${prefs.getString("server_url", "")}'")

        mediaLibrarySession = MediaLibraryService.MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "MusicService onDestroy")
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved, isPlaying=${player.isPlaying}")
        if (!player.isPlaying) stopSelf()
    }
}