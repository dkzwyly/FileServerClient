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
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    // 播放状态
    private var currentState = PlaybackState.IDLE
    private var currentTrack: AudioTrack? = null
    private var currentPosition: Long = 0L
    private var currentDuration: Long = 0L
    private var isBuffering: Boolean = false
    private var playbackSpeed: Float = 1.0f
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var shuffleEnabled: Boolean = false

    // 播放列表
    private val playlist = mutableListOf<AudioTrack>()
    private var currentIndex: Int = -1

    private val playbackLock = Any()
    private var isPlayingNext = false
    private var isPlayingPrevious = false
    // 标记是否正在手动操作，防止自动切换干扰
    private var isManualOperation = false

    // 监听器
    private val playbackListeners = CopyOnWriteArrayList<AudioPlaybackListener>()
    private val progressListeners = CopyOnWriteArrayList<AudioProgressListener>()

    // 进度更新Runnable
    private val updateProgressRunnable = Runnable { updateProgress() }

    // ==================== 初始化与基础操作 ====================

    override fun initialize(context: Context, handler: Handler) {
        this.context = context
        this.handler = handler

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
                    Player.STATE_IDLE -> {
                        updateState(PlaybackState.IDLE, null)
                    }
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
                    }
                    Player.STATE_ENDED -> {
                        updateState(PlaybackState.ENDED, null)
                        notifyPlaybackEnded()
                        stopProgressUpdates()
                        // 播放结束，根据模式手动触发下一首
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

            // 移除 onMediaItemTransition 监听，避免干扰 currentIndex 的更新
        })
    }

    // 处理播放结束，根据模式决定是否自动下一首
    private fun handlePlaybackEnded() {
        if (isManualOperation) return // 如果是手动操作触发的结束，不自动切换

        synchronized(playbackLock) {
            if (playlist.isEmpty()) return

            when (repeatMode) {
                RepeatMode.ONE -> {
                    // 单曲循环，重新播放当前曲目
                    playAtIndex(currentIndex)
                }
                RepeatMode.ALL -> {
                    // 列表循环，播放下一个
                    playNext()
                }
                RepeatMode.NONE -> {
                    // 不循环，停止
                }
            }
        }
    }

    override fun release() {
        stopProgressUpdates()
        exoPlayer?.release()
        exoPlayer = null

        playbackListeners.clear()
        progressListeners.clear()

        updateState(PlaybackState.IDLE, null)
    }

    // ==================== 播放控制 ====================

    override fun play(track: AudioTrack) {
        // 获取目标曲目在播放列表中的索引
        val targetIndex = playlist.indexOfFirst { it.id == track.id }

        // 如果是同一首歌且索引相同，且播放器已准备就绪（非空闲/结束），则仅更新UI，不重新播放
        if (currentTrack?.url == track.url && currentIndex == targetIndex && currentState != PlaybackState.IDLE && currentState != PlaybackState.ENDED) {
            Log.d("ExoAudioPlayerManager", "点击了正在播放的同一首歌曲（索引相同）: ${track.name}，保持当前播放进度")

            // 确保 currentTrack 引用正确
            currentTrack = track
            // 确保索引一致
            if (targetIndex != -1) {
                currentIndex = targetIndex
            }

            // 通知UI更新（刷新歌词、标题等）
            notifyTrackChanged(track, currentIndex)
            notifyPlaybackStateChange()
            return // 直接返回，不执行任何播放/跳转操作
        }

        // 正常播放新曲目
        currentTrack = track
        if (targetIndex != -1) {
            currentIndex = targetIndex
        }
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
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                pause()
            } else {
                resume()
            }
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

    // ==================== 播放列表管理 ====================

    override fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int) {
        playlist.clear()
        playlist.addAll(tracks)

        if (tracks.isNotEmpty()) {
            val safeIndex = startIndex.coerceIn(0, tracks.size - 1)
            currentIndex = safeIndex

            if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
                playAtIndex(safeIndex)
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
                        val availableIndices = playlist.indices.toMutableList()
                        availableIndices.remove(currentIndex)
                        if (availableIndices.isEmpty()) currentIndex else availableIndices.random()
                    }
                    repeatMode == RepeatMode.ONE -> currentIndex
                    currentIndex < playlist.size - 1 -> currentIndex + 1
                    repeatMode == RepeatMode.ALL -> 0
                    else -> {
                        isPlayingNext = false
                        return
                    }
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
                        val availableIndices = playlist.indices.toMutableList()
                        availableIndices.remove(currentIndex)
                        if (availableIndices.isEmpty()) currentIndex else availableIndices.random()
                    }
                    repeatMode == RepeatMode.ONE -> currentIndex
                    currentIndex > 0 -> currentIndex - 1
                    repeatMode == RepeatMode.ALL -> playlist.size - 1
                    else -> {
                        isPlayingPrevious = false
                        return
                    }
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
            if (isPlayingNext || index < 0 || index >= playlist.size) return
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

    override fun getPlaylist(): List<AudioTrack> {
        return playlist.toList()
    }

    // ==================== 播放设置 ====================

    override fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        exoPlayer?.playbackParameters = exoPlayer?.playbackParameters?.withSpeed(speed) ?: return
        notifyPlaybackStateChange()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        // 禁用 ExoPlayer 的自动重复，由我们手动控制
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        notifyPlaybackStateChange()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        // 禁用 ExoPlayer 的随机，由我们手动控制
        exoPlayer?.shuffleModeEnabled = false
        notifyPlaybackStateChange()
    }

    // ==================== 状态获取 ====================

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

    override fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: currentPosition
    }

    override fun getDuration(): Long {
        return exoPlayer?.duration ?: currentDuration
    }

    override fun isPlaying(): Boolean {
        return currentState == PlaybackState.PLAYING
    }

    override fun getCurrentTrack(): AudioTrack? {
        return currentTrack
    }

    override fun getCurrentIndex(): Int {
        return currentIndex
    }

    // ==================== 事件监听 ====================

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

    // ==================== 私有辅助方法 ====================

    private fun updateState(newState: PlaybackState, errorMessage: String? = null) {
        currentState = newState

        if (newState == PlaybackState.PLAYING) {
            startProgressUpdates()
        } else if (newState == PlaybackState.PAUSED || newState == PlaybackState.ENDED ||
            newState == PlaybackState.ERROR) {
            stopProgressUpdates()
        }

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

    // ==================== 通知方法 ====================

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
            playbackListeners.forEach { listener ->
                listener.onTrackChanged(track, index)
            }
        }
    }

    private fun notifyPlaybackError(error: String) {
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackError(error)
            }
        }
    }

    private fun notifyPlaybackEnded() {
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onPlaybackEnded()
            }
        }
    }

    private fun notifyBuffering(isBuffering: Boolean) {
        coroutineScope.launch {
            playbackListeners.forEach { listener ->
                listener.onAudioBuffering(isBuffering)
            }
        }
    }

    private fun notifyProgressUpdate(position: Long, duration: Long) {
        coroutineScope.launch {
            progressListeners.forEach { listener ->
                listener.onProgressUpdated(position, duration)
            }
        }
    }
}