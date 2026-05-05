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
    LyricsManager.LyricsStateListener,       // 直接处理歌词回调
    LyricsManager.TimeProvider,             // 提供当前播放时间
    LyricsManager.PlayStateProvider {

    // 核心组件
    private lateinit var mediaPlaybackController: MediaPlaybackController
    private lateinit var metadataManager: SongMetadataManager
    internal lateinit var lyricsManager: LyricsManager
    private var audioBackgroundManager: AudioBackgroundManager? = null

    // LiveData
    val currentTrackName = MutableLiveData<String>()
    val artistAlbum = MutableLiveData<String>()
    val coverUrl = MutableLiveData<String?>()
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

    private var serverUrl = ""
    private var songPath = ""
    private var currentTrack: AudioTrack? = null
    private var playlist: List<AudioTrack> = emptyList()
    private var currentIndex = -1

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(intent: Intent) {
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

        if (track == null) {
            errorMessage.value = "无法获取音频信息"
            return
        }

        // 初始化播放控制器
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

        // 歌词管理器（必须在加载歌词前设置监听器）
        lyricsManager = LyricsManager(getApplication(), handler, scope)
        lyricsManager.setListener(this)   // ViewModel 自身作为监听器

        // 元数据管理器
        metadataManager = SongMetadataManager(getApplication(), FileServerService(getApplication()))

        // 连接后台服务
        audioBackgroundManager = AudioBackgroundManager(getApplication())
        audioBackgroundManager?.bindService()

        // 播放
        currentTrack = track
        playlist = tracks ?: listOf(track)
        currentIndex = index.coerceIn(0, playlist.size - 1)

        val mediaItem = MediaPlaybackItem.fromAudioTrack(track)
        mediaPlaybackController.play(mediaItem, playlist.map { MediaPlaybackItem.fromAudioTrack(it) }, currentIndex)

        updateTrackInfo(track)
        loadCoverAndMetadata()
        lyricsManager.loadLyrics(serverUrl, songPath, track.name)
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
                val cover = if (metadata?.hasCover == true) {
                    metadataManager.getCoverUrl(serverUrl, songPath, addTimestamp = true)
                } else null
                coverUrl.value = cover
            } catch (e: Exception) {
                artistAlbum.value = currentTrack?.name ?: "未知"
                coverUrl.value = null
            }
        }
    }

    // 播放控制
    fun togglePlayback() = mediaPlaybackController.togglePlayback()
    fun playNext() = mediaPlaybackController.playNext()
    fun playPrevious() = mediaPlaybackController.playPrevious()
    fun seekTo(position: Long) = mediaPlaybackController.seekTo(position)
    fun setPlaybackSpeed(speed: Float) {
        mediaPlaybackController.setPlaybackSpeed(speed)
        playbackSpeed.value = speed
    }

    // 歌词操作
    fun reloadLyrics() {
        lyricsManager.loadLyrics(serverUrl, songPath, currentTrack?.name ?: "")
    }

    fun markAsNoLyrics() {
        scope.launch {
            val ok = lyricsManager.markNoLyrics(serverUrl, songPath)
            if (ok) {
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

    // 元数据编辑
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

    // 封面上传
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
                loadCoverAndMetadata()
                Toast.makeText(getApplication(), "已删除封面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 生命周期
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
        audioBackgroundManager?.unbindService()
        scope.cancel()
    }

    // MediaPlaybackListener
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

    override fun onPlaybackEnded() {
        // 由控制器处理列表循环
    }

    override fun onMediaBuffering(isBuffering: Boolean) {
        playbackState.value = if (isBuffering) PlaybackState.BUFFERING else PlaybackState.PLAYING
    }

    // MediaProgressListener
    override fun onProgressUpdated(position: Long, duration: Long) {
        currentPosition.value = position
        this.duration.value = duration
    }

    override fun onBufferingProgress(percent: Int) {}

    // ========== LyricsStateListener 实现 ==========
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

    // TimeProvider / PlayStateProvider
    override fun getCurrentTime(): Long = currentPosition.value ?: 0L
    override fun isPlaying(): Boolean = isPlaying.value ?: false

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}