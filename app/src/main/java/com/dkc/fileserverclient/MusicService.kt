package com.dkc.fileserverclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.net.URLEncoder

@OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var prefs: SharedPreferences
    private var savedActionFactory: MediaNotification.ActionFactory? = null

    companion object {
        private const val TAG = "MusicService"
        private const val CHANNEL_ID = "music_playback_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "com.dkc.fileserverclient.STOP_SERVICE"

        @Volatile
        var exoPlayer: ExoPlayer? = null

        val exitEvent = SingleLiveEvent<Boolean>()
    }

    // ========== 通知提供者 ==========
    private val notificationProvider = object : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            savedActionFactory = actionFactory
            val notification = buildNotificationInternal()
            return MediaNotification(NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean {
            if (action == "ACTION_CHANGE_REPEAT_MODE") {
                cycleRepeatMode()
                return true
            }
            return false
        }
    }

    // ========== 媒体库回调 ==========
    private val mediaLibrarySessionCallback = object : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val serverUrl = prefs.getString("server_url", null).orEmpty().trimEnd('/')
            val resolved = mediaItems.map { item ->
                val uri = item.localConfiguration?.uri
                if (uri == null || uri == android.net.Uri.EMPTY) {
                    if (serverUrl.startsWith("http")) {
                        val encoded = try {
                            item.mediaId.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
                        } catch (e: Exception) { item.mediaId }
                        item.buildUpon().setUri("$serverUrl/api/fileserver/stream/$encoded").build()
                    } else item
                } else item
            }
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Handler(Looper.getMainLooper()).post { applyPlayMode() }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val itemsJson = prefs.getString("last_playlist", null)
            if (itemsJson.isNullOrEmpty()) {
                future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                return future
            }
            val serverUrl = prefs.getString("server_url", "").orEmpty().trimEnd('/')
            val mediaItems = itemsJson.split("|||").map { id ->
                val uri = if (serverUrl.startsWith("http")) {
                    val encoded = try { id.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") } } catch (_: Exception) { id }
                    "$serverUrl/api/fileserver/stream/$encoded"
                } else ""
                MediaItem.Builder().setMediaId(id).setUri(uri).build()
            }
            val startIndex = prefs.getInt("last_index", 0).coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
            val startPosition = prefs.getLong("last_position", 0L)
            future.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPosition))
            Handler(Looper.getMainLooper()).post { applyPlayMode() }
            return future
        }
    }

    // ========== 按钮列表 ==========
    private fun buildCommandButtons(): ImmutableList<CommandButton> {
        // 循环模式按钮的图标根据当前播放模式决定
        val repeatIcon = when {
            player.shuffleModeEnabled -> R.drawable.ic_shuffle          // 随机播放
            player.repeatMode == Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one   // 单曲循环
            else -> R.drawable.ic_repeat_all                           // 列表循环
        }

        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("上一曲")
                .setIconResId(R.drawable.ic_previous_notification)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build(),
            CommandButton.Builder()
                .setDisplayName(if (player.isPlaying) "暂停" else "播放")
                .setIconResId(if (player.isPlaying) R.drawable.ic_pause_notification else R.drawable.ic_play_notification)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .build(),
            CommandButton.Builder()
                .setDisplayName("下一曲")
                .setIconResId(R.drawable.ic_next_notification)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build(),
            CommandButton.Builder()
                .setDisplayName("循环模式")
                .setIconResId(repeatIcon)
                .setSessionCommand(SessionCommand("ACTION_CHANGE_REPEAT_MODE", Bundle.EMPTY))
                .build()
        )
    }

    // ========== 通知构建 ==========
    private fun buildNotificationInternal(): Notification {
        val mediaItem = player.currentMediaItem
        val title = mediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲"
        val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家"

        val deleteIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val deletePendingIntent = PendingIntent.getService(
            this, 0, deleteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentIntent = Intent(this, AudioPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 构建通知按钮动作 —— 修复点：直接使用 mediaLibrarySession，无需 .mediaSession
        val actions = buildCommandButtons().mapNotNull { button ->
            val factory = savedActionFactory ?: return@mapNotNull null
            when {
                button.playerCommand != Player.COMMAND_INVALID -> {
                    factory.createMediaAction(
                        mediaLibrarySession!!,   // 修复：原为 mediaLibrarySession?.mediaSession!!
                        IconCompat.createWithResource(this, button.iconResId),
                        button.displayName ?: "",
                        button.playerCommand
                    )
                }
                button.sessionCommand != null -> {
                    factory.createCustomAction(
                        mediaLibrarySession!!,   // 修复：原为 mediaLibrarySession?.mediaSession!!
                        IconCompat.createWithResource(this, button.iconResId),
                        button.displayName ?: "",
                        button.sessionCommand!!.customAction,
                        button.sessionCommand!!.customExtras
                    )
                }
                else -> null
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaLibrarySession?.sessionCompatToken) // 修复：原为 mediaLibrarySession?.mediaSession?.sessionCompatToken
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .apply { actions.forEach { addAction(it) } }
            .build()
    }

    private fun updateNotification() {
        val notification = buildNotificationInternal()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ========== 生命周期 ==========
    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("audio_cache", Context.MODE_PRIVATE)
        createNotificationChannel()

        val unsafeOkHttpClient = UnsafeHttpClient.createUnsafeOkHttpClient()
        val httpDataSourceFactory = OkHttpDataSource.Factory(unsafeOkHttpClient)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory))
            )
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()
                    override fun onRepeatModeChanged(repeatMode: Int) = updateNotification()
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateNotification()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = updateNotification()
                })
            }
        exoPlayer = player

        val builder = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
        setMediaNotificationProvider(notificationProvider)
        builder.setCustomLayout(buildCommandButtons())
        mediaLibrarySession = builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopPlaybackAndExit()
            return START_NOT_STICKY
        }
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        exoPlayer = null
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    // ========== 辅助 ==========
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun cycleRepeatMode() {
        // 三种模式轮换：列表循环 → 单曲循环 → 随机播放 → 列表循环...
        when {
            player.shuffleModeEnabled -> {
                // 当前是随机，切换到列表循环
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            player.repeatMode == Player.REPEAT_MODE_ONE -> {
                // 当前是单曲，切换到随机
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
            else -> {
                // 当前是列表循环，切换到单曲
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
        }
        // 自动触发 onRepeatModeChanged 或 onShuffleModeEnabledChanged，通知会更新
    }

    private fun applyPlayMode() {
        val modePrefs = getSharedPreferences("audio_library_play_mode", Context.MODE_PRIVATE)
        when (modePrefs.getInt("current_play_mode", PlaylistDetailActivity.MODE_LIST)) {
            PlaylistDetailActivity.MODE_LIST -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }
            PlaylistDetailActivity.MODE_SINGLE -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
            PlaylistDetailActivity.MODE_RANDOM -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }
        }
    }

    private fun stopPlaybackAndExit() {
        player.stop()
        exitEvent.postValue(true)
        stopSelf()
    }
}