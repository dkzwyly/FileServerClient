package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.util.Log

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

    private val playbackListeners = mutableListOf<MediaPlaybackListener>()
    private val progressListeners = mutableListOf<MediaProgressListener>()

    private var currentState = PlaybackState.IDLE
    private var currentPosition: Long = 0L
    private var currentDuration: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var shuffleEnabled: Boolean = false
    private var isReleased = false

    override fun initialize(context: Context, handler: Handler) {
        this.context = context
        audioBackgroundManager = AudioBackgroundManager(context)

        audioBackgroundManager?.addPlaybackListener(object : AudioPlaybackListener {
            override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
                updateFromAudioStatus(status)
            }
            override fun onTrackChanged(track: AudioTrack, index: Int) {
                currentItem = MediaPlaybackItem.fromAudioTrack(track)
                currentIndex = index
                notifyTrackChanged(currentItem!!, index)
            }
            override fun onPlaybackError(error: String) {
                updateState(PlaybackState.ERROR, error)
                notifyPlaybackError(error)
            }
            override fun onPlaybackEnded() {
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

        audioBackgroundManager?.addProgressListener(object : AudioProgressListener {
            override fun onProgressUpdated(position: Long, duration: Long) {
                currentPosition = position
                currentDuration = duration
                notifyProgressUpdate(position, duration)
            }
            override fun onBufferingProgress(percent: Int) {
                progressListeners.forEach { it.onBufferingProgress(percent) }
            }
        })
    }

    override fun release(keepAlive: Boolean) {
        if (isReleased) return
        if (keepAlive && supportsBackgroundPlayback()) {
            releaseLocalOnly()
        } else {
            releaseCompletely()
        }
        isReleased = true
    }

    private fun releaseLocalOnly() {
        playbackListeners.clear()
        progressListeners.clear()
        audioBackgroundManager?.unbindService()
        currentItem = null
        playlist = emptyList()
        currentIndex = -1
        currentPosition = 0L
        currentDuration = 0L
        currentState = PlaybackState.IDLE
    }

    private fun releaseCompletely() {
        playbackListeners.clear()
        progressListeners.clear()
        audioBackgroundManager?.shutdownService()
        audioBackgroundManager = null
        currentItem = null
        playlist = emptyList()
        currentIndex = -1
        currentPosition = 0L
        currentDuration = 0L
        currentState = PlaybackState.IDLE
    }

    override fun getType(): PlaybackType = PlaybackType.AUDIO
    override fun supportsBackgroundPlayback(): Boolean = true

    override fun onActivityPause() {
        Log.d(TAG, "onActivityPause: Activity暂停，音频继续后台播放")
    }

    override fun onActivityResume() {
        Log.d(TAG, "onActivityResume: Activity恢复")
        if (audioBackgroundManager?.isServiceRunning() == true && !(audioBackgroundManager?.isServiceBound() ?: false)) {
            audioBackgroundManager?.bindService()
        }
        val currentStatus = audioBackgroundManager?.getPlaybackStatus()
        currentStatus?.let { status ->
            currentState = status.state
            currentItem = status.currentTrack?.let { convertToMediaPlaybackItem(it) }
            currentPosition = status.position
            currentDuration = status.duration
            playbackSpeed = status.playbackSpeed
            repeatMode = status.repeatMode
            shuffleEnabled = status.shuffleEnabled
            notifyPlaybackStateChange()
            notifyProgressUpdate(currentPosition, currentDuration)
        }
    }

    fun play(item: MediaPlaybackItem) = play(item, null, 0)

    override fun play(item: MediaPlaybackItem, playlist: List<MediaPlaybackItem>?, startIndex: Int) {
        val currentTrack = audioBackgroundManager?.getCurrentTrack()
        val isSameTrack = currentTrack?.url == item.url

        if (isSameTrack && audioBackgroundManager?.isServiceRunning() == true) {
            currentItem = item
            if (playlist != null && playlist.isNotEmpty()) {
                this.playlist = playlist
                currentIndex = startIndex.coerceIn(0, playlist.size - 1)
            }
            val currentStatus = audioBackgroundManager?.getPlaybackStatus()
            currentStatus?.let {
                currentState = it.state
                currentPosition = it.position
                currentDuration = it.duration
                playbackSpeed = it.playbackSpeed
                repeatMode = it.repeatMode
                shuffleEnabled = it.shuffleEnabled
                notifyTrackChanged(item, currentIndex)
                notifyPlaybackStateChange()
            }
            return
        }

        currentItem = item
        updateState(PlaybackState.LOADING)

        if (playlist != null && playlist.isNotEmpty()) {
            this.playlist = playlist
            currentIndex = playlist.indexOfFirst { it.id == item.id }.takeIf { it != -1 } ?: 0
            if (startIndex in playlist.indices) currentIndex = startIndex
        } else {
            this.playlist = listOf(item)
            currentIndex = 0
        }

        val audioTrack = convertToAudioTrack(item)
        val audioTracks = this.playlist.map { convertToAudioTrack(it) }
        audioBackgroundManager?.startService(audioTrack, ArrayList(audioTracks), currentIndex)
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
        val currentTrack = audioBackgroundManager?.getCurrentTrack()
        val isSameTrack = currentTrack?.url == url
        if (isSameTrack && audioBackgroundManager?.isServiceRunning() == true) {
            val currentStatus = audioBackgroundManager?.getPlaybackStatus()
            currentStatus?.let {
                currentState = it.state
                currentItem = it.currentTrack?.let { convertToMediaPlaybackItem(it) }
                currentPosition = it.position
                currentDuration = it.duration
                playbackSpeed = it.playbackSpeed
                repeatMode = it.repeatMode
                shuffleEnabled = it.shuffleEnabled
                notifyPlaybackStateChange()
                notifyProgressUpdate(currentPosition, currentDuration)
            }
            return
        }
        play(item)
    }

    override fun pause() {
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
        updateState(PlaybackState.PAUSED)
    }

    override fun resume() {
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
        updateState(PlaybackState.PLAYING)
    }

    override fun stop() {
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_STOP)
        updateState(PlaybackState.IDLE)
    }

    override fun togglePlayback() {
        val isPlaying = audioBackgroundManager?.isPlaying() ?: false
        audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
        updateState(if (isPlaying) PlaybackState.PAUSED else PlaybackState.PLAYING)
    }

    override fun seekTo(position: Long) {
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
        playlist = tracks.filter { it.type == PlaybackType.AUDIO }
        if (playlist.isNotEmpty()) {
            currentIndex = startIndex.coerceIn(0, playlist.size - 1)
            val audioTracks = playlist.map { convertToAudioTrack(it) }
            audioBackgroundManager?.setPlaylist(ArrayList(audioTracks), currentIndex)
            if (!(audioBackgroundManager?.isServiceRunning() ?: false)) {
                if (currentIndex in playlist.indices) {
                    play(playlist[currentIndex], playlist, currentIndex)
                }
            }
        } else {
            currentIndex = -1
        }
    }

    override fun playNext() {
        audioBackgroundManager?.safePlayNext()
    }

    override fun playPrevious() {
        audioBackgroundManager?.safePlayPrevious()
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
        notifyPlaybackStateChange()
    }

    override fun getPlaybackSpeed(): Float = playbackSpeed

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

    override fun getCurrentPosition(): Long = audioBackgroundManager?.getPlaybackStatus()?.position ?: currentPosition
    override fun getDuration(): Long = audioBackgroundManager?.getPlaybackStatus()?.duration ?: currentDuration
    override fun isPlaying(): Boolean = audioBackgroundManager?.isPlaying() ?: false
    override fun getCurrentItem(): MediaPlaybackItem? = currentItem ?: audioBackgroundManager?.getCurrentTrack()?.let { convertToMediaPlaybackItem(it) }
    override fun getCurrentIndex(): Int = currentIndex

    override fun addPlaybackListener(listener: MediaPlaybackListener) { playbackListeners.add(listener) }
    override fun removePlaybackListener(listener: MediaPlaybackListener) { playbackListeners.remove(listener) }
    override fun addProgressListener(listener: MediaProgressListener) { progressListeners.add(listener) }
    override fun removeProgressListener(listener: MediaProgressListener) { progressListeners.remove(listener) }
    override fun getPlaybackView(): Any? = null

    override fun addSpectrumListener(listener: AudioSpectrumListener) {
        audioBackgroundManager?.addSpectrumListener(listener)
    }

    override fun removeSpectrumListener(listener: AudioSpectrumListener) {
        audioBackgroundManager?.removeSpectrumListener(listener)
    }

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
            if (errorMessage != null) listener.onPlaybackError(errorMessage)
        }
    }

    private fun notifyTrackChanged(item: MediaPlaybackItem, index: Int) {
        playbackListeners.forEach { it.onTrackChanged(item, index) }
    }

    private fun notifyPlaybackError(error: String) {
        playbackListeners.forEach { it.onPlaybackError(error) }
    }

    private fun notifyPlaybackEnded() {
        playbackListeners.forEach { it.onPlaybackEnded() }
    }

    private fun notifyMediaBuffering(isBuffering: Boolean) {
        playbackListeners.forEach { it.onMediaBuffering(isBuffering) }
    }

    private fun notifyProgressUpdate(position: Long, duration: Long) {
        currentPosition = position
        currentDuration = duration
        progressListeners.forEach { it.onProgressUpdated(position, duration) }
    }
}