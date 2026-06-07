package com.dkc.fileserverclient

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class AudiobookService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "audiobook_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "AudiobookService"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var isSpeaking = false
    private var currentUtteranceId = ""
    private var speechRate = 1.0f
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // 回调给 Activity 的接口
    var callback: Callback? = null

    // 新增：TTS 就绪回调
    var onTtsReadyListener: (() -> Unit)? = null

    interface Callback {
        fun onPlaybackStart()
        fun onPlaybackComplete(utteranceId: String)
        fun onPlaybackPause()
        fun onPlaybackStop()
        fun onPlaybackError(error: String?)
    }

    // Binder
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): AudiobookService = this@AudiobookService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopSelfForeground()
        tts?.stop()
        tts?.shutdown()
        tts = null
        callback = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE) ?: TextToSpeech.LANG_MISSING_DATA
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "中文不支持")
                callback?.onPlaybackError("当前系统不支持中文语音")
                return
            }
            tts?.setSpeechRate(speechRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "开始朗读: $utteranceId")
                    currentUtteranceId = utteranceId ?: ""
                    isSpeaking = true
                    callback?.onPlaybackStart()
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "朗读完成: $utteranceId")
                    isSpeaking = false
                    callback?.onPlaybackComplete(utteranceId ?: "")
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "朗读错误: $utteranceId")
                    isSpeaking = false
                    callback?.onPlaybackError("朗读出错")
                }
            })
            isTtsReady = true
            // 通知外部 TTS 已就绪
            onTtsReadyListener?.invoke()
        } else {
            Log.e(TAG, "TTS 初始化失败")
            callback?.onPlaybackError("TTS 初始化失败")
        }
    }

    // 公开就绪状态
    fun isReady(): Boolean = isTtsReady

    fun play(text: String, utteranceId: String = "page_${System.currentTimeMillis()}") {
        if (!isTtsReady) {
            callback?.onPlaybackError("语音引擎未就绪")
            return
        }
        if (text.isBlank()) {
            callback?.onPlaybackError("没有可朗读的内容")
            return
        }
        requestAudioFocus()
        startForegroundIfNeeded()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun pause() {
        tts?.stop()
        isSpeaking = false
        abandonAudioFocus()
        stopForegroundAndKeepService()
        callback?.onPlaybackPause()
    }

    fun stop() {
        tts?.stop()
        isSpeaking = false
        abandonAudioFocus()
        stopSelfForeground()
        callback?.onPlaybackStop()
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    fun isPlaying(): Boolean = isSpeaking

    fun getCurrentUtteranceId(): String = currentUtteranceId

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioFocusRequest = focusRequest
                hasAudioFocus = audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                hasAudioFocus = audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求音频焦点失败", e)
        }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放音频焦点失败", e)
        }
        hasAudioFocus = false
    }

    private fun startForegroundIfNeeded() {
        val intent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在听书")
            .setContentText("《未知》")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    fun updateNotification(title: String) {
        val intent = Intent(this, TextPreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在听书")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
}