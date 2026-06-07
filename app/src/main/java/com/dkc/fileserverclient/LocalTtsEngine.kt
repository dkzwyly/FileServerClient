package com.dkc.fileserverclient

class LocalTtsEngine(private val service: AudiobookService) : VoiceEngine {
    private var callback: VoiceCallback? = null

    init {
        // 设置 TTS 就绪监听，以便在就绪后自动触发播放
        service.onTtsReadyListener = {
            // 如果有待播放任务（由外部管理），这里不做具体播放，仅通知
        }
        // 绑定 service 回调，将事件转发给 VoiceCallback
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
        service.play(text, utteranceId)
    }

    override fun pause() = service.pause()
    override fun stop() = service.stop()
    override fun isPlaying() = service.isPlaying()
    override fun release() { /* 服务由 Activity 管理 */ }
}