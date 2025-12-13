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
import java.util.*
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

                        // 自动开始播放
                        if (currentState == PlaybackState.LOADING) {
                            exoPlayer?.playWhenReady = true
                            updateState(PlaybackState.PLAYING, null)
                        }
                    }
                    Player.STATE_ENDED -> {
                        updateState(PlaybackState.ENDED, null)
                        notifyPlaybackEnded()
                        stopProgressUpdates()

                        // 根据重复模式处理
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 媒体项切换
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    currentIndex >= 0 && currentIndex < playlist.size) {
                    notifyTrackChanged(playlist[currentIndex], currentIndex)
                }
            }
        })
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
        // 检查是否是同一首歌曲
        val isSameTrack = currentTrack?.url == track.url

        if (isSameTrack && currentState != PlaybackState.IDLE && currentState != PlaybackState.ENDED) {
            // 同一首歌且正在播放或暂停，仅更新UI
            Log.d("ExoAudioPlayerManager", "同一首歌已正在播放：${track.name}")

            // 更新当前轨道和索引
            currentTrack = track

            // 更新播放列表中的索引
            val index = playlist.indexOfFirst { it.id == track.id }
            if (index != -1) {
                currentIndex = index
            }

            // 通知轨道变更（这会更新UI）
            notifyTrackChanged(track, currentIndex)

            // 获取当前状态并通知
            val status = getPlaybackStatus()
            notifyPlaybackStateChange()

            // 更新进度显示
            val currentPos = exoPlayer?.currentPosition ?: 0L
            val duration = exoPlayer?.duration ?: 0L
            notifyProgressUpdate(currentPos, duration)

            return  // 重要：直接返回，不重新播放
        }

        // 如果不是同一首歌，执行正常播放流程
        currentTrack = track
        updateState(PlaybackState.LOADING, null)

        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(track.url)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()

            // 更新播放列表中的索引
            val index = playlist.indexOfFirst { it.id == track.id }
            if (index != -1) {
                currentIndex = index
            }

            // 重要：通知轨道变更
            notifyTrackChanged(track, currentIndex)

        } catch (e: Exception) {
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

            // 如果当前正在播放，切换到新的播放列表
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

            try {
                val nextIndex = when {
                    shuffleEnabled -> {
                        // 随机播放逻辑
                        val availableIndices = playlist.indices.toMutableList()
                        availableIndices.remove(currentIndex)
                        if (availableIndices.isEmpty()) currentIndex else availableIndices.random()
                    }
                    repeatMode == RepeatMode.ONE -> currentIndex
                    currentIndex < playlist.size - 1 -> currentIndex + 1
                    repeatMode == RepeatMode.ALL -> 0  // 循环到开头
                    else -> {
                        isPlayingNext = false
                        return  // 没有下一首，不播放
                    }
                }

                // 确保索引有效
                if (nextIndex in playlist.indices) {
                    currentIndex = nextIndex
                    play(playlist[nextIndex])
                }
            } finally {
                handler?.postDelayed({
                    isPlayingNext = false
                }, 300) // 防止快速连续点击
            }
        }
    }

    override fun playPrevious() {
        synchronized(playbackLock) {
            if (isPlayingPrevious || playlist.isEmpty()) return
            isPlayingPrevious = true

            try {
                val prevIndex = when {
                    shuffleEnabled -> {
                        // 随机播放逻辑
                        val availableIndices = playlist.indices.toMutableList()
                        availableIndices.remove(currentIndex)
                        if (availableIndices.isEmpty()) currentIndex else availableIndices.random()
                    }
                    repeatMode == RepeatMode.ONE -> currentIndex
                    currentIndex > 0 -> currentIndex - 1
                    repeatMode == RepeatMode.ALL -> playlist.size - 1  // 循环到最后
                    else -> {
                        isPlayingPrevious = false
                        return  // 没有上一首，不播放
                    }
                }

                if (prevIndex in playlist.indices) {
                    currentIndex = prevIndex
                    play(playlist[prevIndex])
                }
            } finally {
                handler?.postDelayed({
                    isPlayingPrevious = false
                }, 300)
            }
        }
    }

    override fun playAtIndex(index: Int) {
        synchronized(playbackLock) {
            if (isPlayingNext || index < 0 || index >= playlist.size) return
            isPlayingNext = true

            try {
                currentIndex = index
                play(playlist[index])
            } finally {
                handler?.postDelayed({
                    isPlayingNext = false
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

        // 通知状态更新
        notifyPlaybackStateChange()
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        // 同步到ExoPlayer的重复模式
        exoPlayer?.repeatMode = when(mode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        notifyPlaybackStateChange()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        exoPlayer?.shuffleModeEnabled = enabled
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

        // 通知进度更新
        notifyProgressUpdate(position, duration)

        // 继续下一次更新
        if (currentState == PlaybackState.PLAYING) {
            handler?.postDelayed(updateProgressRunnable, 1000)
        }
    }

    private fun handlePlaybackEnded() {
        // 如果是音频模式，且播放列表中有多个曲目，则让服务层处理自动连播
        // 这里不处理自动连播，避免与服务层的逻辑冲突
        Log.d("ExoAudioPlayerManager", "播放结束，通知服务层处理")

        // 直接通知播放结束事件，由服务层决定如何处理
        notifyPlaybackEnded()
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