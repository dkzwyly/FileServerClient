package com.dkc.fileserverclient

interface VoiceEngine {
    fun play(text: String, utteranceId: String, callback: VoiceCallback)
    fun appendPlay(text: String, utteranceId: String) {}  // 默认空实现，本地引擎需要重写
    fun pause()
    fun stop()
    fun isPlaying(): Boolean
    fun release()
}

interface VoiceCallback {
    fun onStart(utteranceId: String)
    fun onComplete(utteranceId: String)
    fun onError(utteranceId: String, error: String?)
}