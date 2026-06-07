package com.dkc.fileserverclient

class LocalTtsEngine(private val service: AudiobookService) : VoiceEngine {
    private var callback: VoiceCallback? = null

    init {
        service.onTtsReadyListener = {
            // 就绪后由 Activity 触发播放
        }
        service.callback = object : AudiobookService.Callback {
            override fun onPlaybackStart() {
                callback?.onStart(service.getCurrentUtteranceId())
            }
            override fun onPlaybackComplete(utteranceId: String) {
                callback?.onComplete(utteranceId)
            }
            override fun onPlaybackError(error: String?) {
                callback?.onError("", error ?: "本地TTS错误")
            }
            override fun onPlaybackPause() {}
            override fun onPlaybackStop() {}
        }
    }

    override fun play(text: String, utteranceId: String, callback: VoiceCallback) {
        this.callback = callback
        service.play(text, utteranceId, flush = true)
    }

    override fun appendPlay(text: String, utteranceId: String) {
        service.appendPlay(text, utteranceId)
    }

    override fun pause() = service.pause()
    override fun stop() = service.stop()
    override fun isPlaying() = service.isPlaying()
    override fun release() { /* 服务由Activity管理 */ }
}