// [file name]: AudioPlaybackService.kt
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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import android.os.Looper
import android.os.Handler

/**
 * 音频后台播放服务
 * 唯一负责音频播放，支持API 23-34
 */
class AudioPlaybackService : Service(), AudioPlaybackListener, AudioProgressListener {
    private lateinit var handler: Handler

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_playback_channel"
        private const val CHANNEL_NAME = "音频播放"
        private var isAutoPlaying = false
        private val autoPlayLock = Any()

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

    // 在AudioPlaybackService.kt的onCreate方法中：
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "音频播放服务创建")

        // 初始化 Handler
        handler = Handler(Looper.getMainLooper())

        // 初始化通知管理器
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()

        // 修改初始化参数 - 传入 HttpClient 而不是 Handler
        AudioPlayerManagerFactory.initialize(this, UnsafeHttpClient.createUnsafeOkHttpClient())
        audioPlayerManager = AudioPlayerManagerFactory.getInstance()

        // 注册监听器
        audioPlayerManager.addPlaybackListener(this)
        audioPlayerManager.addProgressListener(this)

        Log.d(TAG, "音频播放服务初始化完成")
    }

// 在AudioPlaybackService.kt的公共方法区域添加：
    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Long) {
        Log.d(TAG, "跳转到位置: $position ms")
        audioPlayerManager.seekTo(position)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动命令: ${intent?.action}")

        // 处理初始化参数
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

            // 关键修复：确保播放操作顺序执行
            handler.post {
                // 如果有播放列表，设置播放列表
                if (playlist != null && playlist.isNotEmpty()) {
                    Log.d(TAG, "设置播放列表，大小: ${playlist.size}, 起始索引: $startIndex")
                    audioPlayerManager.setPlaylist(playlist, startIndex)

                    // 如果有指定曲目，播放指定曲目
                    track?.let {
                        Log.d(TAG, "播放指定曲目: ${it.name}")
                        // 延迟确保播放列表已设置完成
                        handler.postDelayed({
                            audioPlayerManager.play(it)
                        }, 100)
                    } ?: run {
                        // 否则播放起始索引的曲目
                        if (startIndex in playlist.indices) {
                            handler.postDelayed({
                                audioPlayerManager.playAtIndex(startIndex)
                            }, 100)
                        }
                    }
                } else {
                    // 单个曲目播放
                    track?.let {
                        Log.d(TAG, "播放单个曲目: ${it.name}")
                        handler.postDelayed({
                            audioPlayerManager.play(it)
                        }, 100)
                    }
                }
            }
        }

        // 处理控制命令
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
                    Log.d(TAG, "关闭服务")
                    stopForeground(true)
                    stopSelf()
                }
            }
        }

        // 启动前台服务
        if (!isForeground) {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "启动为前台服务")
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

        // 如果没有活动绑定且不在播放中，停止服务
        if (!isPlaying() && !isForeground) {
            stopSelf()
        }

        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "服务销毁")

        // 移除所有 handler 回调
        handler.removeCallbacksAndMessages(null)

        // 移除监听器
        audioPlayerManager.removePlaybackListener(this)
        audioPlayerManager.removeProgressListener(this)

        // 停止播放
        stopPlayback()

        // 移除通知
        stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)

        super.onDestroy()
    }

    // ==================== 公共方法 ====================

    /**
     * 开始播放
     */
    fun startPlayback(track: AudioTrack? = null) {
        Log.d(TAG, "开始播放: ${track?.name}")

        // 如果有指定曲目，播放它
        track?.let { audioPlayerManager.play(it) }

        // 确保是前台服务
        if (!isForeground) {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        }
    }

    /**
     * 停止播放
     */
    fun stopPlayback() {
        Log.d(TAG, "停止播放")
        audioPlayerManager.stop()

        // 如果不在前台模式，停止服务
        if (!isForeground && !isBound) {
            stopSelf()
        }
    }

    /**
     * 获取当前播放状态
     */
    fun getPlaybackStatus(): AudioPlaybackStatus {
        return audioPlayerManager.getPlaybackStatus()
    }

    /**
     * 获取当前播放曲目
     */
    fun getCurrentTrack(): AudioTrack? {
        return audioPlayerManager.getCurrentTrack()
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean {
        return audioPlayerManager.isPlaying()
    }

    /**
     * 设置播放列表
     */
    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        audioPlayerManager.setPlaylist(tracks, startIndex)
    }

    /**
     * 获取播放列表
     */
    fun getPlaylist(): List<AudioTrack> {
        return audioPlayerManager.getPlaylist()
    }

    /**
     * 添加播放监听器
     */
    fun addPlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.add(listener)
    }

    /**
     * 移除播放监听器
     */
    fun removePlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.remove(listener)
    }

    /**
     * 添加进度监听器
     */
    fun addProgressListener(listener: AudioProgressListener) {
        progressListeners.add(listener)
    }

    /**
     * 移除进度监听器
     */
    fun removeProgressListener(listener: AudioProgressListener) {
        progressListeners.remove(listener)
    }

    // ==================== 通知管理 ====================

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun buildNotification(): Notification {
        val status = audioPlayerManager.getPlaybackStatus()
        val track = status.currentTrack

        // 创建返回应用的Intent
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "audio")
        }

        // 创建PendingIntent
        val contentPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        // 创建控制按钮的PendingIntent
        val playPausePendingIntent = createPendingIntent(ACTION_PLAY_PAUSE)
        val previousPendingIntent = createPendingIntent(ACTION_PREVIOUS)
        val nextPendingIntent = createPendingIntent(ACTION_NEXT)
        val stopPendingIntent = createPendingIntent(ACTION_STOP)
        val closePendingIntent = createPendingIntent(ACTION_CLOSE)

        // 构建通知
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio_notification)
            .setContentTitle(track?.name ?: "音频播放")
            .setContentText(track?.artist ?: "未知艺术家")
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)  // 使用LOW优先级减少干扰
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(status.isPlaying)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)

        // 添加播放控制按钮
        val playPauseIcon = if (status.isPlaying) {
            R.drawable.ic_pause_notification
        } else {
            R.drawable.ic_play_notification
        }

        builder
            .addAction(
                R.drawable.ic_previous_notification,
                "上一首",
                previousPendingIntent
            )
            .addAction(
                playPauseIcon,
                if (status.isPlaying) "暂停" else "播放",
                playPausePendingIntent
            )
            .addAction(
                R.drawable.ic_next_notification,
                "下一首",
                nextPendingIntent
            )

        // 如果Android O以上，设置重要性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID)
        }

        return builder.build()
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

        return PendingIntent.getService(this, 0, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW  // 使用LOW重要性避免干扰
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
        if (isForeground) {
            val notification = buildNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    // ==================== 监听器实现 ====================

    override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
        Log.d(TAG, "播放状态变化: ${status.state}")

        // 更新通知
        updateNotification()

        // 通知外部监听器
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackStateChanged(status)
            }
        }
    }

    override fun onTrackChanged(track: AudioTrack, index: Int) {
        Log.d(TAG, "曲目变化: ${track.name}, 索引: $index")

        // 更新通知
        updateNotification()

        // 通知外部监听器
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onTrackChanged(track, index)
            }
        }
    }

    override fun onPlaybackError(error: String) {
        Log.e(TAG, "播放错误: $error")

        // 通知外部监听器
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackError(error)
            }
        }
    }

    override fun onPlaybackEnded() {
        Log.d(TAG, "播放结束")

        // 防止重复触发自动连播
        synchronized(autoPlayLock) {
            if (isAutoPlaying) {
                Log.d(TAG, "自动连播已在处理中，跳过")
                return
            }
            isAutoPlaying = true
        }

        try {
            // 重要：根据重复模式处理下一首
            val status = audioPlayerManager.getPlaybackStatus()
            when (status.repeatMode) {
                RepeatMode.NONE -> {
                    // 不重复：如果有下一首，播放下一首（自动连播）
                    val playlist = audioPlayerManager.getPlaylist()
                    val currentIndex = audioPlayerManager.getCurrentIndex()
                    if (playlist.isNotEmpty() && currentIndex < playlist.size - 1) {
                        Log.d(TAG, "自动连播：播放下一个")
                        handler.postDelayed({
                            audioPlayerManager.playNext()
                        }, 800) // 稍微延迟，确保UI更新完成
                    } else {
                        Log.d(TAG, "播放列表结束，停止播放")
                        // 播放列表结束，可以停止服务或保持状态
                        // 这里不停止服务，保持通知显示
                    }
                }
                RepeatMode.ONE -> {
                    // 单曲循环：重新播放当前歌曲
                    Log.d(TAG, "单曲循环：重新播放当前歌曲")
                    handler.postDelayed({
                        audioPlayerManager.playAtIndex(audioPlayerManager.getCurrentIndex())
                    }, 800)
                }
                RepeatMode.ALL -> {
                    // 列表循环：播放下一首（或第一首）
                    Log.d(TAG, "列表循环：播放下一个")
                    handler.postDelayed({
                        audioPlayerManager.playNext()
                    }, 800)
                }
            }
        } finally {
            handler.postDelayed({
                synchronized(autoPlayLock) {
                    isAutoPlaying = false
                }
            }, 1000)
        }

        // 通知外部监听器
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackEnded()
            }
        }
    }

    override fun onAudioBuffering(isBuffering: Boolean) {
        // 通知外部监听器
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onAudioBuffering(isBuffering)
            }
        }
    }

    override fun onProgressUpdated(position: Long, duration: Long) {
        // 通知外部监听器
        coroutineScope.launch {
            progressListeners.forEach { listener ->
                listener.onProgressUpdated(position, duration)
            }
        }
    }

    override fun onBufferingProgress(percent: Int) {
        // 通知外部监听器
        coroutineScope.launch {
            progressListeners.forEach { listener ->
                listener.onBufferingProgress(percent)
            }
        }
    }
}