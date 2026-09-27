package com.dkc.fileserverclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class AudiobookService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "audiobook_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "AudiobookService"
        const val PREF_TTS_ENGINE = "tts_engine_package"
        const val ACTION_STOP_SERVICE = "com.dkc.fileserverclient.action.STOP_SERVICE"
        const val ACTION_PLAY = "com.dkc.fileserverclient.action.PLAY"
        const val ACTION_PAUSE = "com.dkc.fileserverclient.action.PAUSE"
        const val FULL_VOLUME = 1.0f

        private const val RETRY_BASE_DELAY = 2000L
        private const val RETRY_MAX_DELAY = 30000L
        private const val MAX_AUTO_RETRIES = 5
    }

    private lateinit var repository: PageRepository

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private var speechRate = 1.0f

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private var isAutoPlay = false
    private var wasPlayingBeforeFocusLoss = false
    private var autoPlayPausedDueToNetwork = false

    private var fileName = ""
    private var fileUrl = ""
    private var filePath = ""

    private lateinit var readingPrefs: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pageFlowJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // 只缓存“正在朗读哪一页”，用于识别朗读期间页面是否被外部改变；
    // 不再作为分页状态来源，分页完全由 PageRepository 持有。
    private var currentSpeakingOffset: Int = -1
    private var currentSpokenContent: String = ""

    // 通知去重：前台服务通知必须用 startForeground 持续刷新，
    // 但相同内容不必重复发送，避免 onDone 路径上无谓开销。
    private var lastNotificationTitle: String? = null
    private var lastNotificationContent: String? = null

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallbackRegistered = false
    private var networkRecoveryJob: Job? = null

    private lateinit var mediaSession: MediaSessionCompat

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "网络可用")
            networkRecoveryJob?.cancel()
            networkRecoveryJob = serviceScope.launch {
                delay(1000L)
                if (connectivityManager.activeNetwork != null) {
                    if (autoPlayPausedDueToNetwork && isAutoPlay && !isSpeaking) {
                        Log.d(TAG, "网络恢复，自动重试翻页")
                        autoPlayPausedDueToNetwork = false
                        updateNotification(currentSpokenContent)
                        autoNextPageWithRetry()
                    } else if (isAutoPlay && !isSpeaking) {
                        autoNextPageWithRetry()
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "网络丢失 $network")
        }
    }

    private val nextPageMutex = Mutex()

    inner class LocalBinder : Binder() {
        fun getService(): AudiobookService = this@AudiobookService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "AudiobookService").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { startAutoPlay() }
                override fun onPause() { pause() }
                override fun onStop() { stop() }
            })
            isActive = true
        }

        startForegroundIfNeeded()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        repository = PageRepository.getInstance(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        // 关键：关闭引用计数，避免 isHeld 与实际持有状态不一致导致后续不再 acquire。
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudiobookService::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        readingPrefs = getSharedPreferences("reading_prefs", MODE_PRIVATE)
        speechRate = readingPrefs.getFloat("tts_speed", 1.0f)

        pageFlowJob = serviceScope.launch {
            repository.pageContentFlow.collect { uiData ->
                uiData?.let { data ->
                    val newOffset = data.state.absoluteCharOffset

                    if (isSpeaking && currentSpeakingOffset != -1 && newOffset != currentSpeakingOffset) {
                        Log.d(TAG, "页面在朗读期间被外部改变: $currentSpeakingOffset -> $newOffset")
                        tts?.stop()
                        isSpeaking = false
                        releaseWakeLocks()
                        currentSpeakingOffset = newOffset
                        currentSpokenContent = data.content.toString()
                        updateNotification(currentSpokenContent)
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                        return@collect
                    }

                    if (!isSpeaking) {
                        currentSpokenContent = data.content.toString()
                        updateNotification(currentSpokenContent)
                    }
                }
            }
        }

        serviceScope.launch {
            repository.errorEvents.collect { msg ->
                if (isAutoPlay) {
                    updateNotification("错误: ${msg.take(30)}")
                }
            }
        }

        requestBatteryOptimizationIfNeeded()

        val ttsPrefs = getSharedPreferences("tts_prefs", MODE_PRIVATE)
        val enginePackage = ttsPrefs.getString(PREF_TTS_ENGINE, null)
        tts = if (enginePackage.isNullOrEmpty()) TextToSpeech(this, this)
        else TextToSpeech(this, this, enginePackage)

        isAutoPlay = readingPrefs.getBoolean("is_auto_play", false)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations()) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
            }
        }
    }

    private fun startPlaybackIfReady() {
        val content = repository.getCurrentPageContent()
        if (content.isNotEmpty()) {
            speakContent(content)
            Handler(Looper.getMainLooper()).post {
                registerNetworkCallbackIfNeeded()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stop()
            ACTION_PLAY -> startAutoPlay()
            ACTION_PAUSE -> pause()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        networkRecoveryJob?.cancel()
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        stopForegroundAndRemoveNotification()   // ← 改这里
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWakeLocks()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        autoPlayPausedDueToNetwork = false
        super.onDestroy()
    }

    fun setupFile(name: String, url: String, path: String) {
        this.fileName = name
        this.fileUrl = url
        this.filePath = path
        repository.setupFile(name, url, path)
    }

    fun startAutoPlay() {
        autoPlayPausedDueToNetwork = false
        readingPrefs.edit().putBoolean("is_auto_play", true).apply()
        wasPlayingBeforeFocusLoss = false
        isAutoPlay = true
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            requestBatteryOptimizationIfNeeded()
        }

        val state = repository.getCurrentPageState()
        if (state != null) {
            val content = repository.getCurrentPageContent()
            if (content.isNotEmpty()) {
                speakContent(content)
                Handler(Looper.getMainLooper()).post {
                    registerNetworkCallbackIfNeeded()
                }
            }
        } else {
            serviceScope.launch {
                val timeout = withTimeoutOrNull(10_000L) {
                    repository.pageContentFlow.first { it != null }
                }
                if (timeout != null) {
                    speakContent(timeout.content.toString())
                    Handler(Looper.getMainLooper()).post {
                        registerNetworkCallbackIfNeeded()
                    }
                } else {
                    isAutoPlay = false
                    updateNotification("内容加载超时")
                    updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                    readingPrefs.edit().putBoolean("is_auto_play", false).apply()
                }
            }
        }
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (!networkCallbackRegistered && isAutoPlay) {
            try {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder().build(), networkCallback
                )
                networkCallbackRegistered = true
                Log.d(TAG, "网络回调已注册")
            } catch (e: Exception) {
                Log.e(TAG, "注册网络回调失败", e)
            }
        }
    }

    fun stopAutoPlay() {
        readingPrefs.edit().putBoolean("is_auto_play", false).apply()
        wasPlayingBeforeFocusLoss = false
        isAutoPlay = false
        autoPlayPausedDueToNetwork = false
        networkRecoveryJob?.cancel()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        pause()
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
    }

    fun isAutoPlaying(): Boolean = isAutoPlay
    fun getFileName(): String = fileName
    fun getFileUrl(): String = fileUrl
    fun getFilePath(): String = filePath

    fun pause() {
        tts?.stop()
        isSpeaking = false
        abandonAudioFocus()
        releaseWakeLocks()
        updateNotification("已暂停")
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
    }

    fun stop() {
        readingPrefs.edit().putBoolean("is_auto_play", false).apply()
        tts?.stop()
        isSpeaking = false
        isAutoPlay = false
        autoPlayPausedDueToNetwork = false
        networkRecoveryJob?.cancel()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        abandonAudioFocus()
        releaseWakeLocks()
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED)
        stopForegroundAndRemoveNotification()   // ← 改这里，移除通知
        stopSelf()
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
        readingPrefs.edit().putFloat("tts_speed", speechRate).apply()
    }

    fun isPlaying(): Boolean = isSpeaking
    fun isReady(): Boolean = isTtsReady

    fun reinitializeTts(enginePackage: String?) {
        tts?.stop()
        tts?.shutdown()
        isTtsReady = false
        tts = if (enginePackage.isNullOrEmpty()) TextToSpeech(this, this)
        else TextToSpeech(this, this, enginePackage)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            var res = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_MISSING_DATA
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_MISSING_DATA
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS不支持中文")
                    return
                }
            }
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    updateNotification(currentSpokenContent)
                    updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isAutoPlay) {
                        serviceScope.launch { autoNextPageWithRetry() }
                    } else {
                        releaseWakeLocks()
                        updateNotification(currentSpokenContent)
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    releaseWakeLocks()
                    updateNotification(currentSpokenContent)
                    updateMediaSessionState(PlaybackStateCompat.STATE_ERROR)
                    if (isAutoPlay) {
                        serviceScope.launch {
                            delay(500)
                            autoNextPageWithRetry()
                        }
                    }
                }
            })
            isTtsReady = true
            updateNotification("已准备就绪")
        } else {
            Log.e(TAG, "TTS初始化失败")
        }
    }

    private fun updateMediaSessionState(state: Int) {
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .build())
    }

    private suspend fun autoNextPageWithRetry(): Unit = nextPageMutex.withLock {
        if (!isAutoPlay) return@withLock

        var attempts = 0
        var delayTime = RETRY_BASE_DELAY
        while (attempts < MAX_AUTO_RETRIES) {
            try {
                val moved = repository.nextPage()
                if (!moved) {
                    Log.d(TAG, "已到达最后一页，停止自动播放")
                    updateNotification("已读完")
                    updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                    stopAutoPlay()
                    return@withLock
                }
                val content = repository.getCurrentPageContent()
                val state = repository.getCurrentPageState()
                if (content.isNotEmpty() && state != null) {
                    speakContent(content)
                    return@withLock
                } else {
                    throw Exception("空内容")
                }
            } catch (e: Exception) {
                attempts++
                if (attempts >= MAX_AUTO_RETRIES) {
                    Log.e(TAG, "自动翻页失败 $MAX_AUTO_RETRIES 次，暂停等待网络恢复")
                    updateNotification("网络中断，暂停朗读（等待网络恢复）")
                    updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                    tts?.stop()
                    isSpeaking = false
                    releaseWakeLocks()
                    autoPlayPausedDueToNetwork = true
                    return@withLock
                }
                Log.w(TAG, "自动翻页失败，${delayTime}ms后重试 (${attempts}/${MAX_AUTO_RETRIES})", e)
                updateNotification("网络中断，重试中…")
                updateMediaSessionState(PlaybackStateCompat.STATE_BUFFERING)
                delay(delayTime)
                delayTime = (delayTime * 2).coerceAtMost(RETRY_MAX_DELAY)
            }
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            wasPlayingBeforeFocusLoss = false
                            stopAutoPlay()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            if (isSpeaking) {
                                tts?.stop()
                                isSpeaking = false
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (wasPlayingBeforeFocusLoss) {
                                wasPlayingBeforeFocusLoss = false
                                if (isAutoPlay && !isSpeaking) {
                                    if (!autoPlayPausedDueToNetwork) {
                                        startPlaybackIfReady()
                                    } else {
                                        Log.d(TAG, "焦点恢复但网络未恢复，忽略")
                                    }
                                }
                            }
                        }
                    }
                }
                .build()
            hasAudioFocus = audioManager?.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            hasAudioFocus = audioManager?.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            wasPlayingBeforeFocusLoss = false
                            stopAutoPlay()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            if (isSpeaking) {
                                tts?.stop()
                                isSpeaking = false
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (wasPlayingBeforeFocusLoss) {
                                wasPlayingBeforeFocusLoss = false
                                if (isAutoPlay && !isSpeaking) {
                                    if (!autoPlayPausedDueToNetwork) {
                                        startPlaybackIfReady()
                                    } else {
                                        Log.d(TAG, "焦点恢复但网络未恢复，忽略")
                                    }
                                }
                            }
                        }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    private fun startForegroundIfNeeded() {
        // 重置去重状态，确保首次通知一定能发出。
        lastNotificationTitle = null
        lastNotificationContent = null
        val notification = buildNotification("正在听书", "加载中...", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 前台服务通知必须用 startForeground 持续刷新；相同标题+内容时不重发，
     * 避免 onDone 路径上无谓开销，同时保持前台服务状态不被降级。
     */
    private fun updateNotification(content: String) {
        val title = when {
            !isAutoPlay -> "听书暂停"
            autoPlayPausedDueToNetwork -> "等待网络恢复"
            isSpeaking -> "正在听书"
            else -> "等待恢复"
        }
        val displayContent = if (content.isNotBlank()) content.take(50) else " "
        if (title == lastNotificationTitle && displayContent == lastNotificationContent) return
        lastNotificationTitle = title
        lastNotificationContent = displayContent

        val notification = buildNotification(title, displayContent, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, content: String, ongoing: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(getOpenPendingIntent())
            .setDeleteIntent(getStopPendingIntent())
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1))

        if (isAutoPlay) {
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", getActionPendingIntent(ACTION_PAUSE))
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "播放", getActionPendingIntent(ACTION_PLAY))
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", getActionPendingIntent(ACTION_STOP_SERVICE))

        return builder.build()
    }

    private fun getOpenPendingIntent(): PendingIntent {
        val openIntent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FILE_NAME", fileName)
            putExtra("FILE_URL", fileUrl)
            putExtra("FILE_PATH", filePath)
        }
        return PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getStopPendingIntent(): PendingIntent = getActionPendingIntent(ACTION_STOP_SERVICE)

    private fun getActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudiobookService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * 停止服务时彻底移除前台通知。
     * 用 STOP_FOREGROUND_REMOVE（而不是 DETACH），并在退到 < N 时用 stopForeground(true)；
     * 再显式 cancel 一次以兼容个别 ROM 上 DETACH 残留的情况。
     */
    private fun stopForegroundAndRemoveNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "朗读文本时的控制器"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun speakContent(content: String) {
        currentSpokenContent = content
        currentSpeakingOffset = repository.getCurrentPageState()?.absoluteCharOffset ?: -1
        isSpeaking = true
        requestAudioFocus()
        acquireWakeLocks()
        val utteranceId = "page_${System.currentTimeMillis()}"
        tts?.speak(content, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        updateNotification(content)
    }

    /**
     * 使用无超时 acquire，自动播放期间一直持有，直到 pause/stop/onDestroy 显式释放。
     * 不要用 acquire(timeout) + delay 续期：Doze 下主线程延时消息会被冻结，
     * 续期任务不会执行，wakeLock 超时后被系统释放，CPU 随即休眠，TTS 合成在分页处被挂起。
     */
    private fun acquireWakeLocks() {
        wakeLock?.let {
            if (!it.isHeld) it.acquire()
        }
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}