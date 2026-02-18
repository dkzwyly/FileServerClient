package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.util.Log

/**
 * 音频播放适配器，实现统一播放接口
 * 支持后台播放，服务独立于Activity生命周期
 */
class AudioPlaybackController(
    private val httpClient: okhttp3.OkHttpClient
) : MediaPlaybackController {

    companion object {
        private const val TAG = "AudioPlaybackController"
    }

    private var context: Context? = null
    private var audioBackgroundManager: AudioBackgroundManager? = null
    private var currentItem: MediaPlaybackItem? = null
    private var playlist: List<MediaPlaybackItem> = emptyList()
    private var currentIndex: Int = -1

    // 监听器
    private val playbackListeners = mutableListOf<MediaPlaybackListener>()
    private val progressListeners = mutableListOf<MediaProgressListener>()

    // 当前状态
    private var currentState = PlaybackState.IDLE
    private var currentPosition: Long = 0L
    private var currentDuration: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var shuffleEnabled: Boolean = false

    // 释放标志
    private var isReleased = false

    override fun initialize(context: Context, handler: Handler) {
        this.context = context

        // 初始化音频后台管理器
        audioBackgroundManager = AudioBackgroundManager(context)

        // 添加播放状态监听器
        audioBackgroundManager?.addPlaybackListener(object : AudioPlaybackListener {
            override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
                Log.d(TAG, "播放状态变化: ${status.state}")
                updateFromAudioStatus(status)
            }

            override fun onTrackChanged(track: AudioTrack, index: Int) {
                Log.d(TAG, "曲目变化: ${track.name}, 索引: $index")
                currentItem = MediaPlaybackItem.fromAudioTrack(track)
                currentIndex = index
                notifyTrackChanged(currentItem!!, index)
            }

            override fun onPlaybackError(error: String) {
                Log.e(TAG, "播放错误: $error")
                updateState(PlaybackState.ERROR, error)
                notifyPlaybackError(error)
            }

            override fun onPlaybackEnded() {
                Log.d(TAG, "播放结束")
                updateState(PlaybackState.ENDED)
                notifyPlaybackEnded()
            }

            override fun onAudioBuffering(isBuffering: Boolean) {
                val state = if (isBuffering) PlaybackState.BUFFERING else
                    if (audioBackgroundManager?.isPlaying() == true) PlaybackState.PLAYING else PlaybackState.PAUSED
                updateState(state)
                notifyMediaBuffering(isBuffering)
            }
        })

        // 添加进度监听器
        audioBackgroundManager?.addProgressListener(object : AudioProgressListener {
            override fun onProgressUpdated(position: Long, duration: Long) {
                currentPosition = position
                currentDuration = duration
                notifyProgressUpdate(position, duration)
            }

            override fun onBufferingProgress(percent: Int) {
                // 处理缓冲进度
                progressListeners.forEach { listener ->
                    listener.onBufferingProgress(percent)
                }
            }
        })
    }

    override fun release(keepAlive: Boolean) {
        Log.d(TAG, "释放音频播放控制器，保持服务运行: $keepAlive")

        if (isReleased) {
            Log.d(TAG, "音频播放控制器已释放，跳过")
            return
        }

        if (keepAlive && supportsBackgroundPlayback()) {
            // 保持服务运行，只清理本地资源
            releaseLocalOnly()
            Log.d(TAG, "音频服务继续在后台运行")
        } else {
            // 完全释放，停止服务
            releaseCompletely()
            Log.d(TAG, "音频服务已停止")
        }

        isReleased = true
    }

    /**
     * 只释放本地资源，保持服务运行
     */
    private fun releaseLocalOnly() {
        Log.d(TAG, "只释放本地资源，保持服务运行")

        // 清理监听器
        playbackListeners.clear()
        progressListeners.clear()

        // 解绑服务但不停止服务
        audioBackgroundManager?.unbindService()

        // 重置本地状态
        currentItem = null
        playlist = emptyList()
        currentIndex = -1
        currentPosition = 0L
        currentDuration = 0L
        currentState = PlaybackState.IDLE

        Log.d(TAG, "本地资源已清理，音频服务继续在后台运行")
    }

    /**
     * 完全释放资源，停止服务
     */
    private fun releaseCompletely() {
        Log.d(TAG, "完全释放音频播放器资源")

        // 清理本地监听器
        playbackListeners.clear()
        progressListeners.clear()

        // 停止服务
        audioBackgroundManager?.shutdownService()
        audioBackgroundManager = null

        // 重置本地状态
        currentItem = null
        playlist = emptyList()
        currentIndex = -1
        currentPosition = 0L
        currentDuration = 0L
        currentState = PlaybackState.IDLE

        Log.d(TAG, "音频播放器完全释放")
    }

    override fun getType(): PlaybackType = PlaybackType.AUDIO

    override fun supportsBackgroundPlayback(): Boolean = true

    override fun onActivityPause() {
        Log.d(TAG, "onActivityPause: Activity暂停，音频继续在后台播放")

        // 音频播放器在Activity进入后台时不需要暂停播放
        // 音频可以继续在后台播放，由AudioBackgroundManager处理

        if (audioBackgroundManager?.isServiceRunning() == true) {
            Log.d(TAG, "onActivityPause: 音频播放器继续在后台播放")
        } else {
            Log.d(TAG, "onActivityPause: 音频服务未运行")
        }
    }

    override fun onActivityResume() {
        Log.d(TAG, "onActivityResume: Activity恢复")

        // 尝试重新绑定到服务
        if (audioBackgroundManager?.isServiceRunning() == true) {
            if (!(audioBackgroundManager?.isServiceBound() ?: false)) {
                Log.d(TAG, "onActivityResume: 服务运行中但未绑定，尝试重新绑定")
                audioBackgroundManager?.bindService()
            }
        }

        // 同步状态
        val currentStatus = audioBackgroundManager?.getPlaybackStatus()
        currentStatus?.let { status ->
            Log.d(TAG, "从后台服务同步状态: ${status.state}, 位置: ${status.position}/${status.duration}")

            // 更新本地状态
            currentState = status.state
            currentItem = status.currentTrack?.let { convertToMediaPlaybackItem(it) }
            currentPosition = status.position
            currentDuration = status.duration
            playbackSpeed = status.playbackSpeed
            repeatMode = status.repeatMode
            shuffleEnabled = status.shuffleEnabled

            // 通知UI更新
            notifyPlaybackStateChange()
            notifyProgressUpdate(currentPosition, currentDuration)
        }
    }

    /**
     * 播放媒体项（简化版本）
     */
    fun play(item: MediaPlaybackItem) {
        Log.d(TAG, "播放媒体项: ${item.name}")
        play(item, null, 0)
    }

    // 修改 play 方法（带播放列表的版本）
    override fun play(item: MediaPlaybackItem, playlist: List<MediaPlaybackItem>?, startIndex: Int) {
        Log.d(TAG, "播放: ${item.name}, playlist=${playlist?.size}, startIndex=$startIndex")

        // 检查是否是同一首歌曲（比较URL）
        val currentTrack = audioBackgroundManager?.getCurrentTrack()
        val isSameTrack = currentTrack?.url == item.url

        if (isSameTrack && audioBackgroundManager?.isServiceRunning() == true) {
            // 同一首歌且服务正在运行，保持当前播放进度，仅更新UI
            Log.d(TAG, "点击了正在播放的同一首歌曲: ${item.name}，保持当前播放进度")

            // 获取完整的当前播放状态
            val currentStatus = audioBackgroundManager?.getPlaybackStatus()
            if (currentStatus != null) {
                // 更新本地状态
                currentItem = item
                if (playlist != null && playlist.isNotEmpty()) {
                    this.playlist = playlist
                    currentIndex = startIndex.coerceIn(0, playlist.size - 1)
                }

                // 同步播放状态（从服务获取）
                currentState = currentStatus.state
                currentPosition = currentStatus.position
                currentDuration = currentStatus.duration
                playbackSpeed = currentStatus.playbackSpeed
                repeatMode = currentStatus.repeatMode
                shuffleEnabled = currentStatus.shuffleEnabled

                // 通知UI更新（轨道变化和状态变化）
                notifyTrackChanged(item, currentIndex)
                notifyPlaybackStateChange()
            }
            return  // 不执行任何播放控制命令
        }

        // 如果不是同一首歌，执行正常播放流程
        currentItem = item
        updateState(PlaybackState.LOADING)

        // 更新播放列表
        if (playlist != null && playlist.isNotEmpty()) {
            this.playlist = playlist

            // 查找当前项目在播放列表中的索引
            currentIndex = playlist.indexOfFirst { it.id == item.id }
            if (currentIndex == -1) {
                currentIndex = 0
            }

            // 确保 startIndex 有效时使用它
            if (startIndex in playlist.indices) {
                currentIndex = startIndex
            }
        } else {
            // 没有播放列表，创建只包含当前项目的列表
            this.playlist = listOf(item)
            currentIndex = 0
        }

        // 转换为AudioTrack
        val audioTrack = convertToAudioTrack(item)
        val audioTracks = this.playlist.map { convertToAudioTrack(it) }
        val trackList = ArrayList(audioTracks)

        // 启动后台服务
        audioBackgroundManager?.startService(
            track = audioTrack,
            playlist = trackList,
            startIndex = currentIndex
        )

        notifyTrackChanged(item, currentIndex)
    }

    override fun play(url: String, metadata: MediaPlaybackItem?) {
        val item = metadata ?: MediaPlaybackItem(
            id = "temp_${System.currentTimeMillis()}",
            name = "音频",
            url = url,
            path = "",
            type = PlaybackType.AUDIO,
            duration = 0L,
            metadata = emptyMap()
        )

        // 检查是否是同一首歌曲
        val currentTrack = audioBackgroundManager?.getCurrentTrack()
        val isSameTrack = currentTrack?.url == url

        Log.d(TAG, "play() 检查同一首歌: isSameTrack=$isSameTrack, currentUrl=${currentTrack?.url}, newUrl=$url")

        if (isSameTrack && audioBackgroundManager?.isServiceRunning() == true) {
            // 同一首歌且服务正在运行，保持当前播放进度
            Log.d(TAG, "点击了正在播放的同一首歌曲，保持当前播放进度")

            // 获取当前播放状态
            val currentStatus = audioBackgroundManager?.getPlaybackStatus()
            currentStatus?.let { status ->
                // 同步状态信息
                currentState = status.state
                currentItem = status.currentTrack?.let { convertToMediaPlaybackItem(it) }
                currentPosition = status.position
                currentDuration = status.duration
                playbackSpeed = status.playbackSpeed
                repeatMode = status.repeatMode
                shuffleEnabled = status.shuffleEnabled

                // 通知UI更新
                notifyPlaybackStateChange()
                notifyProgressUpdate(currentPosition, currentDuration)
            }
            return
        }

        // 如果不是同一首歌，执行正常播放
        play(item)
    }

    override fun pause() {
        Log.d(TAG, "暂停播放")
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
        updateState(PlaybackState.PAUSED)
    }

    override fun resume() {
        Log.d(TAG, "恢复播放")

        // 重要：直接发送播放命令，不检查是否已经在播放
        // 因为从音乐库初次进入时，歌曲可能还没开始播放
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
        updateState(PlaybackState.PLAYING)
    }

    override fun stop() {
        Log.d(TAG, "停止播放")
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_STOP)
        updateState(PlaybackState.IDLE)
    }

    override fun togglePlayback() {
        Log.d(TAG, "切换播放状态")

        // 获取当前播放状态
        val isPlaying = audioBackgroundManager?.isPlaying() ?: false

        if (isPlaying) {
            // 当前正在播放，发送暂停命令
            audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
            updateState(PlaybackState.PAUSED)
        } else {
            // 当前暂停，发送播放命令
            audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
            updateState(PlaybackState.PLAYING)
        }
    }

    override fun seekTo(position: Long) {
        Log.d(TAG, "跳转到: $position")
        audioBackgroundManager?.seekTo(position)
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
        Log.d(TAG, "设置播放列表，大小: ${tracks.size}, 起始索引: $startIndex")

        playlist = tracks.filter { it.type == PlaybackType.AUDIO }
        if (playlist.isNotEmpty()) {
            currentIndex = startIndex.coerceIn(0, playlist.size - 1)

            // 转换播放列表为AudioTrack
            val audioTracks = playlist.map { convertToAudioTrack(it) }
            val trackList = ArrayList(audioTracks)

            // 通过后台管理器设置播放列表
            audioBackgroundManager?.setPlaylist(trackList, currentIndex)

            // 如果服务未启动，启动它
            if (!(audioBackgroundManager?.isServiceRunning() ?: false)) {
                if (currentIndex in playlist.indices) {
                    play(playlist[currentIndex], playlist, currentIndex)
                }
            }

            Log.d(TAG, "播放列表设置完成，当前索引: $currentIndex")
        } else {
            currentIndex = -1
            Log.w(TAG, "播放列表为空")
        }
    }

    override fun playNext() {
        Log.d(TAG, "播放下一首")
        audioBackgroundManager?.safePlayNext()
    }

    override fun playPrevious() {
        Log.d(TAG, "播放上一首")
        audioBackgroundManager?.safePlayPrevious()
    }

    override fun playAtIndex(index: Int) {
        if (index in playlist.indices) {
            currentIndex = index
            play(playlist[index], playlist, index)
        } else {
            Log.e(TAG, "无效的索引: $index, 播放列表大小: ${playlist.size}")
        }
    }

    override fun getPlaylist(): List<MediaPlaybackItem> = playlist

    override fun setPlaybackSpeed(speed: Float) {
        Log.d(TAG, "设置播放速度: $speed")
        playbackSpeed = speed
        // TODO: 通过后台服务设置播放速度
        // audioBackgroundManager?.setPlaybackSpeed(speed)
        notifyPlaybackStateChange()
    }

    override fun getPlaybackSpeed(): Float {
        Log.d(TAG, "获取播放速度: $playbackSpeed")
        return playbackSpeed
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        audioBackgroundManager?.setRepeatMode(mode)
        notifyPlaybackStateChange()
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleEnabled = enabled
        audioBackgroundManager?.setShuffleEnabled(enabled)
        notifyPlaybackStateChange()
    }

    override fun getPlaybackStatus(): MediaPlaybackStatus {
        return MediaPlaybackStatus(
            state = currentState,
            currentItem = currentItem,
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
        return audioBackgroundManager?.getPlaybackStatus()?.position ?: currentPosition
    }

    override fun getDuration(): Long {
        return audioBackgroundManager?.getPlaybackStatus()?.duration ?: currentDuration
    }

    override fun isPlaying(): Boolean {
        return audioBackgroundManager?.isPlaying() ?: false
    }

    override fun getCurrentItem(): MediaPlaybackItem? {
        return currentItem ?: audioBackgroundManager?.getCurrentTrack()?.let {
            convertToMediaPlaybackItem(it)
        }
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

    override fun getPlaybackView(): Any? = null // 音频不需要播放视图

    // ==================== 私有辅助方法 ====================

    private fun convertToAudioTrack(item: MediaPlaybackItem): AudioTrack {
        return AudioTrack(
            id = item.id,
            name = item.name,
            url = item.url,
            serverUrl = item.metadata["serverUrl"] as? String ?: "",
            path = item.path,
            duration = item.duration,
            artist = item.metadata["artist"] as? String,
            album = item.metadata["album"] as? String,
            coverUrl = item.metadata["coverUrl"] as? String,
            fileExtension = item.metadata["fileExtension"] as? String ?: "",
            sizeFormatted = item.metadata["sizeFormatted"] as? String ?: ""
        )
    }

    private fun convertToMediaPlaybackItem(track: AudioTrack): MediaPlaybackItem {
        return MediaPlaybackItem(
            id = track.id,
            name = track.name,
            url = track.url,
            path = track.path,
            type = PlaybackType.AUDIO,
            duration = track.duration,
            metadata = mapOf(
                "artist" to (track.artist ?: ""),
                "album" to (track.album ?: ""),
                "coverUrl" to (track.coverUrl ?: ""),
                "serverUrl" to track.serverUrl,
                "fileExtension" to track.fileExtension,
                "sizeFormatted" to track.sizeFormatted
            )
        )
    }

    private fun updateFromAudioStatus(status: AudioPlaybackStatus) {
        currentState = status.state
        currentItem = status.currentTrack?.let { convertToMediaPlaybackItem(it) }
        currentPosition = status.position
        currentDuration = status.duration
        playbackSpeed = status.playbackSpeed
        repeatMode = status.repeatMode
        shuffleEnabled = status.shuffleEnabled

        notifyPlaybackStateChange()
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
}