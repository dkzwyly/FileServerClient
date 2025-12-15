package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.util.Log
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
import java.util.*

@UnstableApi
class VideoPlaybackController(
    private val httpClient: okhttp3.OkHttpClient,
    private val playerView: PlayerView,
    private val videoLoadingProgress: ProgressBar,
    private val playPauseButton: ImageButton,
    private val seekBar: SeekBar,
    private val currentTimeTextView: TextView,
    private val durationTextView: TextView,
    private val uiHandler: Handler
) : MediaPlaybackController {

    // 内部状态
    private var context: Context? = null
    private var exoPlayer: ExoPlayer? = null
    private var currentItem: MediaPlaybackItem? = null
    private var playlist: List<MediaPlaybackItem> = emptyList()
    private var currentIndex: Int = -1
    private var isPlayingState = false
    private var isBuffering = false
    private var currentPosition: Long = 0L  // 这是 var，可以重新赋值
    private var currentDuration: Long = 0L  // 这是 var，可以重新赋值
    private var playbackSpeed: Float = 1.0f
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var shuffleEnabled: Boolean = false
    private var currentState = PlaybackState.IDLE

    // 监听器列表
    private val playbackListeners = mutableListOf<MediaPlaybackListener>()
    private val progressListeners = mutableListOf<MediaProgressListener>()

    // 进度更新任务
    private val updateProgressRunnable = Runnable { updateProgress() }

    // 初始化播放器
    override fun initialize(context: Context, handler: Handler) {
        this.context = context
        initializePlayer()

        // 设置播放/暂停按钮监听器
        playPauseButton.setOnClickListener {
            togglePlayback()
        }
    }

    private fun initializePlayer() {
        val dataSourceFactory = createUnsafeDataSourceFactory()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 1500, 2000)
            .setTargetBufferBytes(-1)
            .build()

        val renderersFactory = DefaultRenderersFactory(context!!)
            .setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(context!!)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_IDLE -> {
                                updateState(PlaybackState.IDLE)
                                videoLoadingProgress.visibility = View.GONE
                            }
                            Player.STATE_BUFFERING -> {
                                updateState(PlaybackState.BUFFERING)
                                videoLoadingProgress.visibility = View.VISIBLE
                                isBuffering = true
                                notifyMediaBuffering(true)
                            }
                            Player.STATE_READY -> {
                                videoLoadingProgress.visibility = View.GONE
                                isBuffering = false
                                notifyMediaBuffering(false)

                                if (isPlayingState) {
                                    updateState(PlaybackState.PLAYING)
                                } else {
                                    updateState(PlaybackState.PAUSED)
                                }

                                updateDuration()
                                startProgressUpdates()
                                notifyPlayerReady()
                            }
                            Player.STATE_ENDED -> {
                                updateState(PlaybackState.ENDED)
                                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                                isPlayingState = false
                                stopProgressUpdates()
                                notifyPlaybackEnded()
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        videoLoadingProgress.visibility = View.GONE
                        updateState(PlaybackState.ERROR, error.message ?: "播放错误")
                        stopProgressUpdates()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@VideoPlaybackController.isPlayingState = isPlaying
                        val state = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                        updateState(state)
                        updatePlayPauseButton()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        // 修复：直接从播放器获取当前时间，而不是使用参数
                        exoPlayer?.let { player ->
                            val newPos = player.currentPosition
                            // 安全地更新当前播放位置
                            this@VideoPlaybackController.currentPosition = newPos
                            notifyProgressUpdate(newPos, currentDuration)
                        }
                    }
                })
            }

        playerView.player = exoPlayer
        playerView.useController = false
    }

    private fun createUnsafeDataSourceFactory(): DataSource.Factory {
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(httpClient)
        return DefaultDataSource.Factory(context!!, okHttpDataSourceFactory)
    }

    override fun release(keepAlive: Boolean) {
        Log.d("VideoPlaybackController", "释放视频播放控制器，保持服务运行: $keepAlive")

        // 视频播放器不支持后台播放，总是完全释放
        releaseCompletely()
    }

    private fun releaseCompletely() {
        Log.d("VideoPlaybackController", "完全释放视频播放器资源")

        stopProgressUpdates()
        exoPlayer?.release()
        exoPlayer = null
        playbackListeners.clear()
        progressListeners.clear()
    }

    override fun getType(): PlaybackType = PlaybackType.VIDEO

    override fun onActivityPause() {
        Log.d("VideoPlaybackController", "onActivityPause: 视频播放器处理Activity暂停")

        // 视频播放器在Activity进入后台时需要暂停播放
        if (isPlayingState) {
            pause()
            Log.d("VideoPlaybackController", "onActivityPause: 视频播放已暂停")
        }

        // 停止进度更新
        stopProgressUpdates()
    }

    override fun onActivityResume() {
        Log.d("VideoPlaybackController", "onActivityResume: 视频播放器处理Activity恢复")

        // 重新启动进度更新（如果需要）
        if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
            startProgressUpdates()
        }

        // 视频播放器不自动恢复播放，让用户决定
        Log.d("VideoPlaybackController", "onActivityResume: 视频播放器保持当前状态")
    }

    override fun supportsBackgroundPlayback(): Boolean = false

    /**
     * 播放媒体项（简化版本）
     */
    fun play(item: MediaPlaybackItem) {
        Log.d("VideoPlaybackController", "播放媒体项: ${item.name}")
        play(item, null, 0)
    }

    override fun play(item: MediaPlaybackItem, playlist: List<MediaPlaybackItem>?, startIndex: Int) {
        currentItem = item
        updateState(PlaybackState.LOADING)

        if (playlist != null) {
            this.playlist = playlist
            currentIndex = playlist.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        } else {
            this.playlist = listOf(item)
            currentIndex = 0
        }

        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(item.url)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            isPlayingState = true
            updatePlayPauseButton()

            notifyTrackChanged(item, currentIndex)
            Log.d("VideoPlaybackController", "开始播放: ${item.name}")

        } catch (e: Exception) {
            updateState(PlaybackState.ERROR, "视频加载失败: ${e.message}")
        }
    }

    override fun play(url: String, metadata: MediaPlaybackItem?) {
        val item = metadata ?: MediaPlaybackItem(
            id = "temp_${System.currentTimeMillis()}",
            name = "视频",
            url = url,
            path = "",
            type = PlaybackType.VIDEO,
            duration = 0L,
            metadata = emptyMap()
        )
        play(item)
    }

    override fun pause() {
        exoPlayer?.pause()
        isPlayingState = false
        updateState(PlaybackState.PAUSED)
        updatePlayPauseButton()
    }

    override fun resume() {
        exoPlayer?.play()
        isPlayingState = true
        updateState(PlaybackState.PLAYING)
        updatePlayPauseButton()
    }

    override fun stop() {
        exoPlayer?.stop()
        isPlayingState = false
        updateState(PlaybackState.IDLE)
        stopProgressUpdates()
        updatePlayPauseButton()
    }

    override fun togglePlayback() {
        if (isPlayingState) {
            pause()
        } else {
            resume()
        }
    }

    override fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        currentPosition = position
        notifyProgressUpdate(position, currentDuration)
    }

    override fun fastForward(milliseconds: Long) {
        val newPosition = (currentPosition + milliseconds).coerceAtMost(currentDuration)
        seekTo(newPosition)
    }

    override fun rewind(milliseconds: Long) {
        val newPosition = (currentPosition - milliseconds).coerceAtLeast(0L)
        seekTo(newPosition)
    }

    override fun setPlaylist(tracks: List<MediaPlaybackItem>, startIndex: Int) {
        playlist = tracks.filter { it.type == PlaybackType.VIDEO }
        if (playlist.isNotEmpty()) {
            currentIndex = startIndex.coerceIn(0, playlist.size - 1)
            play(playlist[currentIndex], playlist, currentIndex)
        } else {
            currentIndex = -1
        }
    }

    override fun playNext() {
        if (playlist.isNotEmpty() && currentIndex < playlist.size - 1) {
            currentIndex++
            play(playlist[currentIndex], playlist, currentIndex)
        }
    }

    override fun playPrevious() {
        if (playlist.isNotEmpty() && currentIndex > 0) {
            currentIndex--
            play(playlist[currentIndex], playlist, currentIndex)
        }
    }

    override fun playAtIndex(index: Int) {
        if (index in playlist.indices) {
            currentIndex = index
            play(playlist[index], playlist, index)
        }
    }

    override fun getPlaylist(): List<MediaPlaybackItem> = playlist

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        exoPlayer?.playbackParameters = exoPlayer?.playbackParameters?.withSpeed(speed)!!
        notifyPlaybackStateChange()
    }

    override fun getPlaybackSpeed(): Float {
        Log.d("VideoPlaybackController", "获取播放速度: $playbackSpeed")
        return playbackSpeed
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        when (mode) {
            RepeatMode.NONE -> exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> exoPlayer?.repeatMode = Player.REPEAT_MODE_ALL
        }
        notifyPlaybackStateChange()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        exoPlayer?.shuffleModeEnabled = enabled
        notifyPlaybackStateChange()
    }

    override fun getPlaybackStatus(): MediaPlaybackStatus {
        return MediaPlaybackStatus(
            state = currentState,
            currentItem = currentItem,
            position = currentPosition,
            duration = currentDuration,
            isPlaying = isPlayingState,
            playbackSpeed = playbackSpeed,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            errorMessage = if (currentState == PlaybackState.ERROR) "播放错误" else null
        )
    }

    override fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: currentPosition
    }

    override fun getDuration(): Long {
        return exoPlayer?.duration ?: currentDuration
    }

    override fun isPlaying(): Boolean {
        return isPlayingState
    }

    override fun getCurrentItem(): MediaPlaybackItem? {
        return currentItem
    }

    override fun getCurrentIndex(): Int = currentIndex

    override fun addPlaybackListener(listener: MediaPlaybackListener) {
        playbackListeners.add(listener)
    }

    override fun removePlaybackListener(listener: MediaPlaybackListener) {
        playbackListeners.remove(listener)
    }

    override fun addProgressListener(listener: MediaProgressListener) {
        progressListeners.add(listener)
    }

    override fun removeProgressListener(listener: MediaProgressListener) {
        progressListeners.remove(listener)
    }

    override fun getPlaybackView(): Any? {
        return playerView
    }

    // ==================== 私有辅助方法 ====================

    private fun startProgressUpdates() {
        uiHandler.post(updateProgressRunnable)
    }

    private fun stopProgressUpdates() {
        uiHandler.removeCallbacks(updateProgressRunnable)
    }

    private fun updateProgress() {
        exoPlayer?.let { player ->
            val duration = player.duration
            val position = player.currentPosition

            if (duration > 0) {
                if (duration != currentDuration) {
                    currentDuration = duration
                    updateDuration()
                }

                currentPosition = position
                val progress = if (duration > 0) {
                    (position * 1000 / duration).toInt()
                } else {
                    0
                }

                seekBar.progress = progress
                currentTimeTextView.text = formatTime(position)
                notifyProgressUpdate(position, duration)
            }

            uiHandler.postDelayed(updateProgressRunnable, 1000)
        }
    }

    private fun updateDuration() {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration > 0) {
                currentDuration = duration
                durationTextView.text = formatTime(duration)
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

    private fun updateState(newState: PlaybackState, errorMessage: String? = null) {
        currentState = newState
        notifyPlaybackStateChange(errorMessage)
    }

    private fun notifyPlaybackStateChange(errorMessage: String? = null) {
        val status = getPlaybackStatus()
        playbackListeners.forEach { listener ->
            listener.onPlaybackStateChanged(status)
            if (errorMessage != null) {
                listener.onPlaybackError(errorMessage)
            }
        }
    }

    private fun notifyTrackChanged(item: MediaPlaybackItem, index: Int) {
        playbackListeners.forEach { listener ->
            listener.onTrackChanged(item, index)
        }
    }

    private fun notifyPlaybackError(error: String) {
        playbackListeners.forEach { listener ->
            listener.onPlaybackError(error)
        }
    }

    private fun notifyPlaybackEnded() {
        playbackListeners.forEach { listener ->
            listener.onPlaybackEnded()
        }
    }

    private fun notifyMediaBuffering(isBuffering: Boolean) {
        playbackListeners.forEach { listener ->
            listener.onMediaBuffering(isBuffering)
        }
    }

    private fun notifyProgressUpdate(position: Long, duration: Long) {
        currentPosition = position
        currentDuration = duration
        progressListeners.forEach { listener ->
            listener.onProgressUpdated(position, duration)
        }
    }

    private fun notifyPlayerReady() {
        // 内部使用，通知播放器已准备就绪
        updateState(PlaybackState.READY)
    }
}