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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextPaint
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.*
import java.util.Locale

class AudiobookService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "audiobook_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "AudiobookService"
        const val PREF_TTS_ENGINE = "tts_engine_package"
        const val PREF_READING_PARAMS = "reading_params"
        const val ACTION_STOP_SERVICE = "com.dkc.fileserverclient.action.STOP_SERVICE"
    }

    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private var currentUtteranceId = ""
    private var speechRate = 1.0f

    // 音频焦点
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // 分页管理器
    private lateinit var paginationManager: TextPaginationManager
    private var httpClient: OkHttpClient? = null

    // 自动播放状态
    private var isAutoPlay = false
    private var pendingAutoPlay = false

    // 文件信息
    private var fileName = ""
    private var fileUrl = ""
    private var filePath = ""

    // 显示参数
    private var textWidth = 0
    private lateinit var textPaint: TextPaint
    private var lineSpacingExtra = 0f
    private var lineSpacingMultiplier = 1f
    private var linesPerPage = 20

    private lateinit var prefs: SharedPreferences
    private var readingHistoryFile: File? = null
    private var pendingHistory: Pair<Int, Int>? = null

    var paginationCallback: TextPaginationManager.Callback? = null

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): AudiobookService = this@AudiobookService
        fun getPaginationManager(): TextPaginationManager = paginationManager
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences(PREF_READING_PARAMS, MODE_PRIVATE)

        textWidth = prefs.getInt("text_width", 600)
        val savedFontSize = prefs.getFloat("font_size", 40f)
        textPaint = TextPaint().apply { textSize = savedFontSize }
        lineSpacingExtra = prefs.getFloat("line_spacing_extra", 0f)
        lineSpacingMultiplier = prefs.getFloat("line_spacing_multiplier", 1f)
        linesPerPage = prefs.getInt("lines_per_page", 20)

        try {
            httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()
        } catch (e: Exception) {
            httpClient = OkHttpClient()
        }

        paginationManager = TextPaginationManager(httpClient!!)
        paginationManager.setCallback(internalPaginationCallback)

        val enginePackage = getSharedPreferences("tts_prefs", MODE_PRIVATE).getString(PREF_TTS_ENGINE, null)
        tts = if (enginePackage.isNullOrEmpty()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, enginePackage)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand, action=${intent?.action}")
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "收到停止通知，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }
        // 不再处理文件信息，统一由 setupFile 负责
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        saveReadingProgress()
        stopSelfForeground()
        tts?.stop()
        tts?.shutdown()
        tts = null
        paginationManager.release()
        super.onDestroy()
    }

    /** 外部调用，传递文件信息并触发初始化 */
    fun setupFile(fileName: String, fileUrl: String, filePath: String) {
        Log.d(TAG, "setupFile: $fileName, url=$fileUrl")
        if (fileName != this.fileName || !paginationManager.isInitialized) {
            resetForNewFile(fileName, fileUrl, filePath)
        }
    }

    fun updateDisplayParams(width: Int, paint: Paint, extra: Float, multiplier: Float, linesPerPage: Int) {
        textWidth = width
        textPaint = TextPaint(paint)
        lineSpacingExtra = extra
        lineSpacingMultiplier = multiplier
        this.linesPerPage = linesPerPage.coerceAtLeast(2)
        prefs.edit()
            .putInt("text_width", textWidth)
            .putFloat("font_size", textPaint.textSize)
            .putFloat("line_spacing_extra", lineSpacingExtra)
            .putFloat("line_spacing_multiplier", lineSpacingMultiplier)
            .putInt("lines_per_page", this.linesPerPage)
            .apply()
        if (paginationManager.isInitialized) {
            paginationManager.updateDisplayParams(width, paint, extra, multiplier, this.linesPerPage)
        } else {
            initPaginationIfNeeded()
        }
    }

    fun nextPage() = paginationManager.nextPage()
    fun previousPage() = paginationManager.previousPage()
    fun loadChapters() = paginationManager.loadChapters()
    fun jumpToChapter(chapter: TextPaginationManager.ChapterInfo) = paginationManager.jumpToChapter(chapter)
    fun getCurrentPageState(): TextPaginationManager.PageState? = paginationManager.getCurrentPageState()
    fun getCurrentPageContent(): String = paginationManager.getCurrentPageContent()

    fun startAutoPlay() {
        isAutoPlay = true
        pendingAutoPlay = false
        if (!isSpeaking) playCurrentPage()
    }
    fun stopAutoPlay() {
        isAutoPlay = false
        pause()
    }
    fun isAutoPlaying(): Boolean = isAutoPlay

    fun pause() {
        tts?.stop()
        isSpeaking = false
        abandonAudioFocus()
        stopForegroundAndKeepService()
        updateNotification("已暂停")
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
    }
    fun isPlaying(): Boolean = isSpeaking
    fun isReady(): Boolean = isTtsReady

    fun reinitializeTts(enginePackage: String?) {
        tts?.stop()
        tts?.shutdown()
        isTtsReady = false
        tts = if (enginePackage.isNullOrEmpty()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, enginePackage)
        }
    }

    // ── 内部 ──
    private fun resetForNewFile(newFileName: String, newFileUrl: String, newFilePath: String) {
        Log.d(TAG, "重置分页管理器，加载新文件: $newFileName")
        fileName = newFileName
        fileUrl = newFileUrl
        filePath = newFilePath
        val historyDir = File(filesDir, "reading_history")
        if (!historyDir.exists()) historyDir.mkdirs()
        val safeName = fileName.replace("[^a-zA-Z0-9]".toRegex(), "_")
        readingHistoryFile = File(historyDir, "history_${safeName}.dat")
        paginationManager.resetForNewFile()
        initPaginationIfNeeded()
    }

    private fun initPaginationIfNeeded() {
        Log.d(TAG, "initPaginationIfNeeded: initialized=${paginationManager.isInitialized}, fileName='$fileName', width=$textWidth")
        if (!paginationManager.isInitialized && fileName.isNotEmpty() && textWidth > 0) {
            val history = readReadingHistory()
            if (history != null && history.fileName == fileName) {
                pendingHistory = Pair(history.blockPage, history.absoluteCharOffset)
            } else {
                pendingHistory = null
            }
            paginationManager.init(fileName, fileUrl, filePath, textWidth, textPaint, lineSpacingExtra, lineSpacingMultiplier, linesPerPage)
        }
    }

    private fun playCurrentPage() {
        val content = getCurrentPageContent()
        if (content.isBlank()) return
        if (!isTtsReady) {
            pendingAutoPlay = true
            return
        }
        requestAudioFocus()
        startForegroundIfNeeded()
        val state = getCurrentPageState()
        val utteranceId = "page_${state?.blockPage}_${state?.subPage}_${System.currentTimeMillis()}"
        tts?.speak(content, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private val internalPaginationCallback = object : TextPaginationManager.Callback {
        override fun onPageContentChanged(content: CharSequence, state: TextPaginationManager.PageState) {
            if (pendingHistory != null) {
                val (block, offset) = pendingHistory!!
                pendingHistory = null
                paginationManager.restorePosition(block, offset)
                return
            }
            updateNotification(content.toString())
            saveReadingProgress(state)
            paginationCallback?.onPageContentChanged(content, state)
            if (isAutoPlay && !isSpeaking) playCurrentPage()
        }
        override fun onError(message: String) { paginationCallback?.onError(message) }
        override fun onLoading(loading: Boolean, message: String?) { paginationCallback?.onLoading(loading, message) }
        override fun onChaptersReady(chapters: List<TextPaginationManager.ChapterInfo>) {
            paginationCallback?.onChaptersReady(chapters)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            var res = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_MISSING_DATA
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_MISSING_DATA
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    paginationCallback?.onError("TTS不支持中文")
                    return
                }
            }
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    currentUtteranceId = utteranceId ?: ""
                    isSpeaking = true
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    if (isAutoPlay) nextPage()
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    paginationCallback?.onError("朗读出错")
                }
            })
            isTtsReady = true
            if (pendingAutoPlay) {
                pendingAutoPlay = false
                startAutoPlay()
            }
        } else {
            paginationCallback?.onError("TTS初始化失败")
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
                .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) pause() }
                .build()
            hasAudioFocus = audioManager?.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            hasAudioFocus = audioManager?.requestAudioFocus(
                { if (it == AudioManager.AUDIOFOCUS_LOSS) pause() },
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

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val stopIntent = Intent(this, AudiobookService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            val stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val openIntent = Intent(this, TextPreviewActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("正在听书")
                .setContentText("加载中...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPendingIntent)
                .setDeleteIntent(stopPendingIntent)   // 滑动删除时停止服务
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val stopIntent = Intent(this, AudiobookService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isSpeaking) "正在听书" else "听书暂停")
            .setContentText(content.take(50))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundAndKeepService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopSelfForeground() {
        stopForegroundAndKeepService()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "听书播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "朗读文本时的控制器"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun saveReadingProgress(state: TextPaginationManager.PageState? = null) {
        val s = state ?: paginationManager.getCurrentPageState() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                readingHistoryFile?.let {
                    ObjectOutputStream(FileOutputStream(it)).use { out ->
                        out.writeObject(ReadingHistory(fileName, fileUrl, s.blockPage, s.absoluteCharOffset, System.currentTimeMillis()))
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "保存进度失败", e) }
        }
    }

    private fun readReadingHistory(): ReadingHistory? {
        return try {
            readingHistoryFile?.takeIf { it.exists() }?.let {
                ObjectInputStream(FileInputStream(it)).use { it.readObject() as? ReadingHistory }
            }
        } catch (e: Exception) { null }
    }
}

data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val blockPage: Int,
    val absoluteCharOffset: Int,
    val timestamp: Long
) : Serializable