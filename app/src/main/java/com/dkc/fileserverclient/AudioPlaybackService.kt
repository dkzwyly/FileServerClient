package com.dkc.fileserverclient

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 音频后台播放服务
 * 唯一负责音频播放，支持 API 23-34
 */
class AudioPlaybackService : Service(), AudioPlaybackListener, AudioProgressListener {
    private lateinit var handler: Handler

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_playback_channel"
        private const val CHANNEL_NAME = "音频播放"

        // 公共常量
        const val ACTION_PLAY_PAUSE = "com.dkc.fileserverclient.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.dkc.fileserverclient.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.dkc.fileserverclient.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.dkc.fileserverclient.ACTION_STOP"
        const val ACTION_CLOSE = "com.dkc.fileserverclient.ACTION_CLOSE"

        // 额外参数
        const val EXTRA_TRACK = "extra_track"
        const val EXTRA_PLAYLIST = "extra_playlist"
        const val EXTRA_START_INDEX = "extra_start_index"

        // 启动服务的方法
        fun startService(context: Context, track: AudioTrack? = null, playlist: ArrayList<AudioTrack>? = null, startIndex: Int = 0) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                if (track != null) {
                    putExtra(EXTRA_TRACK, track)
                }
                if (playlist != null) {
                    putExtra(EXTRA_PLAYLIST, playlist)
                }
                putExtra(EXTRA_START_INDEX, startIndex)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // 发送控制命令
        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }
    }

    // 服务绑定器
    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    private val binder = AudioServiceBinder()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var audioPlayerManager: AudioPlayerManager

    // 服务状态
    private var isForeground = false
    private var isBound = false

    // 监听器列表
    private val playbackListeners = CopyOnWriteArrayList<AudioPlaybackListener>()
    private val progressListeners = CopyOnWriteArrayList<AudioProgressListener>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "音频播放服务创建")

        handler = Handler(Looper.getMainLooper())

        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()

        AudioPlayerManagerFactory.initialize(this, UnsafeHttpClient.createUnsafeOkHttpClient())
        audioPlayerManager = AudioPlayerManagerFactory.getInstance()

        audioPlayerManager.addPlaybackListener(this)
        audioPlayerManager.addProgressListener(this)

        Log.d(TAG, "音频播放服务初始化完成")
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                return notificationManager.areNotificationsEnabled()
            } catch (e: SecurityException) {
                Log.e(TAG, "检查通知权限时出错: ${e.message}")
                return false
            }
        }
        return true
    }

    fun seekTo(position: Long) {
        Log.d(TAG, "跳转到位置: $position ms")
        audioPlayerManager.seekTo(position)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动命令: ${intent?.action}")

        if (!checkNotificationPermission()) {
            Log.w(TAG, "没有通知权限，服务以非前台模式运行")
        } else {
            if (!isForeground) {
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
                isForeground = true
                Log.d(TAG, "启动为前台服务")
            }
        }

        if (intent != null && intent.extras != null) {
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_TRACK, AudioTrack::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_TRACK)
            }

            val playlist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(EXTRA_PLAYLIST, AudioTrack::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(EXTRA_PLAYLIST)
            }

            val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

            handler.post {
                if (playlist != null && playlist.isNotEmpty()) {
                    Log.d(TAG, "设置播放列表，大小: ${playlist.size}, 起始索引: $startIndex")
                    audioPlayerManager.setPlaylist(playlist, startIndex)

                    track?.let {
                        Log.d(TAG, "播放指定曲目: ${it.name}")
                        handler.postDelayed({
                            audioPlayerManager.play(it)
                        }, 100)
                    } ?: run {
                        if (startIndex in playlist.indices) {
                            handler.postDelayed({
                                audioPlayerManager.playAtIndex(startIndex)
                            }, 100)
                        }
                    }
                } else {
                    track?.let {
                        Log.d(TAG, "播放单个曲目: ${it.name}")
                        handler.postDelayed({
                            audioPlayerManager.play(it)
                        }, 100)
                    }
                }
            }
        }

        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    Log.d(TAG, "切换播放/暂停状态")
                    audioPlayerManager.togglePlayback()
                    updateNotification()
                }
                ACTION_NEXT -> {
                    Log.d(TAG, "播放下一首")
                    audioPlayerManager.playNext()
                }
                ACTION_PREVIOUS -> {
                    Log.d(TAG, "播放上一首")
                    audioPlayerManager.playPrevious()
                }
                ACTION_STOP -> {
                    Log.d(TAG, "停止播放")
                    stopPlayback()
                }
                ACTION_CLOSE -> {
                    Log.d(TAG, "收到关闭服务命令")
                    audioPlayerManager.stop()
                    stopForeground(true)
                    stopSelf()

                    val closeIntent = Intent("AUDIO_SERVICE_CLOSED")
                    sendBroadcast(closeIntent)

                    Log.d(TAG, "服务已完全关闭")
                }
                else -> {
                    Log.w(TAG, "收到未知的动作类型: $action")
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "服务绑定")
        isBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "服务解绑")
        isBound = false
        Log.d(TAG, "Activity解绑，但服务继续在后台运行")
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "服务销毁")

        handler.removeCallbacksAndMessages(null)

        audioPlayerManager.removePlaybackListener(this)
        audioPlayerManager.removeProgressListener(this)

        audioPlayerManager.stop()

        stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)

        isForeground = false
        isBound = false

        super.onDestroy()
    }

    // ==================== 公共方法 ====================

    fun startPlayback(track: AudioTrack? = null) {
        Log.d(TAG, "开始播放: ${track?.name}")
        track?.let { audioPlayerManager.play(it) }

        if (!isForeground && checkNotificationPermission()) {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        }
    }

    fun stopPlayback() {
        Log.d(TAG, "停止播放")
        audioPlayerManager.stop()
        if (isForeground) {
            stopForeground(false)
            isForeground = false
        }
        updateNotification()
        Log.d(TAG, "播放已停止，服务仍在运行")
    }

    fun stopServiceCompletely() {
        Log.d(TAG, "完全停止服务")
        audioPlayerManager.stop()
        if (isForeground) {
            stopForeground(true)
            isForeground = false
        }
        stopSelf()
        Log.d(TAG, "服务已完全停止")
    }

    fun getPlaybackStatus(): AudioPlaybackStatus {
        return audioPlayerManager.getPlaybackStatus()
    }

    fun getCurrentTrack(): AudioTrack? {
        return audioPlayerManager.getCurrentTrack()
    }

    fun isPlaying(): Boolean {
        return audioPlayerManager.isPlaying()
    }

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        audioPlayerManager.setPlaylist(tracks, startIndex)
    }

    fun getPlaylist(): List<AudioTrack> {
        return audioPlayerManager.getPlaylist()
    }

    fun addPlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.add(listener)
    }

    fun removePlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.remove(listener)
    }

    fun addProgressListener(listener: AudioProgressListener) {
        progressListeners.add(listener)
    }

    fun removeProgressListener(listener: AudioProgressListener) {
        progressListeners.remove(listener)
    }

    fun setRepeatMode(mode: RepeatMode) {
        audioPlayerManager.setRepeatMode(mode)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        audioPlayerManager.setShuffleEnabled(enabled)
    }

    // ==================== 通知管理 ====================

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun buildNotification(): Notification {
        try {
            val status = audioPlayerManager.getPlaybackStatus()
            val track = status.currentTrack
            val playlist = audioPlayerManager.getPlaylist()
            val currentIndex = audioPlayerManager.getCurrentIndex()

            val contentIntent = Intent(this, PreviewActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("AUDIO_TRACK", track)
                putExtra("AUDIO_TRACKS", ArrayList(playlist))
                putExtra("CURRENT_INDEX", currentIndex)
                putExtra("FROM_NOTIFICATION", true)
                putExtra("SHOULD_AUTO_PLAY", status.isPlaying)
                putExtra("SERVER_URL", track?.serverUrl ?: "")
                putExtra("FILE_NAME", track?.name ?: "音频播放")
                putExtra("FILE_TYPE", "audio")
                track?.let { putExtra("FILE_PATH", it.path) }
            }

            val contentPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    this,
                    0,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                @Suppress("DEPRECATION")
                PendingIntent.getActivity(
                    this,
                    0,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_audio_notification)
                .setContentTitle(track?.name ?: "音频播放")
                .setContentText(track?.artist ?: "未知艺术家")
                .setContentIntent(contentPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(false)
                .setAutoCancel(true)
                .setDeleteIntent(createDeleteIntent())
                .setShowWhen(false)
                .setOnlyAlertOnce(true)

            if (checkNotificationPermission()) {
                val playPauseIcon = if (status.isPlaying) {
                    R.drawable.ic_pause_notification
                } else {
                    R.drawable.ic_play_notification
                }

                builder
                    .addAction(
                        R.drawable.ic_previous_notification,
                        "上一首",
                        createPendingIntent(ACTION_PREVIOUS)
                    )
                    .addAction(
                        playPauseIcon,
                        if (status.isPlaying) "暂停" else "播放",
                        createPendingIntent(ACTION_PLAY_PAUSE)
                    )
                    .addAction(
                        R.drawable.ic_next_notification,
                        "下一首",
                        createPendingIntent(ACTION_NEXT)
                    )
                    .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "关闭",
                        createPendingIntent(ACTION_CLOSE)
                    )
            }

            return builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "构建通知失败: ${e.message}")
            e.printStackTrace()
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_audio_notification)
                .setContentTitle("音频播放")
                .setContentText("正在播放")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .build()
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            this.action = action
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val requestCode = when (action) {
            ACTION_PLAY_PAUSE -> 0
            ACTION_PREVIOUS -> 1
            ACTION_NEXT -> 2
            ACTION_CLOSE -> 3
            else -> 4
        }

        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "音频播放通知"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    enableLights(false)
                    setSound(null, null)
                }

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Log.d(TAG, "创建通知渠道成功")
            } catch (e: Exception) {
                Log.e(TAG, "创建通知渠道失败: ${e.message}")
            }
        }
    }

    private fun updateNotification() {
        try {
            if (isForeground) {
                if (!checkNotificationPermission()) {
                    Log.w(TAG, "没有通知权限，无法更新通知")
                    return
                }

                val notification = buildNotification()
                notificationManager.notify(NOTIFICATION_ID, notification)
                Log.d(TAG, "通知更新成功")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "更新通知时出现权限错误: ${e.message}")
            createNotificationChannel()
            handler.postDelayed({
                try {
                    if (isForeground) {
                        val notification = buildNotification()
                        notificationManager.notify(NOTIFICATION_ID, notification)
                        Log.d(TAG, "重新尝试更新通知成功")
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "重新尝试更新通知失败: ${e2.message}")
                    if (isForeground) {
                        try {
                            stopForeground(false)
                            isForeground = false
                            Log.d(TAG, "已停止前台服务（降级处理）")
                        } catch (e3: Exception) {
                            Log.e(TAG, "停止前台服务失败: ${e3.message}")
                        }
                    }
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "更新通知时出现其他错误: ${e.message}")
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createDeleteIntent(): PendingIntent {
        Log.d(TAG, "创建删除Intent：滑动清除时停止服务")
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            action = ACTION_CLOSE
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getService(this, 2, intent, flags)
    }

    // ==================== 监听器实现 ====================

    override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
        Log.d(TAG, "播放状态变化: ${status.state}")
        updateNotification()
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackStateChanged(status)
            }
        }
    }

    override fun onTrackChanged(track: AudioTrack, index: Int) {
        Log.d(TAG, "曲目变化: ${track.name}, 索引: $index")
        updateNotification()
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onTrackChanged(track, index)
            }
        }
    }

    override fun onPlaybackError(error: String) {
        Log.e(TAG, "播放错误: $error")
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackError(error)
            }
        }
    }

    override fun onPlaybackEnded() {
        Log.d(TAG, "播放结束 - 交由 ExoPlayer 自动处理连播")
        updateNotification()
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackEnded()
            }
        }
    }

    override fun onAudioBuffering(isBuffering: Boolean) {
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onAudioBuffering(isBuffering)
            }
        }
    }

    override fun onProgressUpdated(position: Long, duration: Long) {
        coroutineScope.launch {
            progressListeners.forEach { listener ->
                listener.onProgressUpdated(position, duration)
            }
        }
    }

    override fun onBufferingProgress(percent: Int) {
        coroutineScope.launch {
            progressListeners.forEach { listener ->
                listener.onBufferingProgress(percent)
            }
        }
    }
}