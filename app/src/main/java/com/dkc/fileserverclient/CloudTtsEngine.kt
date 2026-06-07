package com.dkc.fileserverclient

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.*

class CloudTtsEngine(private val context: Context) : VoiceEngine {
    private var mediaPlayer: MediaPlayer? = null
    private var callback: VoiceCallback? = null
    private var isPlayingFlag = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun play(text: String, utteranceId: String, callback: VoiceCallback) {
        this.callback = callback
        callback.onStart(utteranceId)
        // 模拟云端合成延迟，然后完成（实际应用替换为真实请求）
        scope.launch {
            delay(1500) // 模拟合成时间
            withContext(Dispatchers.Main) {
                callback.onComplete(utteranceId)
            }
        }
    }

    override fun pause() {
        mediaPlayer?.pause()
        isPlayingFlag = false
    }

    override fun stop() {
        mediaPlayer?.stop()
        isPlayingFlag = false
    }

    override fun isPlaying(): Boolean = isPlayingFlag

    override fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
        scope.cancel()
    }
}