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
        const val DUCK_VOLUME = 0.2f
        const val FULL_VOLUME = 1.0f

        private const val RETRY_BASE_DELAY = 2000L
        private const val RETRY_MAX_DELAY = 30000L
        private var retryDelay = RETRY_BASE_DELAY
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

    private var fileName = ""
    private var fileUrl = ""
    private var filePath = ""

    private var isFileSetupDone = false

    private lateinit var readingPrefs: SharedPreferences

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pageFlowJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSpokenOffset: Int = -1

    private lateinit var connectivityManager: ConnectivityManager

    private var networkCallbackRegistered = false

    // MediaSession 相关
    private lateinit var mediaSession: MediaSessionCompat

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "网络可用，绑定到 $network")
            repository.setNetwork(network)
            if (isAutoPlay && !isSpeaking) {
                serviceScope.launch { autoNextPage() }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "网络丢失 $network，解绑")
            repository.setNetwork(null)
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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        repository = PageRepository.getInstance(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork?.let {
                repository.setNetwork(it)
            }
        }

        // 初始化 MediaSession
        mediaSession = MediaSessionCompat(this, "AudiobookService").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP)
                .build())
            isActive = true
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudiobookService::WakeLock")

        readingPrefs = getSharedPreferences("reading_prefs", MODE_PRIVATE)
        speechRate = readingPrefs.getFloat("tts_speed", 1.0f)

        pageFlowJob = serviceScope.launch {
            repository.pageContentFlow.collect { uiData ->
                uiData?.let {
                    updateNotification(it.content.toString())
                    lastSpokenOffset = it.state.absoluteCharOffset
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

        // 恢复自动播放状态
        isAutoPlay = readingPrefs.getBoolean("is_auto_play", false)
        if (isAutoPlay) {
            if (isTtsReady) {
                startPlaybackIfReady()
            }
        }
    }

    private fun requestBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) { }
            }
        }
    }

    private fun startPlaybackIfReady() {
        val content = repository.getCurrentPageContent()
        if (content.isNotEmpty()) {
            val state = repository.getCurrentPageState()
            if (state != null) lastSpokenOffset = state.absoluteCharOffset
            speakContent(content)
            Handler(Looper.getMainLooper()).post {
                registerNetworkCallbackIfNeeded()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        stopForegroundAndKeepService()
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWakeLock()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        repository.setNetwork(null)
        super.onDestroy()
    }

    fun setupFile(fileName: String, fileUrl: String, filePath: String) {
        if (isFileSetupDone && this.fileName == fileName) return
        this.fileName = fileName
        this.fileUrl = fileUrl
        this.filePath = filePath
        repository.setupFile(fileName, fileUrl, filePath)
        isFileSetupDone = true
    }

    fun updateDisplayParams(width: Int, paint: Paint, extra: Float, multiplier: Float, lines: Int) {
        repository.updateDisplayParams(width, paint, extra, multiplier, lines)
    }

    fun startAutoPlay() {
        readingPrefs.edit().putBoolean("is_auto_play", true).apply()
        wasPlayingBeforeFocusLoss = false
        isAutoPlay = true
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)

        val state = repository.getCurrentPageState()
        if (state != null) {
            lastSpokenOffset = state.absoluteCharOffset
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
                    lastSpokenOffset = timeout.state.absoluteCharOffset
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
        releaseWakeLock()
        updateNotification("已暂停")
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
    }

    fun stop() {
        readingPrefs.edit().putBoolean("is_auto_play", false).apply()
        tts?.stop()
        isSpeaking = false
        isAutoPlay = false
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        abandonAudioFocus()
        releaseWakeLock()
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED)
        stopForegroundAndKeepService()
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
                    updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isAutoPlay) {
                        serviceScope.launch { autoNextPage() }
                    } else {
                        releaseWakeLock()
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    releaseWakeLock()
                    updateMediaSessionState(PlaybackStateCompat.STATE_ERROR)
                    if (isAutoPlay) {
                        serviceScope.launch {
                            delay(500)
                            autoNextPage()
                        }
                    }
                }
            })
            isTtsReady = true
            if (isAutoPlay && !isSpeaking) {
                startPlaybackIfReady()
            }
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

    private suspend fun autoNextPage(): Unit = nextPageMutex.withLock {
        if (!isAutoPlay) return@withLock
        try {
            repository.nextPage()
            val content = repository.getCurrentPageContent()
            val state = repository.getCurrentPageState()
            if (content.isNotEmpty() && state != null) {
                lastSpokenOffset = state.absoluteCharOffset
                speakContent(content)
                retryDelay = RETRY_BASE_DELAY
            } else {
                isAutoPlay = false
                stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "自动翻页失败，${retryDelay}ms后重试", e)
            updateNotification("网络中断，重试中…")
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_DELAY)
            if (isAutoPlay) {
                autoNextPage()
            }
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            stopAutoPlay()
                            abandonAudioFocus()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            adjustTtsVolume(DUCK_VOLUME)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            adjustTtsVolume(FULL_VOLUME)
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
                            stopAutoPlay()
                            abandonAudioFocus()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            adjustTtsVolume(DUCK_VOLUME)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            adjustTtsVolume(FULL_VOLUME)
                        }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun adjustTtsVolume(volume: Float) {
        audioManager?.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 * volume).toInt(),
            0
        )
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
        val notification = buildNotification("正在听书", "加载中...", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val title = when {
            !isAutoPlay -> "听书暂停"
            isSpeaking -> "正在听书"
            else -> "等待恢复"
        }
        val notification = buildNotification(title, content.take(50).toString(), isAutoPlay)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(getOpenPendingIntent())
            .setDeleteIntent(getStopPendingIntent())
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0))
            .build()

    private fun getOpenPendingIntent(): PendingIntent {
        val openIntent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FILE_NAME", fileName)
            putExtra("FILE_URL", fileUrl)
            putExtra("FILE_PATH", filePath)
        }
        return PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getStopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, AudiobookService::class.java).apply { action = ACTION_STOP_SERVICE }
        return PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopForegroundAndKeepService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(true)
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
        requestAudioFocus()
        acquireWakeLock()
        if (isAutoPlay) {
            startForegroundIfNeeded()
        }
        val utteranceId = "page_${System.currentTimeMillis()}"
        tts?.speak(content, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            it.acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}