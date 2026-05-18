package com.dkc.fileserverclient

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.*
import kotlinx.coroutines.*
import java.io.File

class AudioPlayerViewModel(application: Application) : AndroidViewModel(application),
    MediaPlaybackListener, MediaProgressListener,
    LyricsManager.LyricsStateListener,
    LyricsManager.TimeProvider,
    LyricsManager.PlayStateProvider {

    private lateinit var mediaPlaybackController: MediaPlaybackController
    private lateinit var metadataManager: SongMetadataManager
    internal lateinit var lyricsManager: LyricsManager
    private var audioBackgroundManager: AudioBackgroundManager? = null

    val currentTrackName = MutableLiveData<String>()
    val artistAlbum = MutableLiveData<String>()
    val coverLocalPath = MutableLiveData<String?>()
    val isPlaying = MutableLiveData<Boolean>()
    val playbackState = MutableLiveData<PlaybackState>()
    val currentPosition = MutableLiveData<Long>()
    val duration = MutableLiveData<Long>()
    val currentLyricsLine = MutableLiveData<String?>()
    val nextLyricsLine = MutableLiveData<String?>()
    val spectrumData = MutableLiveData<FloatArray>()
    val errorMessage = MutableLiveData<String>()
    val playbackSpeed = MutableLiveData<Float>(1.0f)
    val currentSongMetadata = MutableLiveData<SongMetadata?>()
    val lyricsFileSelection = MutableLiveData<List<FileServerService.LyricsFileInfo>?>()

    // 播放模式
    private var currentPlayMode = PlaylistDetailActivity.MODE_LIST

    private var serverUrl = ""
    private var songPath = ""
    private var currentTrack: AudioTrack? = null
    private var playlist: List<AudioTrack> = emptyList()
    private var currentIndex = -1

    private var currentCoverUrl: String? = null

    // ==================== 新增优化变量 ====================
    // 缓存最新进度，用于界面恢复时立刻显示
    private var latestPosition: Long = 0L
    private var latestDuration: Long = 0L
    // 标记是否已完成首次初始化，防止重复创建播放器
    private var isInitialized = false

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 后台服务监听器（用于获取真实进度和状态）
    private val servicePlaybackListener = object : AudioPlaybackListener {
        override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
            isPlaying.postValue(status.isPlaying)
            playbackState.postValue(status.state)
        }
        override fun onTrackChanged(track: AudioTrack, index: Int) {
            track?.let { updateTrackInfo(it) }
        }
        override fun onPlaybackError(error: String) {
            errorMessage.postValue(error)
        }
        override fun onPlaybackEnded() {}
        override fun onAudioBuffering(isBuffering: Boolean) {
            playbackState.postValue(if (isBuffering) PlaybackState.BUFFERING else PlaybackState.PLAYING)
        }
    }

    private val serviceProgressListener = object : AudioProgressListener {
        override fun onProgressUpdated(position: Long, duration: Long) {
            latestPosition = position
            latestDuration = duration
            currentPosition.postValue(position)
            this@AudioPlayerViewModel.duration.postValue(duration)
        }
        override fun onBufferingProgress(percent: Int) {}
    }

    init {
        CoverImageStorage.init(getApplication(), UnsafeHttpClient.createUnsafeOkHttpClient())
    }

    fun init(intent: Intent) {
        // 如果已经初始化过，仅同步进度并返回（不再重复创建播放器）
        if (isInitialized) {
            refreshPositionImmediately()
            return
        }
        isInitialized = true

        val track: AudioTrack? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_TRACK", AudioTrack::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("AUDIO_TRACK")
        }

        val tracks: ArrayList<AudioTrack>? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("AUDIO_TRACKS", AudioTrack::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("AUDIO_TRACKS")
        }

        val index = intent.getIntExtra("CURRENT_INDEX", 0)
        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        songPath = intent.getStringExtra("FILE_PATH") ?: track?.path ?: ""

        currentPlayMode = intent.getIntExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, PlaylistDetailActivity.MODE_LIST)

        if (track == null) {
            errorMessage.value = "无法获取音频信息"
            isInitialized = false  // 失败允许重试
            return
        }

        mediaPlaybackController = MediaPlaybackFactory.createController(
            type = PlaybackType.AUDIO,
            httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()
        )
        mediaPlaybackController.initialize(getApplication(), handler)
        mediaPlaybackController.addPlaybackListener(this)
        mediaPlaybackController.addProgressListener(this)

        mediaPlaybackController.addSpectrumListener(object : AudioSpectrumListener {
            override fun onSpectrumData(spectrum: FloatArray) {
                spectrumData.postValue(spectrum)
            }
        })

        lyricsManager = LyricsManager(getApplication(), handler, scope)
        lyricsManager.setListener(this)

        metadataManager = SongMetadataManager(getApplication(), FileServerService(getApplication()))

        // 创建后台服务管理器并注册服务监听器（确保后台进度不丢失）
        audioBackgroundManager = AudioBackgroundManager(getApplication())
        audioBackgroundManager?.addPlaybackListener(servicePlaybackListener)
        audioBackgroundManager?.addProgressListener(serviceProgressListener)
        audioBackgroundManager?.bindService()

        currentTrack = track
        playlist = tracks ?: listOf(track)
        currentIndex = index.coerceIn(0, playlist.size - 1)

        val mediaItem = MediaPlaybackItem.fromAudioTrack(track)
        mediaPlaybackController.play(mediaItem, playlist.map { MediaPlaybackItem.fromAudioTrack(it) }, currentIndex)

        applyPlayMode(currentPlayMode)

        updateTrackInfo(track)
        loadCoverAndMetadata()
        lyricsManager.loadLyrics(serverUrl, songPath, track.name)
    }

    // ==================== 新增核心方法 ====================
    /**
     * 立即刷新进度，消除从通知栏返回时的停滞感。
     * 优先从后台服务获取真实进度，其次使用缓存，最后尝试本地播放器。
     */
    fun refreshPositionImmediately() {
        // 1. 优先从后台服务获取（最权威，真正的实时进度）
        val serviceStatus = audioBackgroundManager?.getPlaybackStatus()
        if (serviceStatus != null && serviceStatus.duration > 0) {
            latestPosition = serviceStatus.position
            latestDuration = serviceStatus.duration
            currentPosition.value = serviceStatus.position
            duration.value = serviceStatus.duration
            return
        }

        // 2. 服务不可用时，使用最近一次回调缓存
        if (latestDuration > 0) {
            currentPosition.value = latestPosition
            duration.value = latestDuration
        }

        // 3. 最后尝试本地播放器的实时查询（作为补充）
        if (::mediaPlaybackController.isInitialized) {
            val realPos = mediaPlaybackController.getCurrentPosition()
            val realDur = mediaPlaybackController.getDuration()
            if (realPos >= 0 && realDur > 0) {
                latestPosition = realPos
                latestDuration = realDur
                currentPosition.value = realPos
                duration.value = realDur
            }
        }
    }

    private fun applyPlayMode(mode: Int) {
        when (mode) {
            PlaylistDetailActivity.MODE_LIST -> {
                mediaPlaybackController.setRepeatMode(RepeatMode.ALL)
                mediaPlaybackController.setShuffleEnabled(false)
            }
            PlaylistDetailActivity.MODE_SINGLE -> {
                mediaPlaybackController.setRepeatMode(RepeatMode.ONE)
                mediaPlaybackController.setShuffleEnabled(false)
            }
            PlaylistDetailActivity.MODE_RANDOM -> {
                mediaPlaybackController.setRepeatMode(RepeatMode.ALL)
                mediaPlaybackController.setShuffleEnabled(true)
            }
        }
    }

    fun setPlayMode(mode: Int) {
        currentPlayMode = mode
        applyPlayMode(mode)
    }

    private fun updateTrackInfo(track: AudioTrack) {
        currentTrackName.value = track.name
    }

    private fun loadCoverAndMetadata() {
        // 保持原有逻辑不变（省略以节省篇幅，实际代码未修改）
        scope.launch {
            try {
                val metadata = withContext(Dispatchers.IO) {
                    metadataManager.getMetadata(serverUrl, songPath)
                }
                currentSongMetadata.postValue(metadata)

                val artist = metadata?.artist?.ifEmpty { null } ?: currentTrack?.artist
                val album = metadata?.album?.ifEmpty { null } ?: currentTrack?.album
                artistAlbum.value = listOfNotNull(artist, album).let {
                    if (it.isNotEmpty()) it.joinToString(" · ") else currentTrack?.name ?: "未知"
                }

                val trackId = currentTrack?.id ?: songPath
                val baseCoverUrl = if (metadata?.hasCover == true) {
                    metadataManager.getCoverUrl(serverUrl, songPath, addTimestamp = false)
                } else null

                currentCoverUrl = baseCoverUrl

                if (baseCoverUrl == null) {
                    coverLocalPath.postValue(null)
                    return@launch
                }

                val localFile = CoverImageStorage.getLocalFile(trackId, baseCoverUrl)
                if (localFile.exists()) {
                    coverLocalPath.postValue(localFile.absolutePath)
                } else {
                    coverLocalPath.postValue(null)
                    CoverImageStorage.downloadCover(
                        trackId = trackId,
                        coverUrl = baseCoverUrl,
                        scope = scope,
                        onResult = { file ->
                            if (file != null) {
                                coverLocalPath.postValue(file.absolutePath)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                artistAlbum.value = currentTrack?.name ?: "未知"
                coverLocalPath.postValue(null)
            }
        }
    }

    // ==================== 播放控制（修改后支持服务降级） ====================
    fun togglePlayback() {
        if (audioBackgroundManager?.isServiceRunning() == true) {
            // ACTION_PLAY_PAUSE 本身就会切换播放/暂停，无需判断状态
            audioBackgroundManager?.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
        } else {
            mediaPlaybackController.togglePlayback()
        }
    }

    fun playNext() {
        if (audioBackgroundManager?.isServiceRunning() == true) {
            audioBackgroundManager?.safePlayNext()
        } else {
            mediaPlaybackController.playNext()
        }
    }

    fun playPrevious() {
        if (audioBackgroundManager?.isServiceRunning() == true) {
            audioBackgroundManager?.safePlayPrevious()
        } else {
            mediaPlaybackController.playPrevious()
        }
    }

    fun seekTo(position: Long) {
        // 乐观更新：立刻刷新 UI
        latestPosition = position
        currentPosition.value = position
        // 执行真实 seek
        if (audioBackgroundManager?.isServiceRunning() == true) {
            audioBackgroundManager?.seekTo(position)
        } else {
            mediaPlaybackController.seekTo(position)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaPlaybackController.setPlaybackSpeed(speed)
        playbackSpeed.value = speed
    }

    fun reloadLyrics() {
        lyricsManager.loadLyrics(serverUrl, songPath, currentTrack?.name ?: "")
    }

    fun markAsNoLyrics() {
        scope.launch {
            val ok = lyricsManager.markNoLyrics(serverUrl, songPath)
            if (ok) {
                lyricsManager.stopLyricsUpdates()
                lyricsManager.clear()
                onNoLyrics()
            }
        }
    }

    fun showDirectoryLyricsFiles(callback: (List<FileServerService.LyricsFileInfo>) -> Unit) {
        scope.launch {
            val dir = File(songPath).parent ?: ""
            val files = lyricsManager.getLyricsFiles(serverUrl, dir)
            callback(files)
        }
    }

    fun selectLyricsFile(file: FileServerService.LyricsFileInfo, onSuccess: () -> Unit) {
        scope.launch {
            val ok = lyricsManager.saveLyricsMapping(serverUrl, songPath, file.path)
            if (ok) onSuccess()
        }
    }

    fun saveMetadata(title: String?, artist: String?, album: String?) {
        scope.launch {
            val ok = metadataManager.saveMetadata(serverUrl, songPath, title, artist, album)
            if (ok) {
                loadCoverAndMetadata()
                Toast.makeText(getApplication(), "保存成功", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(getApplication(), "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun uploadCover(uri: Uri) {
        scope.launch {
            try {
                val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@launch
                val tempFile = File(getApplication<Application>().cacheDir, "temp_cover.jpg")
                tempFile.outputStream().use { out -> input.copyTo(out) }
                input.close()
                val ok = metadataManager.uploadCover(serverUrl, songPath, tempFile)
                tempFile.delete()
                if (ok) {
                    loadCoverAndMetadata()
                    Toast.makeText(getApplication(), "封面上传成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(getApplication(), "封面上传失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun confirmDeleteCover() {
        scope.launch {
            val ok = metadataManager.deleteCover(serverUrl, songPath)
            if (ok) {
                val trackId = currentTrack?.id ?: songPath
                currentCoverUrl?.let { url ->
                    CoverImageStorage.deleteLocalFile(trackId, url)
                }
                currentCoverUrl = null
                loadCoverAndMetadata()
                Toast.makeText(getApplication(), "已删除封面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onActivityPause() {
        mediaPlaybackController.onActivityPause()
    }

    fun onActivityResume() {
        mediaPlaybackController.onActivityResume()
    }

    fun release() {
        mediaPlaybackController.release(keepAlive = true)
        mediaPlaybackController.removePlaybackListener(this)
        mediaPlaybackController.removeProgressListener(this)
        audioBackgroundManager?.removePlaybackListener(servicePlaybackListener)
        audioBackgroundManager?.removeProgressListener(serviceProgressListener)
        audioBackgroundManager?.unbindService()
        scope.cancel()
    }

    // ==================== MediaPlaybackListener 实现 ====================
    override fun onPlaybackStateChanged(status: MediaPlaybackStatus) {
        isPlaying.value = status.isPlaying
        playbackState.value = status.state
    }

    override fun onTrackChanged(item: MediaPlaybackItem, index: Int) {
        currentTrack = item.toAudioTrack()
        currentIndex = index
        songPath = item.path
        updateTrackInfo(currentTrack!!)
        loadCoverAndMetadata()
        lyricsManager.loadLyrics(serverUrl, songPath, currentTrack!!.name)
    }

    override fun onPlaybackError(error: String) {
        errorMessage.value = error
    }

    override fun onPlaybackEnded() {}

    override fun onMediaBuffering(isBuffering: Boolean) {
        playbackState.value = if (isBuffering) PlaybackState.BUFFERING else PlaybackState.PLAYING
    }

    // ==================== MediaProgressListener 实现 ====================
    override fun onProgressUpdated(position: Long, duration: Long) {
        // 更新缓存
        latestPosition = position
        latestDuration = duration
        currentPosition.value = position
        this.duration.value = duration
    }

    override fun onBufferingProgress(percent: Int) {}

    // ==================== 歌词相关回调（保持不变） ====================
    override fun onLyricsLoaded(data: LyricsData?, title: String?) {
        lyricsManager.startLyricsUpdates()
    }

    override fun onLyricsUpdated(currentLine: String?, nextLine: String?) {
        currentLyricsLine.postValue(currentLine)
        nextLyricsLine.postValue(nextLine)
    }

    override fun onLyricsError(message: String) {
        currentLyricsLine.postValue(message)
        nextLyricsLine.postValue("")
    }

    override fun onLyricsFileSelected(files: List<FileServerService.LyricsFileInfo>) {
        lyricsFileSelection.postValue(files)
    }

    override fun onNoLyrics() {
        currentLyricsLine.postValue("此歌曲无歌词")
        nextLyricsLine.postValue("")
    }

    override fun getCurrentTime(): Long = currentPosition.value ?: 0L
    override fun isPlaying(): Boolean = isPlaying.value ?: false

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}