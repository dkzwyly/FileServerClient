package com.dkc.fileserverclient

interface VoiceEngine {
    fun play(text: String, utteranceId: String, callback: VoiceCallback)
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