package com.dkc.fileserverclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Locale

class AudiobookService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "audiobook_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "AudiobookService"
        const val PREF_TTS_ENGINE = "tts_engine_package"
        const val ACTION_STOP_SERVICE = "com.dkc.fileserverclient.action.STOP_SERVICE"
    }

    private lateinit var repository: PageRepository

    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private var speechRate = 1.0f

    // 音频焦点
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // 自动播放状态
    private var isAutoPlay = false
    private var wasPlayingBeforeFocusLoss = false

    // 文件信息
    private var fileName = ""
    private var fileUrl = ""
    private var filePath = ""

    // 防止 Activity 重复初始化
    private var isFileSetupDone = false

    // SharedPreferences
    private lateinit var readingPrefs: SharedPreferences

    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pageFlowJob: Job? = null

    // WakeLock
    private var wakeLock: PowerManager.WakeLock? = null

    // 新增：记录上一次实际朗读的纯文本内容，用于去重
    private var lastSpokenContent: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudiobookService = this@AudiobookService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        repository = PageRepository.getInstance(this)

        // 获取 WakeLock，防止 CPU 休眠
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudiobookService::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L) // 10分钟超时，避免永久持有

        readingPrefs = getSharedPreferences("reading_prefs", MODE_PRIVATE)
        speechRate = readingPrefs.getFloat("tts_speed", 1.0f)

        // 监听页面内容，自动播放（新增去重逻辑）
        pageFlowJob = serviceScope.launch {
            repository.pageContentFlow.collect { uiData ->
                uiData?.let {
                    updateNotification(it.content.toString())
                    // 只有自动播放、TTS就绪、且内容与上次朗读不同时才触发朗读
                    if (isAutoPlay && isTtsReady && lastSpokenContent != it.content.toString()) {
                        speakContent(it.content)
                    }
                }
            }
        }

        // TTS 初始化
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
        stopSelfForeground()
        tts?.stop()
        tts?.shutdown()
        tts = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ── 公开方法 ──

    /** 初始化文件，如果已经初始化过则忽略，避免重复加载 */
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
        // 手动开始时强制清空已读缓存，确保立即朗读当前页
        lastSpokenContent = null
        if (!isSpeaking) {
            repository.pageContentFlow.value?.let { speakContent(it.content) }
        }
    }

    fun stopAutoPlay() {
        wasPlayingBeforeFocusLoss = false
        isAutoPlay = false
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
        updateNotification("已暂停")
        // 不清空 lastSpokenContent，保持去重状态
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
        isAutoPlay = false
        abandonAudioFocus()
        stopSelfForeground()
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
                override fun onStart(utteranceId: String?) { isSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isAutoPlay) {
                        serviceScope.launch {
                            try {
                                repository.nextPage()
                            } catch (e: Exception) {
                                Log.e(TAG, "自动翻页失败，重试一次", e)
                                delay(500)
                                try {
                                    repository.nextPage()
                                } catch (e2: Exception) {
                                    Log.e(TAG, "重试失败，停止自动播放")
                                    stopAutoPlay()
                                }
                            }
                        }
                    }
                }
                override fun onError(utteranceId: String?) { isSpeaking = false }
            })
            isTtsReady = true
            if (isAutoPlay && !isSpeaking) {
                repository.pageContentFlow.value?.let { speakContent(it.content) }
            }
        } else {
            Log.e(TAG, "TTS初始化失败")
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
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            pause()
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (wasPlayingBeforeFocusLoss) {
                                wasPlayingBeforeFocusLoss = false
                                startAutoPlay()
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
                            stopAutoPlay()
                            abandonAudioFocus()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            pause()
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            wasPlayingBeforeFocusLoss = isAutoPlay
                            pause()
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            if (wasPlayingBeforeFocusLoss) {
                                wasPlayingBeforeFocusLoss = false
                                startAutoPlay()
                            }
                        }
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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

    // ── 前台通知 ──
    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                .build()
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
            .setContentTitle(if (isSpeaking) "正在听书" else "听书暂停")
            .setContentText(content.take(50))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundAndKeepService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun stopSelfForeground() {
        stopForegroundAndKeepService()
        stopSelf()
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

    private fun speakContent(content: CharSequence) {
        requestAudioFocus()
        startForegroundIfNeeded()
        val utteranceId = "page_${System.currentTimeMillis()}"
        lastSpokenContent = content.toString()   // 记录实际朗读内容
        tts?.speak(content.toString(), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
}