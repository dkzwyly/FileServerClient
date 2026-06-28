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

    companion object {
        private const val TAG = "MusicService"
        private const val CHANNEL_ID = "music_playback_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "com.dkc.fileserverclient.STOP_SERVICE"

        @Volatile
        var exoPlayer: ExoPlayer? = null

        val exitEvent = SingleLiveEvent<Boolean>()
    }

    // ========== 自定义通知提供者 ==========
    private val notificationProvider = object : MediaNotification.Provider {

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,   // 正确的参数类型
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            val mediaItem = player.currentMediaItem
            val title = mediaItem?.mediaMetadata?.title?.toString() ?: "未知歌曲"
            val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: "未知艺术家"

            // 滑动删除 → 停止服务
            val deleteIntent = Intent(this@MusicService, MusicService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val deletePendingIntent = PendingIntent.getService(
                this@MusicService, 0, deleteIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 点击通知 → 返回播放界面
            val contentIntent = Intent(this@MusicService, AudioPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                this@MusicService, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 使用 actionFactory 将 CommandButton 列表转换为 NotificationCompat.Action 列表
            val actions = customLayout.map { button ->
                when {
                    button.playerCommand != Player.COMMAND_INVALID -> {
                        // 标准播放器命令
                        actionFactory.createMediaAction(
                            mediaSession,
                            IconCompat.createWithResource(this@MusicService, button.iconResId),
                            button.displayName ?: "",
                            button.playerCommand
                        )
                    }
                    button.sessionCommand != null -> {
                        // 自定义命令（如切换循环模式）
                        actionFactory.createCustomAction(
                            mediaSession,
                            IconCompat.createWithResource(this@MusicService, button.iconResId),
                            button.displayName ?: "",
                            button.sessionCommand!!.customAction,
                            button.sessionCommand!!.customExtras
                        )
                    }
                    else -> null
                }
            }.filterNotNull()

            // 构建通知并添加按钮
            val notification = NotificationCompat.Builder(this@MusicService, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentPendingIntent)
                .setDeleteIntent(deletePendingIntent)
                .setOngoing(true)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.sessionCompatToken)
                        .setShowActionsInCompactView(0, 1, 2)  // 紧凑模式显示前三按钮
                )
                .apply {
                    // 将 actions 添加到通知
                    actions.forEach { addAction(it) }
                }
                .build()

            return MediaNotification(NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean {
            when (action) {
                "ACTION_CHANGE_REPEAT_MODE" -> {
                    cycleRepeatMode()
                    return true
                }
            }
            return false
        }
    }

    // ========== 媒体库回调（保持不变） ==========
    private val mediaLibrarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val serverUrl = prefs.getString("server_url", null).orEmpty().trimEnd('/')
            val resolvedMediaItems = mediaItems.map { mediaItem ->
                val currentUri = mediaItem.localConfiguration?.uri
                if (currentUri == null || currentUri == android.net.Uri.EMPTY) {
                    if (serverUrl.isNotEmpty() && (serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))) {
                        val encodedPath = try {
                            mediaItem.mediaId.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
                        } catch (e: Exception) { mediaItem.mediaId }
                        val finalUri = "$serverUrl/api/fileserver/stream/$encodedPath"
                        mediaItem.buildUpon().setUri(finalUri).build()
                    } else mediaItem
                } else mediaItem
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
            Handler(Looper.getMainLooper()).post { applyPlayMode() }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val itemsJson = prefs.getString("last_playlist", null)
            if (itemsJson.isNullOrEmpty()) {
                settableFuture.set(MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L))
                return settableFuture
            }
            val serverUrl = prefs.getString("server_url", "").orEmpty().trimEnd('/')
            val mediaItems = itemsJson.split("|||").map { id ->
                val uri = if (serverUrl.startsWith("http")) {
                    val encodedPath = try { id.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") } } catch (_: Exception) { id }
                    "$serverUrl/api/fileserver/stream/$encodedPath"
                } else ""
                MediaItem.Builder().setMediaId(id).setUri(uri).build()
            }
            val startIndex = prefs.getInt("last_index", 0).coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
            val startPosition = prefs.getLong("last_position", 0L)
            settableFuture.set(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPosition))
            Handler(Looper.getMainLooper()).post { applyPlayMode() }
            return settableFuture
        }
    }

    // ========== 自定义按钮列表 ==========
    private fun buildCommandButtons(): ImmutableList<CommandButton> {
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
                .setIconResId(
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                        else -> R.drawable.ic_repeat_all
                    }
                )
                .setSessionCommand(SessionCommand("ACTION_CHANGE_REPEAT_MODE", Bundle.EMPTY))
                .build()
        )
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
        exoPlayer = player

        val builder = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
        setMediaNotificationProvider(notificationProvider)
        // 设置自定义按钮布局，这些按钮会通过 createNotification 的 customLayout 参数传入
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    // ========== 辅助方法 ==========
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
        player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_ALL
        } else {
            Player.REPEAT_MODE_ONE
        }
        // 状态改变后 Media3 会自动重建通知（重新调用 createNotification 和 buildCommandButtons）
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