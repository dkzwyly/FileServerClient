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

class AudioPlaybackService : Service(), AudioPlaybackListener, AudioProgressListener {
    private lateinit var handler: Handler

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_playback_channel"
        private const val CHANNEL_NAME = "音频播放"

        const val ACTION_PLAY_PAUSE = "com.dkc.fileserverclient.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.dkc.fileserverclient.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.dkc.fileserverclient.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.dkc.fileserverclient.ACTION_STOP"
        const val ACTION_CLOSE = "com.dkc.fileserverclient.ACTION_CLOSE"

        const val ACTION_SET_SPEED = "com.dkc.fileserverclient.ACTION_SET_SPEED"
        const val EXTRA_SPEED = "extra_speed"
        const val ACTION_SET_REPEAT_MODE = "com.dkc.fileserverclient.ACTION_SET_REPEAT_MODE"
        const val EXTRA_REPEAT_MODE = "extra_repeat_mode"
        const val ACTION_SET_SHUFFLE = "com.dkc.fileserverclient.ACTION_SET_SHUFFLE"
        const val EXTRA_SHUFFLE_ENABLED = "extra_shuffle_enabled"

        const val EXTRA_TRACK = "extra_track"
        const val EXTRA_PLAYLIST = "extra_playlist"
        const val EXTRA_START_INDEX = "extra_start_index"

        // 🔥 新增：播放模式常量（与 PlaylistDetailActivity 保持一致）
        const val MODE_LIST = 0
        const val MODE_SINGLE = 1
        const val MODE_RANDOM = 2

        fun startService(context: Context, track: AudioTrack? = null, playlist: ArrayList<AudioTrack>? = null, startIndex: Int = 0) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply {
                if (track != null) putExtra(EXTRA_TRACK, track)
                if (playlist != null) putExtra(EXTRA_PLAYLIST, playlist)
                putExtra(EXTRA_START_INDEX, startIndex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, AudioPlaybackService::class.java).apply { this.action = action }
            context.startService(intent)
        }
    }

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }

    private val binder = AudioServiceBinder()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var audioPlayerManager: AudioPlayerManager
    private var isForeground = false
    private var isBound = false
    private val playbackListeners = CopyOnWriteArrayList<AudioPlaybackListener>()
    private val progressListeners = CopyOnWriteArrayList<AudioProgressListener>()
    private val spectrumListeners = CopyOnWriteArrayList<AudioSpectrumListener>()

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannel()
        AudioPlayerManagerFactory.initialize(this, UnsafeHttpClient.createUnsafeOkHttpClient())
        audioPlayerManager = AudioPlayerManagerFactory.getInstance()
        audioPlayerManager.addPlaybackListener(this)
        audioPlayerManager.addProgressListener(this)
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return try {
                notificationManager.areNotificationsEnabled()
            } catch (e: SecurityException) {
                false
            }
        }
        return true
    }

    fun seekTo(position: Long) {
        audioPlayerManager.seekTo(position)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!checkNotificationPermission()) {
            Log.w(TAG, "没有通知权限")
        } else if (!isForeground) {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
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
                if (!playlist.isNullOrEmpty()) {
                    audioPlayerManager.setPlaylist(playlist, startIndex)
                    track?.let {
                        handler.postDelayed({ audioPlayerManager.play(it) }, 100)
                    } ?: run {
                        if (startIndex in playlist.indices) {
                            handler.postDelayed({ audioPlayerManager.playAtIndex(startIndex) }, 100)
                        }
                    }
                } else {
                    track?.let { handler.postDelayed({ audioPlayerManager.play(it) }, 100) }
                }
            }
        }

        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY_PAUSE -> {
                    audioPlayerManager.togglePlayback()
                    updateNotification()
                }
                ACTION_NEXT -> audioPlayerManager.playNext()
                ACTION_PREVIOUS -> audioPlayerManager.playPrevious()
                ACTION_STOP -> stopPlayback()
                ACTION_CLOSE -> {
                    audioPlayerManager.stop()
                    stopForeground(true)
                    isForeground = false
                    val closeIntent = Intent("AUDIO_SERVICE_CLOSED").apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(closeIntent)
                    handler.postDelayed({
                        stopSelf()
                    }, 100)
                }
                ACTION_SET_SPEED -> {
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                    audioPlayerManager.setPlaybackSpeed(speed)
                }
                ACTION_SET_REPEAT_MODE -> {
                    val modeOrdinal = intent.getIntExtra(EXTRA_REPEAT_MODE, RepeatMode.NONE.ordinal)
                    val mode = RepeatMode.values().getOrElse(modeOrdinal) { RepeatMode.NONE }
                    audioPlayerManager.setRepeatMode(mode)
                }
                ACTION_SET_SHUFFLE -> {
                    val enabled = intent.getBooleanExtra(EXTRA_SHUFFLE_ENABLED, false)
                    audioPlayerManager.setShuffleEnabled(enabled)
                }
                else -> {}
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        isBound = true
        handler.post {
            val status = audioPlayerManager.getPlaybackStatus()
            playbackListeners.forEach { it.onPlaybackStateChanged(status) }
            progressListeners.forEach { it.onProgressUpdated(status.position, status.duration) }
        }
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isBound = false
        return true
    }

    override fun onDestroy() {
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

    fun startPlayback(track: AudioTrack? = null) {
        track?.let { audioPlayerManager.play(it) }
        if (!isForeground && checkNotificationPermission()) {
            startForeground(NOTIFICATION_ID, buildNotification())
            isForeground = true
        }
    }

    fun stopPlayback() {
        audioPlayerManager.stop()
        if (isForeground) {
            stopForeground(false)
            isForeground = false
        }
        updateNotification()
    }

    fun stopServiceCompletely() {
        audioPlayerManager.stop()
        if (isForeground) stopForeground(true)
        stopSelf()
    }

    fun getPlaybackStatus(): AudioPlaybackStatus = audioPlayerManager.getPlaybackStatus()
    fun getCurrentTrack(): AudioTrack? = audioPlayerManager.getCurrentTrack()
    fun isPlaying(): Boolean = audioPlayerManager.isPlaying()
    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) = audioPlayerManager.setPlaylist(tracks, startIndex)
    fun getPlaylist(): List<AudioTrack> = audioPlayerManager.getPlaylist()
    fun addPlaybackListener(listener: AudioPlaybackListener) = playbackListeners.add(listener)
    fun removePlaybackListener(listener: AudioPlaybackListener) = playbackListeners.remove(listener)
    fun addProgressListener(listener: AudioProgressListener) = progressListeners.add(listener)
    fun removeProgressListener(listener: AudioProgressListener) = progressListeners.remove(listener)
    fun addSpectrumListener(listener: AudioSpectrumListener) {
        spectrumListeners.add(listener)
        audioPlayerManager.addSpectrumListener(listener)
    }
    fun removeSpectrumListener(listener: AudioSpectrumListener) {
        spectrumListeners.remove(listener)
        audioPlayerManager.removeSpectrumListener(listener)
    }
    fun setRepeatMode(mode: RepeatMode) = audioPlayerManager.setRepeatMode(mode)
    fun setShuffleEnabled(enabled: Boolean) = audioPlayerManager.setShuffleEnabled(enabled)

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun buildNotification(): Notification {
        val status = audioPlayerManager.getPlaybackStatus()
        val track = status.currentTrack
        val playlist = audioPlayerManager.getPlaylist()
        val currentIndex = audioPlayerManager.getCurrentIndex()

        // 🔥 根据当前播放状态计算模式
        val currentMode = when {
            status.shuffleEnabled -> MODE_RANDOM
            status.repeatMode == RepeatMode.ONE -> MODE_SINGLE
            else -> MODE_LIST
        }

        val contentIntent = Intent(this, AudioPlayerActivity::class.java).apply {
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
            // 🔥 新增：传递当前播放模式
            putExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, currentMode)
        }

        val contentPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT)
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
            val playPauseIcon = if (status.isPlaying) R.drawable.ic_pause_notification else R.drawable.ic_play_notification
            builder.addAction(R.drawable.ic_previous_notification, "上一首", createPendingIntent(ACTION_PREVIOUS))
                .addAction(playPauseIcon, if (status.isPlaying) "暂停" else "播放", createPendingIntent(ACTION_PLAY_PAUSE))
                .addAction(R.drawable.ic_next_notification, "下一首", createPendingIntent(ACTION_NEXT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", createPendingIntent(ACTION_CLOSE))
        }
        return builder.build()
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply { this.action = action }
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
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "音频播放通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (isForeground && checkNotificationPermission()) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createDeleteIntent(): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply { action = ACTION_CLOSE }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getService(this, 2, intent, flags)
    }

    override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
        updateNotification()
        coroutineScope.launch { playbackListeners.forEach { it.onPlaybackStateChanged(status) } }
    }

    override fun onTrackChanged(track: AudioTrack, index: Int) {
        updateNotification()
        coroutineScope.launch { playbackListeners.forEach { it.onTrackChanged(track, index) } }
    }

    override fun onPlaybackError(error: String) {
        coroutineScope.launch { playbackListeners.forEach { it.onPlaybackError(error) } }
    }

    override fun onPlaybackEnded() {
        updateNotification()
        coroutineScope.launch { playbackListeners.forEach { it.onPlaybackEnded() } }
    }

    override fun onAudioBuffering(isBuffering: Boolean) {
        coroutineScope.launch { playbackListeners.forEach { it.onAudioBuffering(isBuffering) } }
    }

    override fun onProgressUpdated(position: Long, duration: Long) {
        coroutineScope.launch { progressListeners.forEach { it.onProgressUpdated(position, duration) } }
    }

    override fun onBufferingProgress(percent: Int) {
        coroutineScope.launch { progressListeners.forEach { it.onBufferingProgress(percent) } }
    }
}