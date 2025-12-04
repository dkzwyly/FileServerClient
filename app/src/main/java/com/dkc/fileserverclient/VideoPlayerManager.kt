package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.util.Locale

@UnstableApi
class VideoPlayerManager(
    private val context: Context,
    private val client: okhttp3.OkHttpClient,
    private val playerView: PlayerView,
    private val videoLoadingProgress: ProgressBar,
    private val playPauseButton: ImageButton,
    private val seekBar: SeekBar,
    private val currentTimeTextView: TextView,
    private val durationTextView: TextView,
    private val speedIndicator: TextView,
    private val handler: Handler
) {
    private var exoPlayer: ExoPlayer? = null
    private var isPlayingState = false // 改为可变的变量
    private var isFastForwarding = false
    private var videoDuration: Long = 0

    private val updateProgressRunnable = Runnable { updateProgress() }

    // 状态监听器接口
    interface PlayerStateListener {
        fun onPlayerStateChanged(isPlaying: Boolean)
        fun onPlaybackEnded()
        fun onPlayerError(error: String)
        fun onBuffering(isBuffering: Boolean)
        fun onDurationUpdated(duration: Long)
        fun onPlayerReady()
    }

    private var playerStateListener: PlayerStateListener? = null

    fun setPlayerStateListener(listener: PlayerStateListener) {
        this.playerStateListener = listener
    }

    fun initializePlayer() {
        val dataSourceFactory = createUnsafeDataSourceFactory()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 1500, 2000)
            .setTargetBufferBytes(-1)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                videoLoadingProgress.visibility = View.GONE
                                isPlayingState = this@apply.isPlaying // 更新可变变量
                                playerStateListener?.onPlayerStateChanged(isPlayingState)
                                updateDuration()
                                startProgressUpdates()
                                playerStateListener?.onBuffering(false)
                                playerStateListener?.onPlayerReady()
                            }
                            Player.STATE_BUFFERING -> {
                                videoLoadingProgress.visibility = View.VISIBLE
                                playerStateListener?.onBuffering(true)
                            }
                            Player.STATE_ENDED -> {
                                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                                isPlayingState = false // 更新可变变量
                                playerStateListener?.onPlayerStateChanged(isPlayingState)
                                playerStateListener?.onPlaybackEnded()
                                stopProgressUpdates()
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        videoLoadingProgress.visibility = View.GONE
                        playerStateListener?.onPlayerError("播放错误: ${error.message}")
                        stopProgressUpdates()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@VideoPlayerManager.isPlayingState = isPlaying // 更新可变变量
                        playerStateListener?.onPlayerStateChanged(isPlayingState)
                    }
                })
            }

        playerView.player = exoPlayer
        playerView.useController = false
    }

    private fun createUnsafeDataSourceFactory(): DataSource.Factory {
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(client)
        return DefaultDataSource.Factory(context, okHttpDataSourceFactory)
    }

    fun loadVideo(videoUrl: String) {
        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(videoUrl)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            isPlayingState = true
            updatePlayPauseButton()

        } catch (e: Exception) {
            playerStateListener?.onPlayerError("视频加载失败: ${e.message}")
        }
    }

    fun loadAudio(audioUrl: String) {
        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(audioUrl)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            isPlayingState = true
            updatePlayPauseButton()

        } catch (e: Exception) {
            playerStateListener?.onPlayerError("音频加载失败: ${e.message}")
        }
    }

    fun togglePlayback() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            isPlayingState = player.isPlaying
            updatePlayPauseButton()
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0
    }

    fun isPlayerPlaying(): Boolean {
        return isPlayingState
    }

    fun enableFastForward(enable: Boolean) {
        exoPlayer?.let { player ->
            if (enable) {
                player.playbackParameters = player.playbackParameters.withSpeed(2.0f)
                isFastForwarding = true
                speedIndicator.visibility = View.VISIBLE
                speedIndicator.text = "2.0x"

                // 3秒后自动隐藏加速指示器
                handler.postDelayed({
                    speedIndicator.visibility = View.GONE
                }, 3000)
            } else {
                player.playbackParameters = player.playbackParameters.withSpeed(1.0f)
                isFastForwarding = false
                speedIndicator.visibility = View.GONE
            }
        }
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlayingState) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        playPauseButton.setImageResource(iconRes)
    }

    fun startProgressUpdates() {
        handler.post(updateProgressRunnable)
    }

    fun stopProgressUpdates() {
        handler.removeCallbacks(updateProgressRunnable)
    }

    private fun updateProgress() {
        exoPlayer?.let { player ->
            val duration = player.duration
            val position = player.currentPosition

            if (duration > 0) {
                if (duration != videoDuration) {
                    videoDuration = duration
                    updateDuration()
                }

                val progress = if (duration > 0) {
                    (position * 1000 / duration).toInt()
                } else {
                    0
                }

                seekBar.progress = progress
                currentTimeTextView.text = formatTime(position)
            }

            handler.postDelayed(updateProgressRunnable, 1000)
        }
    }

    private fun updateDuration() {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration > 0) {
                videoDuration = duration
                durationTextView.text = formatTime(duration)
                playerStateListener?.onDurationUpdated(duration)
            }
        }
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

    fun releasePlayer() {
        stopProgressUpdates()
        exoPlayer?.release()
        exoPlayer = null
    }

    fun pause() {
        exoPlayer?.pause()
    }
}