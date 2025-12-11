package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import java.util.*

/**
 * 适配器类，用于将现有的 VideoPlayerManager 接口适配到 AudioPlayerManager
 * 这样可以逐步迁移，不破坏现有功能
 */
@UnstableApi
class AudioPlayerAdapter(
    context: Context,
    client: okhttp3.OkHttpClient,
    private val playPauseButton: ImageButton,
    private val seekBar: SeekBar,
    private val currentTimeTextView: TextView,
    private val durationTextView: TextView,
    private val speedIndicator: TextView,
    handler: Handler
) : AudioPlaybackListener, AudioProgressListener {

    private val audioPlayerManager: AudioPlayerManager
    private val contextRef: Context = context
    private val handler: Handler = handler

    // 用于存储外部注册的监听器
    private val playbackListeners = mutableListOf<AudioPlaybackListener>()
    private val progressListeners = mutableListOf<AudioProgressListener>()

    init {
        // 初始化音频播放管理器
        AudioPlayerManagerFactory.initialize(context, client)
        audioPlayerManager = AudioPlayerManagerFactory.getInstance()

        // 将自己注册为内部监听器
        audioPlayerManager.addPlaybackListener(this)
        audioPlayerManager.addProgressListener(this)

        // 设置UI监听器
        setupUIListeners()
    }

    private fun setupUIListeners() {
        playPauseButton.setOnClickListener {
            audioPlayerManager.togglePlayback()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = audioPlayerManager.getDuration()
                    if (duration > 0) {
                        val newPosition = (duration * progress / 1000).toLong()
                        audioPlayerManager.seekTo(newPosition)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // 暂时不需要处理
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 暂时不需要处理
            }
        })
    }

    // ==================== 公共方法 ====================

    fun addPlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.add(listener)
    }

    fun removePlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.remove(listener)
    }

    fun addProgressListener(listener: AudioProgressListener) {
        progressListeners.add(listener)
    }

    fun removeProgressListener(listener: AudioProgressListener) {
        progressListeners.remove(listener)
    }

    fun loadAudio(audioUrl: String, audioTrack: AudioTrack? = null) {
        if (audioTrack != null) {
            audioPlayerManager.play(audioTrack)
        } else {
            audioPlayerManager.play(audioUrl)
        }
    }

    fun togglePlayback() {
        audioPlayerManager.togglePlayback()
    }

    fun seekTo(position: Long) {
        audioPlayerManager.seekTo(position)
    }

    fun getCurrentPosition(): Long {
        return audioPlayerManager.getCurrentPosition()
    }

    fun getDuration(): Long {
        return audioPlayerManager.getDuration()
    }

    fun isPlaying(): Boolean {
        return audioPlayerManager.isPlaying()
    }

    fun pause() {
        audioPlayerManager.pause()
    }

    fun resume() {
        audioPlayerManager.resume()
    }

    fun stop() {
        audioPlayerManager.stop()
    }

    fun release() {
        audioPlayerManager.removePlaybackListener(this)
        audioPlayerManager.removeProgressListener(this)
        playbackListeners.clear()
        progressListeners.clear()
    }

    fun setPlaylist(tracks: List<AudioTrack>, currentIndex: Int = 0) {
        audioPlayerManager.setPlaylist(tracks, currentIndex)
    }

    fun playNext() {
        audioPlayerManager.playNext()
    }

    fun playPrevious() {
        audioPlayerManager.playPrevious()
    }

    fun getCurrentTrack(): AudioTrack? {
        return audioPlayerManager.getCurrentTrack()
    }

    fun getCurrentIndex(): Int {
        return audioPlayerManager.getCurrentIndex()
    }

    fun getPlaylist(): List<AudioTrack> {
        return audioPlayerManager.getPlaylist()
    }

    // ==================== 监听器实现（内部）====================

    override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
        // 更新播放/暂停按钮
        updatePlayPauseButton(status.isPlaying)

        // 更新速度指示器
        if (status.playbackSpeed != 1.0f) {
            speedIndicator.visibility = TextView.VISIBLE
            speedIndicator.text = "${status.playbackSpeed}x"
        } else {
            speedIndicator.visibility = TextView.GONE
        }

        // 更新时长显示
        if (status.duration > 0) {
            durationTextView.text = formatTime(status.duration)
        }

        // 转发给外部监听器
        playbackListeners.forEach { it.onPlaybackStateChanged(status) }
    }

    // 在 AudioPlayerAdapter 类中：
    override fun onTrackChanged(track: AudioTrack, index: Int) {
        // 轨道变化时的处理
        // 可以在这里更新UI显示当前播放的歌曲信息

        // 重要：在主线程中转发给外部监听器
        handler.post {
            playbackListeners.forEach { listener ->
                listener.onTrackChanged(track, index)
            }
        }
    }

    override fun onPlaybackError(error: String) {
        // 转发给外部监听器
        playbackListeners.forEach { it.onPlaybackError(error) }
    }

    override fun onPlaybackEnded() {
        // 转发给外部监听器
        playbackListeners.forEach { it.onPlaybackEnded() }
    }

    override fun onAudioBuffering(isBuffering: Boolean) {  // 修复：方法名改为 onAudioBuffering
        // 转发给外部监听器
        playbackListeners.forEach { it.onAudioBuffering(isBuffering) }
    }

    override fun onProgressUpdated(position: Long, duration: Long) {
        // 更新进度条
        if (duration > 0) {
            val progress = (position * 1000 / duration).toInt()
            handler.post {
                seekBar.progress = progress
                currentTimeTextView.text = formatTime(position)
            }
        }

        // 转发给外部监听器
        progressListeners.forEach { it.onProgressUpdated(position, duration) }
    }

    override fun onBufferingProgress(percent: Int) {
        // 转发给外部监听器
        progressListeners.forEach { it.onBufferingProgress(percent) }
    }

    // ==================== 私有辅助方法 ====================

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        playPauseButton.setImageResource(iconRes)
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60)
        }
    }
}