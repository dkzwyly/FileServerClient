// AudioPlayerManager.kt
package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler

interface AudioSpectrumListener {
    fun onSpectrumData(spectrum: FloatArray)
}

interface AudioPlayerManager {

    fun initialize(context: Context, handler: Handler)
    fun release()

    fun play(track: AudioTrack)
    fun play(url: String, metadata: AudioTrack? = null)
    fun pause()
    fun resume()
    fun stop()
    fun togglePlayback()
    fun seekTo(position: Long)
    fun fastForward(milliseconds: Long = 10000)
    fun rewind(milliseconds: Long = 10000)

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0)
    fun playNext()
    fun playPrevious()
    fun playAtIndex(index: Int)
    fun getPlaylist(): List<AudioTrack>

    fun setPlaybackSpeed(speed: Float)
    fun setRepeatMode(mode: RepeatMode)
    fun setShuffleEnabled(enabled: Boolean)

    fun getPlaybackStatus(): AudioPlaybackStatus
    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun isPlaying(): Boolean
    fun getCurrentTrack(): AudioTrack?
    fun getCurrentIndex(): Int

    fun addPlaybackListener(listener: AudioPlaybackListener)
    fun removePlaybackListener(listener: AudioPlaybackListener)
    fun addProgressListener(listener: AudioProgressListener)
    fun removeProgressListener(listener: AudioProgressListener)

    // 新增频谱监听
    fun addSpectrumListener(listener: AudioSpectrumListener)
    fun removeSpectrumListener(listener: AudioSpectrumListener)
}

interface AudioPlaybackListener {
    fun onPlaybackStateChanged(status: AudioPlaybackStatus)
    fun onTrackChanged(track: AudioTrack, index: Int)
    fun onPlaybackError(error: String)
    fun onPlaybackEnded()
    fun onAudioBuffering(isBuffering: Boolean)
}

interface AudioProgressListener {
    fun onProgressUpdated(position: Long, duration: Long)
    fun onBufferingProgress(percent: Int)
}