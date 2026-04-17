// ExoAudioPlayerManager.kt
package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
class ExoAudioPlayerManager(
    private val httpClient: okhttp3.OkHttpClient
) : AudioPlayerManager {

    private var context: Context? = null
    private var handler: Handler? = null
    private var exoPlayer: ExoPlayer? = null
    private var retryVisualizerCount = 0
    private var visualizerFailed = false
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var currentState = PlaybackState.IDLE
    private var currentTrack: AudioTrack? = null
    private var currentPosition: Long = 0L
    private var currentDuration: Long = 0L
    private var isBuffering: Boolean = false
    private var playbackSpeed: Float = 1.0f
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var shuffleEnabled: Boolean = false

    private val playlist = mutableListOf<AudioTrack>()
    private var currentIndex: Int = -1

    private val playbackLock = Any()
    private var isPlayingNext = false
    private var isPlayingPrevious = false
    private var isManualOperation = false

    private val playbackListeners = CopyOnWriteArrayList<AudioPlaybackListener>()
    private val progressListeners = CopyOnWriteArrayList<AudioProgressListener>()
    private val spectrumListeners = CopyOnWriteArrayList<AudioSpectrumListener>()

    private var audioVisualizerHelper: AudioVisualizerHelper? = null

    private val updateProgressRunnable = Runnable { updateProgress() }

    override fun initialize(context: Context, handler: Handler) {
        this.context = context
        this.handler = handler

        val dataSourceFactory = createUnsafeDataSourceFactory()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30000, 60000, 1500, 2000)
            .setTargetBufferBytes(-1)
            .build()
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()

        setupPlayerListeners()
        updateState(PlaybackState.IDLE, null)
    }

    private fun createUnsafeDataSourceFactory(): DataSource.Factory {
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(httpClient)
        return DefaultDataSource.Factory(context!!, okHttpDataSourceFactory)
    }

    private fun setupPlayerListeners() {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> updateState(PlaybackState.IDLE, null)
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                        updateState(PlaybackState.BUFFERING, null)
                        notifyBuffering(true)
                    }
                    Player.STATE_READY -> {
                        isBuffering = false
                        currentDuration = exoPlayer?.duration ?: 0L
                        updateState(PlaybackState.READY, null)
                        notifyBuffering(false)
                        startProgressUpdates()
                        if (currentState == PlaybackState.LOADING) {
                            exoPlayer?.playWhenReady = true
                            updateState(PlaybackState.PLAYING, null)
                        }
                        startVisualizer()
                    }
                    Player.STATE_ENDED -> {
                        updateState(PlaybackState.ENDED, null)
                        notifyPlaybackEnded()
                        stopProgressUpdates()
                        handlePlaybackEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorMessage = "播放错误: ${error.message ?: "未知错误"}"
                updateState(PlaybackState.ERROR, errorMessage)
                notifyPlaybackError(errorMessage)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val newState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                if (currentState != newState) {
                    updateState(newState, null)
                }
            }
        })
    }

    private fun startVisualizer() {
        if (spectrumListeners.isEmpty()) return
        if (audioVisualizerHelper != null) return
        if (exoPlayer == null) return

        // 必须在主线程执行
        handler?.post {
            // 检查播放器是否处于 READY 状态
            if (exoPlayer?.playbackState != Player.STATE_READY) {
                Log.d("ExoAudioPlayerManager", "Player not ready, will retry later")
                // 延迟重试（最多重试 5 次，每次间隔 300ms）
                if (retryVisualizerCount < 5) {
                    retryVisualizerCount++
                    handler?.postDelayed({ startVisualizer() }, 300)
                } else {
                    Log.w("ExoAudioPlayerManager", "Visualizer start retry exhausted, disabled")
                    visualizerFailed = true
                }
                return@post
            }

            // 播放器已 READY，再延迟 500ms 启动 Visualizer
            handler?.postDelayed({
                try {
                    if (audioVisualizerHelper != null) return@postDelayed
                    if (exoPlayer?.playbackState != Player.STATE_READY) {
                        Log.w("ExoAudioPlayerManager", "Player left READY state, abort visualizer start")
                        return@postDelayed
                    }
                    val sessionId = exoPlayer?.audioSessionId ?: 0
                    if (sessionId == 0) {
                        Log.w("ExoAudioPlayerManager", "AudioSessionId is 0, abort")
                        return@postDelayed
                    }

                    audioVisualizerHelper = AudioVisualizerHelper(exoPlayer!!) { spectrum ->
                        spectrumListeners.forEach { it.onSpectrumData(spectrum) }
                    }
                    audioVisualizerHelper?.start()
                    retryVisualizerCount = 0
                    Log.d("ExoAudioPlayerManager", "Visualizer started successfully after delay")
                } catch (e: Exception) {
                    Log.e("ExoAudioPlayerManager", "Failed to start visualizer", e)
                    visualizerFailed = true
                    audioVisualizerHelper = null
                }
            }, 500) // 延迟 500 毫秒
        }
    }

    private fun stopVisualizer() {
        audioVisualizerHelper?.stop()
        audioVisualizerHelper = null
    }

    private fun handlePlaybackEnded() {
        if (isManualOperation) return
        synchronized(playbackLock) {
            if (playlist.isEmpty()) return
            when (repeatMode) {
                RepeatMode.ONE -> playAtIndex(currentIndex)
                RepeatMode.ALL -> playNext()
                RepeatMode.NONE -> {}
            }
        }
    }

    override fun release() {
        stopVisualizer()
        stopProgressUpdates()
        exoPlayer?.release()
        exoPlayer = null
        playbackListeners.clear()
        progressListeners.clear()
        spectrumListeners.clear()
        updateState(PlaybackState.IDLE, null)
    }

    override fun play(track: AudioTrack) {
        val targetIndex = playlist.indexOfFirst { it.id == track.id }
        if (currentTrack?.url == track.url && currentIndex == targetIndex &&
            currentState != PlaybackState.IDLE && currentState != PlaybackState.ENDED) {
            Log.d("ExoAudioPlayerManager", "同一首歌，保持进度")
            currentTrack = track
            if (targetIndex != -1) currentIndex = targetIndex
            notifyTrackChanged(track, currentIndex)
            notifyPlaybackStateChange()
            return
        }

        currentTrack = track
        if (targetIndex != -1) currentIndex = targetIndex
        updateState(PlaybackState.LOADING, null)

        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(track.url)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            isManualOperation = true
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            isManualOperation = false

            notifyTrackChanged(track, currentIndex)
        } catch (e: Exception) {
            isManualOperation = false
            val errorMessage = "音频加载失败: ${e.message}"
            updateState(PlaybackState.ERROR, errorMessage)
            notifyPlaybackError(errorMessage)
        }
    }

    override fun play(url: String, metadata: AudioTrack?) {
        val track = metadata ?: AudioTrack(
            id = UUID.randomUUID().toString(),
            name = "未知音频",
            url = url,
            serverUrl = "",
            path = "",
            duration = 0L
        )
        play(track)
    }

    override fun pause() {
        exoPlayer?.pause()
        updateState(PlaybackState.PAUSED, null)
    }

    override fun resume() {
        exoPlayer?.play()
        updateState(PlaybackState.PLAYING, null)
    }

    override fun stop() {
        exoPlayer?.stop()
        updateState(PlaybackState.IDLE, null)
        stopProgressUpdates()
    }

    override fun togglePlayback() {
        exoPlayer?.let {
            if (it.isPlaying) pause() else resume()
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

    override fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int) {
        playlist.clear()
        playlist.addAll(tracks)
        if (tracks.isNotEmpty()) {
            currentIndex = startIndex.coerceIn(0, tracks.size - 1)
            if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
                playAtIndex(currentIndex)
            }
        } else {
            currentIndex = -1
        }
    }

    override fun playNext() {
        synchronized(playbackLock) {
            if (isPlayingNext || playlist.isEmpty()) return
            isPlayingNext = true
            isManualOperation = true
            try {
                val nextIndex = when {
                    shuffleEnabled -> {
                        val available = playlist.indices.toMutableList()
                        available.remove(currentIndex)
                        if (available.isEmpty()) currentIndex else available.random()
                    }
                    repeatMode == RepeatMode.ONE -> currentIndex
                    currentIndex < playlist.size - 1 -> currentIndex + 1
                    repeatMode == RepeatMode.ALL -> 0
                    else -> { isPlayingNext = false; return }
                }
                if (nextIndex in playlist.indices) {
                    play(playlist[nextIndex])
                }
            } finally {
                handler?.postDelayed({
                    isPlayingNext = false
                    isManualOperation = false
                }, 300)
            }
        }
    }

    override fun playPrevious() {
        synchronized(playbackLock) {
            if (isPlayingPrevious || playlist.isEmpty()) return
            isPlayingPrevious = true
            isManualOperation = true
            try {
                val prevIndex = when {
                    shuffleEnabled -> {
                        val available = playlist.indices.toMutableList()
                        available.remove(currentIndex)
                        if (available.isEmpty()) currentIndex else available.random()
                    }
                    repeatMode == RepeatMode.ONE -> currentIndex
                    currentIndex > 0 -> currentIndex - 1
                    repeatMode == RepeatMode.ALL -> playlist.size - 1
                    else -> { isPlayingPrevious = false; return }
                }
                if (prevIndex in playlist.indices) {
                    play(playlist[prevIndex])
                }
            } finally {
                handler?.postDelayed({
                    isPlayingPrevious = false
                    isManualOperation = false
                }, 300)
            }
        }
    }

    override fun playAtIndex(index: Int) {
        synchronized(playbackLock) {
            if (isPlayingNext || index !in playlist.indices) return
            isPlayingNext = true
            isManualOperation = true
            try {
                currentIndex = index
                play(playlist[index])
            } finally {
                handler?.postDelayed({
                    isPlayingNext = false
                    isManualOperation = false
                }, 300)
            }
        }
    }

    override fun getPlaylist(): List<AudioTrack> = playlist.toList()

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        exoPlayer?.playbackParameters = exoPlayer?.playbackParameters?.withSpeed(speed) ?: return
        notifyPlaybackStateChange()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        notifyPlaybackStateChange()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        exoPlayer?.shuffleModeEnabled = false
        notifyPlaybackStateChange()
    }

    override fun getPlaybackStatus(): AudioPlaybackStatus {
        return AudioPlaybackStatus(
            state = currentState,
            currentTrack = currentTrack,
            position = currentPosition,
            duration = currentDuration,
            isPlaying = currentState == PlaybackState.PLAYING,
            playbackSpeed = playbackSpeed,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            errorMessage = if (currentState == PlaybackState.ERROR) "播放错误" else null
        )
    }

    override fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: currentPosition
    override fun getDuration(): Long = exoPlayer?.duration ?: currentDuration
    override fun isPlaying(): Boolean = currentState == PlaybackState.PLAYING
    override fun getCurrentTrack(): AudioTrack? = currentTrack
    override fun getCurrentIndex(): Int = currentIndex

    override fun addPlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.add(listener)
    }
    override fun removePlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.remove(listener)
    }
    override fun addProgressListener(listener: AudioProgressListener) {
        progressListeners.add(listener)
    }
    override fun removeProgressListener(listener: AudioProgressListener) {
        progressListeners.remove(listener)
    }
    override fun addSpectrumListener(listener: AudioSpectrumListener) {
        spectrumListeners.add(listener)
        if (exoPlayer?.playbackState == Player.STATE_READY) {
            startVisualizer()
        }
    }
    override fun removeSpectrumListener(listener: AudioSpectrumListener) {
        spectrumListeners.remove(listener)
        if (spectrumListeners.isEmpty()) {
            stopVisualizer()
        }
    }

    private fun updateState(newState: PlaybackState, errorMessage: String?) {
        currentState = newState
        if (newState == PlaybackState.PLAYING) startProgressUpdates()
        else if (newState in listOf(PlaybackState.PAUSED, PlaybackState.ENDED, PlaybackState.ERROR)) stopProgressUpdates()
        notifyPlaybackStateChange(errorMessage)
    }

    private fun startProgressUpdates() {
        handler?.removeCallbacks(updateProgressRunnable)
        handler?.post(updateProgressRunnable)
    }

    private fun stopProgressUpdates() {
        handler?.removeCallbacks(updateProgressRunnable)
    }

    private fun updateProgress() {
        val position = exoPlayer?.currentPosition ?: 0L
        val duration = exoPlayer?.duration ?: 0L
        currentPosition = position
        currentDuration = duration
        notifyProgressUpdate(position, duration)
        if (currentState == PlaybackState.PLAYING) {
            handler?.postDelayed(updateProgressRunnable, 1000)
        }
    }

    private fun notifyPlaybackStateChange(errorMessage: String? = null) {
        val status = getPlaybackStatus()
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackStateChanged(status)
                if (errorMessage != null && currentState == PlaybackState.ERROR) {
                    listener.onPlaybackError(errorMessage)
                }
            }
        }
    }

    private fun notifyTrackChanged(track: AudioTrack, index: Int) {
        coroutineScope.launch {
            playbackListeners.forEach { it.onTrackChanged(track, index) }
        }
    }

    private fun notifyPlaybackError(error: String) {
        coroutineScope.launch {
            playbackListeners.forEach { it.onPlaybackError(error) }
        }
    }

    private fun notifyPlaybackEnded() {
        coroutineScope.launch {
            playbackListeners.forEach { it.onPlaybackEnded() }
        }
    }

    private fun notifyBuffering(isBuffering: Boolean) {
        coroutineScope.launch {
            playbackListeners.forEach { it.onAudioBuffering(isBuffering) }
        }
    }

    private fun notifyProgressUpdate(position: Long, duration: Long) {
        coroutineScope.launch {
            progressListeners.forEach { it.onProgressUpdated(position, duration) }
        }
    }
}