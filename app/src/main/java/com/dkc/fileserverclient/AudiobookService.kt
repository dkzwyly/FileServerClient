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
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    // 新增：标记网络回调是否已注册
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isAutoPlay && !isSpeaking) {
                serviceScope.launch { autoNextPage() }
            }
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

        val ttsPrefs = getSharedPreferences("tts_prefs", MODE_PRIVATE)
        val enginePackage = ttsPrefs.getString(PREF_TTS_ENGINE, null)
        tts = if (enginePackage.isNullOrEmpty()) TextToSpeech(this, this)
        else TextToSpeech(this, this, enginePackage)
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
        stopForegroundAndKeepService()
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWakeLock()
        // 注销网络回调（如果已注册）
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── 公开方法 ──
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
        wasPlayingBeforeFocusLoss = false
        isAutoPlay = true
        // 不再立刻注册网络回调，而是等朗读真正开始后再延迟注册

        val state = repository.getCurrentPageState()
        if (state != null) {
            lastSpokenOffset = state.absoluteCharOffset
            val content = repository.getCurrentPageContent()
            if (content.isNotEmpty()) {
                speakContent(content)
                // 延迟注册，确保 TTS 已经开始播放（避免同步触发翻页）
                Handler(Looper.getMainLooper()).post {
                    registerNetworkCallbackIfNeeded()
                }
            }
        } else {
            serviceScope.launch {
                repository.pageContentFlow.first { it != null }?.let {
                    lastSpokenOffset = it.state.absoluteCharOffset
                    speakContent(it.content.toString())
                    Handler(Looper.getMainLooper()).post {
                        registerNetworkCallbackIfNeeded()
                    }
                }
            }
        }
    }

    /**
     * 注册网络回调（仅当未注册且处于自动播放模式时）
     */
    private fun registerNetworkCallbackIfNeeded() {
        if (!networkCallbackRegistered && isAutoPlay) {
            try {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder().build(), networkCallback
                )
                networkCallbackRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "注册网络回调失败", e)
            }
        }
    }

    fun stopAutoPlay() {
        wasPlayingBeforeFocusLoss = false
        isAutoPlay = false
        // 注销网络回调并重置标志
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        pause()
    }

    fun isAutoPlaying(): Boolean = isAutoPlay
    fun getFileName(): String = fileName
    fun getFileUrl(): String = fileUrl
    fun getFilePath(): String = filePath

    fun pause() {
        tts?.stop()
        isSpeaking = false
        abandonAudioFocus()
        stopForegroundAndKeepService()
        releaseWakeLock()
        updateNotification("已暂停")
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
        isAutoPlay = false
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        } catch (_: Exception) {}
        abandonAudioFocus()
        stopForegroundAndKeepService()
        releaseWakeLock()
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

    // ── TTS 初始化回调 ──
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
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isAutoPlay) {
                        serviceScope.launch { autoNextPage() }
                    } else {
                        releaseWakeLock()
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    releaseWakeLock()
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
                val content = repository.getCurrentPageContent()
                if (content.isNotEmpty()) {
                    val state = repository.getCurrentPageState()
                    if (state != null) lastSpokenOffset = state.absoluteCharOffset
                    speakContent(content)
                }
            }
        } else {
            Log.e(TAG, "TTS初始化失败")
        }
    }

    // ── 翻页逻辑（显式返回类型，互斥保护） ──
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
            updateNotification("网络临时中断，重试中…")
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_DELAY)
            if (isAutoPlay) {
                autoNextPage()
            }
        }
    }

    // ── 音频焦点 ──
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

    // ── 前台通知 ──
    private fun startForegroundIfNeeded() {
        val stopIntent = Intent(this, AudiobookService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FILE_NAME", fileName)
            putExtra("FILE_URL", fileUrl)
            putExtra("FILE_PATH", filePath)
        }
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在听书")
            .setContentText("加载中...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val stopIntent = Intent(this, AudiobookService::class.java).apply { action = ACTION_STOP_SERVICE }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FILE_NAME", fileName)
            putExtra("FILE_URL", fileUrl)
            putExtra("FILE_PATH", filePath)
        }
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (isAutoPlay) {
                    if (isSpeaking) "正在听书" else "等待恢复"
                } else {
                    "听书暂停"
                }
            )
            .setContentText(content.take(50))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setOngoing(isAutoPlay)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundAndKeepService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW).apply {
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