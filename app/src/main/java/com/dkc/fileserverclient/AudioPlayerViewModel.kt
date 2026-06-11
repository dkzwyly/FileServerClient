package com.dkc.fileserverclient

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import java.io.File

class AudioPlayerViewModel(application: Application) : AndroidViewModel(application),
    AudioPlaybackListener, AudioProgressListener,
    LyricsManager.LyricsStateListener,
    LyricsManager.TimeProvider,
    LyricsManager.PlayStateProvider {

    // ---------- UI LiveData ----------
    val currentTrackName = MutableLiveData<String>()
    val artistAlbum = MutableLiveData<String>()
    val coverLocalPath = MutableLiveData<String?>()
    val isPlaying = MutableLiveData<Boolean>()
    val playbackState = MutableLiveData<PlaybackState>()
    private val prefs: SharedPreferences = application.getSharedPreferences("audio_cache", Context.MODE_PRIVATE)
    val currentPosition = MutableLiveData(prefs.getLong("last_position", 0L))
    val duration = MutableLiveData(prefs.getLong("last_duration", 0L))
    val currentLyricsLine = MutableLiveData<String?>()
    val nextLyricsLine = MutableLiveData<String?>()
    val spectrumData = MutableLiveData<FloatArray>()
    val errorMessage = MutableLiveData<String?>()
    val playbackSpeed = MutableLiveData<Float>(1.0f)
    val currentSongMetadata = MutableLiveData<SongMetadata?>()
    val lyricsFileSelection = MutableLiveData<List<FileServerService.LyricsFileInfo>?>()

    // 播放模式
    private var currentPlayMode = PlaylistDetailActivity.MODE_LIST

    private lateinit var audioBackgroundManager: AudioBackgroundManager
    private lateinit var lyricsManager: LyricsManager
    private lateinit var metadataManager: SongMetadataManager

    private var serverUrl = ""
    private var songPath = ""
    private var currentTrack: AudioTrack? = null
    private var playlist: List<AudioTrack> = emptyList()
    private var currentIndex = -1
    private var currentCoverUrl: String? = null

    // 缓存最新进度（用于快速响应）
    private var latestPosition = 0L
    private var latestDuration = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 频谱监听器引用，用于后续移除
    private val spectrumListener = object : AudioSpectrumListener {
        override fun onSpectrumData(spectrum: FloatArray) {
            spectrumData.postValue(spectrum)
        }
    }

    init {
        CoverImageStorage.init(application, UnsafeHttpClient.createUnsafeOkHttpClient())
    }

    fun init(intent: Intent) {
        // 获取传入参数
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
            return
        }

        // 初始化后台服务管理器（如果尚未初始化）
        if (!::audioBackgroundManager.isInitialized) {
            audioBackgroundManager = AudioBackgroundManager(getApplication())
            audioBackgroundManager.addPlaybackListener(this)
            audioBackgroundManager.addProgressListener(this)
            // 频谱监听器将在 onActivityResume 中添加，避免后台高频更新
        }

        // 初始化歌词管理器（若未初始化）
        if (!::lyricsManager.isInitialized) {
            lyricsManager = LyricsManager(getApplication(), handler, scope)
            lyricsManager.setListener(this)
        }

        // 初始化元数据管理器
        if (!::metadataManager.isInitialized) {
            metadataManager = SongMetadataManager(getApplication(), FileServerService(getApplication()))
        }

        // 保存播放数据
        currentTrack = track
        playlist = tracks ?: listOf(track)
        currentIndex = index.coerceIn(0, playlist.size - 1)

        // 检查服务是否已在运行且播放同一首歌，若是则仅绑定同步状态，避免重置播放
        if (audioBackgroundManager.isServiceRunning() &&
            audioBackgroundManager.getCurrentTrack()?.url == track.url) {
            // 服务已在运行，仅同步UI状态
            applyPlayMode(currentPlayMode)
            updateTrackInfo(track)
            loadCoverAndMetadata()
            lyricsManager.loadLyrics(serverUrl, songPath, track.name)
            refreshPositionImmediately()
        } else {
            // 启动或更新服务
            audioBackgroundManager.startService(track, ArrayList(playlist), currentIndex)
            applyPlayMode(currentPlayMode)
            updateTrackInfo(track)
            loadCoverAndMetadata()
            lyricsManager.loadLyrics(serverUrl, songPath, track.name)
            audioBackgroundManager.bindService()
        }
    }

    private fun applyPlayMode(mode: Int) {
        when (mode) {
            PlaylistDetailActivity.MODE_LIST -> {
                audioBackgroundManager.setRepeatMode(RepeatMode.ALL)
                audioBackgroundManager.setShuffleEnabled(false)
            }
            PlaylistDetailActivity.MODE_SINGLE -> {
                audioBackgroundManager.setRepeatMode(RepeatMode.ONE)
                audioBackgroundManager.setShuffleEnabled(false)
            }
            PlaylistDetailActivity.MODE_RANDOM -> {
                audioBackgroundManager.setRepeatMode(RepeatMode.ALL)
                audioBackgroundManager.setShuffleEnabled(true)
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
                    CoverImageStorage.downloadCover(trackId, baseCoverUrl, scope) { file ->
                        if (file != null) coverLocalPath.postValue(file.absolutePath)
                    }
                }
            } catch (e: Exception) {
                artistAlbum.value = currentTrack?.name ?: "未知"
                coverLocalPath.postValue(null)
            }
        }
    }

    fun updateIntent(intent: Intent) {
        val newTrack = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_TRACK", AudioTrack::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("AUDIO_TRACK")
        } ?: return

        val newIndex = intent.getIntExtra("CURRENT_INDEX", 0)
        val newPlayMode = intent.getIntExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, PlaylistDetailActivity.MODE_LIST)
        val newServerUrl = intent.getStringExtra("SERVER_URL") ?: ""

        if (newTrack.path == songPath && currentTrack?.path == songPath) {
            if (currentPlayMode != newPlayMode) {
                currentPlayMode = newPlayMode
                applyPlayMode(currentPlayMode)
            }
            refreshPositionImmediately()
            return
        }

        currentTrack = newTrack
        currentIndex = newIndex.coerceIn(0, playlist.size - 1)
        currentPlayMode = newPlayMode
        songPath = newTrack.path
        serverUrl = newServerUrl

        updateTrackInfo(newTrack)
        loadCoverAndMetadata()
        lyricsManager.loadLyrics(serverUrl, songPath, newTrack.name)
        applyPlayMode(currentPlayMode)

        if (audioBackgroundManager.getCurrentTrack()?.path != songPath) {
            audioBackgroundManager.startService(newTrack, ArrayList(playlist), currentIndex)
        }
    }

    // ---------- 播放控制 ----------
    fun togglePlayback() {
        audioBackgroundManager.sendAction(AudioPlaybackService.ACTION_PLAY_PAUSE)
    }

    fun playNext() {
        audioBackgroundManager.safePlayNext()
    }

    fun playPrevious() {
        audioBackgroundManager.safePlayPrevious()
    }

    fun seekTo(position: Long) {
        latestPosition = position
        currentPosition.value = position
        audioBackgroundManager.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed.value = speed
        audioBackgroundManager.setPlaybackSpeed(speed)
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
        // 移除频谱监听器以停止后台高频更新，节省电量和CPU
        if (::audioBackgroundManager.isInitialized) {
            audioBackgroundManager.removeSpectrumListener(spectrumListener)
        }
    }

    fun onActivityResume() {
        // 重新绑定并添加频谱监听器
        if (::audioBackgroundManager.isInitialized) {
            audioBackgroundManager.bindService()
            audioBackgroundManager.addSpectrumListener(spectrumListener)
        }
        refreshPositionImmediately()
    }

    fun release() {
        if (::audioBackgroundManager.isInitialized) {
            audioBackgroundManager.removePlaybackListener(this)
            audioBackgroundManager.removeProgressListener(this)
            audioBackgroundManager.removeSpectrumListener(spectrumListener)
            audioBackgroundManager.unbindService()
        }
        scope.cancel()
    }

    fun refreshPositionImmediately() {
        if (!::audioBackgroundManager.isInitialized) return
        val status = audioBackgroundManager.getPlaybackStatus()
        if (status != null && status.duration > 0) {
            latestPosition = status.position
            latestDuration = status.duration
            currentPosition.postValue(status.position)
            duration.postValue(status.duration)
            isPlaying.postValue(status.isPlaying)
            playbackState.postValue(status.state)
        }
    }

    // ---------- AudioPlaybackListener 实现 ----------
    override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {
        isPlaying.postValue(status.isPlaying)
        playbackState.postValue(status.state)
        if (status.errorMessage != null) errorMessage.postValue(status.errorMessage)
    }

    override fun onTrackChanged(track: AudioTrack, index: Int) {
        currentTrack = track
        currentIndex = index
        songPath = track.path
        updateTrackInfo(track)
        loadCoverAndMetadata()
        lyricsManager.loadLyrics(serverUrl, songPath, track.name)
    }

    override fun onPlaybackError(error: String) {
        errorMessage.postValue(error)
    }

    override fun onPlaybackEnded() {
        // 服务会自动处理下一首
    }

    override fun onAudioBuffering(isBuffering: Boolean) {
        playbackState.postValue(if (isBuffering) PlaybackState.BUFFERING else PlaybackState.PLAYING)
    }

    // ---------- AudioProgressListener 实现 ----------
    override fun onProgressUpdated(position: Long, duration: Long) {
        latestPosition = position
        latestDuration = duration
        currentPosition.postValue(position)
        this.duration.postValue(duration)
        prefs.edit().putLong("last_position", position).putLong("last_duration", duration).apply()
    }

    override fun onBufferingProgress(percent: Int) {}

    // ---------- 歌词相关接口 ----------
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

    override fun getCurrentTime(): Long = latestPosition
    override fun isPlaying(): Boolean = isPlaying.value ?: false

    override fun onCleared() {
        if (::audioBackgroundManager.isInitialized) {
            audioBackgroundManager.removePlaybackListener(this)
            audioBackgroundManager.removeProgressListener(this)
            audioBackgroundManager.removeSpectrumListener(spectrumListener)
            audioBackgroundManager.unbindService()
        }
        scope.cancel()
        super.onCleared()
    }
}