package com.dkc.fileserverclient

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.io.File
import java.net.URLEncoder


class AudioPlayerViewModel(application: Application) : AndroidViewModel(application),
    LyricsManager.LyricsStateListener, LyricsManager.TimeProvider, LyricsManager.PlayStateProvider {

    val currentTrackName = MutableLiveData<String>()
    val artistAlbum = MutableLiveData<String>()
    val coverLocalPath = MutableLiveData<String?>()
    val isPlaying = MutableLiveData<Boolean>()
    val playbackState = MutableLiveData<PlaybackState>()

    private val prefs: SharedPreferences =
        getApplication<Application>().getSharedPreferences("audio_cache", Context.MODE_PRIVATE)

    val currentPosition = MutableLiveData(0L)
    val duration = MutableLiveData(0L)
    val currentLyricsLine = MutableLiveData<String?>()
    val nextLyricsLine = MutableLiveData<String?>()
    val errorMessage = MutableLiveData<String?>()
    val playbackSpeed = MutableLiveData<Float>(1.0f)
    val currentSongMetadata = MutableLiveData<SongMetadata?>()
    val lyricsFileSelection = MutableLiveData<List<FileServerService.LyricsFileInfo>?>()
    val finishEvent = MutableLiveData<Boolean>()

    private var currentTrack: AudioTrack? = null
    private var serverUrl = ""
    private var songPath = ""
    private val trackList = mutableListOf<AudioTrack>()

    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 待执行操作队列
    private data class PendingPlaybackChange(
        val track: AudioTrack,
        val trackList: List<AudioTrack>,
        val startIndex: Int
    )
    private var pendingChange: PendingPlaybackChange? = null

    private val progressUpdater = object : Runnable {
        override fun run() {
            val controller = mediaController ?: return
            if (controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                val pos = controller.currentPosition
                val dur = controller.duration
                if (dur > 0) {
                    currentPosition.postValue(pos)
                    duration.postValue(dur)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private lateinit var lyricsManager: LyricsManager
    private lateinit var metadataManager: SongMetadataManager

    init {
        val app = getApplication<Application>()
        CoverImageStorage.init(app, UnsafeHttpClient.createUnsafeOkHttpClient())
        lyricsManager = LyricsManager(app, handler, scope)
        metadataManager = SongMetadataManager(app, FileServerService(app))
    }

    fun init(intent: Intent) {
        val track: AudioTrack? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_TRACK", AudioTrack::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("AUDIO_TRACK")
        }

        val tracks: ArrayList<AudioTrack>? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("AUDIO_TRACKS", AudioTrack::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableArrayListExtra("AUDIO_TRACKS")
            }

        val index = intent.getIntExtra("CURRENT_INDEX", 0)
        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        songPath = intent.getStringExtra("FILE_PATH") ?: track?.path ?: ""

        if (track == null) {
            errorMessage.value = "无法获取音频信息"
            return
        }

        currentTrack = track
        trackList.clear()
        trackList.addAll(tracks ?: listOf(track))

        prefs.edit().putString("server_url", serverUrl).apply()

        // ↓ 修改点：无条件设置监听器（即使 LyricsManager 已初始化）
        lyricsManager.setListener(this)
        metadataManager = SongMetadataManager(getApplication(), FileServerService(getApplication()))

        connectToMediaService(index)
    }

    // ======================= 修改后的 connectToMediaService =======================
    private fun connectToMediaService(startIndex: Int) {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))

        controllerFuture = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    finishEvent.postValue(true)
                }
            })
            .buildAsync()

        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)

                val currentMediaId = mediaController?.currentMediaItem?.mediaId
                val targetTrack = trackList.getOrNull(startIndex)

                // 情况1：已有播放且点击的是当前歌曲 -> 仅更新 UI，不重置播放
                if (currentMediaId != null && targetTrack != null && currentMediaId == targetTrack.path) {
                    Log.d("AudioPlayerVM", "Same track clicked, skip re-set")
                    currentTrack = targetTrack
                    songPath = targetTrack.path
                    updateTrackInfo(targetTrack)
                    loadCoverAndMetadata()
                    lyricsManager.loadLyrics(serverUrl, songPath, targetTrack.name)
                    // 不调用 setMediaItems，保持进度不变
                } else {
                    // 情况2：无播放或点击不同歌曲 -> 正常切换
                    if (trackList.isNotEmpty()) {
                        val mediaItems = trackList.map { it.toMediaItem() }
                        mediaController?.setMediaItems(mediaItems, startIndex, 0L)
                        mediaController?.play()
                    }
                }

                // 处理在连接过程中积压的切歌请求（比如 updateIntent 在连接完成前调用）
                pendingChange?.let { change ->
                    executePlaybackChange(change.track, change.trackList, change.startIndex)
                    pendingChange = null
                }

                handler.post(progressUpdater)
            } catch (e: Exception) {
                errorMessage.postValue("连接媒体服务失败")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ======================= 修改后的 executePlaybackChange =======================
    private fun executePlaybackChange(track: AudioTrack, tracks: List<AudioTrack>, startIndex: Int) {
        val currentMediaId = mediaController?.currentMediaItem?.mediaId

        // 如果当前播放的就是这首歌，只刷新 UI，不重新 setMediaItems
        if (currentMediaId == track.path) {
            currentTrack = track
            songPath = track.path
            updateTrackInfo(track)
            loadCoverAndMetadata()
            lyricsManager.loadLyrics(serverUrl, songPath, track.name)
            return
        }

        // 否则正常切换
        currentTrack = track
        songPath = track.path
        trackList.clear()
        trackList.addAll(tracks)

        updateTrackInfo(track)
        loadCoverAndMetadata()
        lyricsManager.loadLyrics(serverUrl, songPath, track.name)

        val mediaItems = trackList.map { it.toMediaItem() }
        mediaController?.setMediaItems(mediaItems, startIndex, 0L)
        mediaController?.play()
    }

    /**
     * 转换为 MediaItem，使用 path 作为 mediaId 并直接构建流地址
     */
    private fun AudioTrack.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(name ?: "")
            .setArtist(artist ?: "")
            .setAlbumTitle(album ?: "")
            .build()

        val mediaId = path
        val uri = try {
            val encodedPath = mediaId.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
            Uri.parse("$serverUrl/api/fileserver/stream/$encodedPath")
        } catch (e: Exception) {
            Log.e("AudioPlayerVM", "Failed to build URI for $mediaId", e)
            Uri.EMPTY
        }

        Log.d("AudioPlayerVM", "toMediaItem: mediaId=$mediaId, uri=$uri")
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@AudioPlayerViewModel.isPlaying.postValue(isPlaying)
            if (!isPlaying) saveRecoveryInfo()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                val track = findTrackByMediaId(mediaItem.mediaId)
                if (track != null) {
                    currentTrack = track
                    songPath = track.path
                    updateTrackInfo(track)
                    loadCoverAndMetadata()
                    lyricsManager.loadLyrics(serverUrl, songPath, track.name)
                    saveRecoveryInfo()
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> playbackState.postValue(PlaybackState.BUFFERING)
                Player.STATE_READY -> playbackState.postValue(PlaybackState.PLAYING)
                Player.STATE_ENDED -> { /* 自动下一首由服务处理 */ }
                Player.STATE_IDLE -> playbackState.postValue(PlaybackState.STOPPED)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorMessage.postValue(error.message)
        }
    }

    private fun findTrackByMediaId(mediaId: String): AudioTrack? {
        return trackList.firstOrNull { it.path == mediaId }
    }

    private fun updateTrackInfo(track: AudioTrack) {
        currentTrackName.value = track.name
    }

    private var currentCoverUrl: String? = null

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
        val newTrack: AudioTrack = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_TRACK", AudioTrack::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("AUDIO_TRACK")
        } ?: return

        // 如果切的是同一首歌，只刷新进度
        val newFilePath = intent.getStringExtra("FILE_PATH") ?: newTrack.path
        if (newFilePath == songPath && currentTrack?.path == songPath) {
            refreshPositionImmediately()
            return
        }

        // 更新服务器地址
        val newServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        serverUrl = newServerUrl
        prefs.edit().putString("server_url", serverUrl).apply()

        // 提取播放列表和索引
        val tracks = intent.getParcelableArrayListExtra<AudioTrack>("AUDIO_TRACKS") ?: arrayListOf(newTrack)
        val index = intent.getIntExtra("CURRENT_INDEX", 0)

        // 判断控制器是否就绪
        if (mediaController == null) {
            // 控制器尚未连接，保存请求
            pendingChange = PendingPlaybackChange(newTrack, tracks, index)
        } else {
            // 控制器已就绪，直接执行切歌
            executePlaybackChange(newTrack, tracks, index)
        }
    }

    fun togglePlayback() { mediaController?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun playNext() { mediaController?.seekToNextMediaItem() }
    fun playPrevious() { mediaController?.seekToPreviousMediaItem() }
    fun seekTo(position: Long) { mediaController?.seekTo(position) }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed.value = speed
        mediaController?.setPlaybackSpeed(speed)
    }

    fun setPlayMode(mode: Int) {
        val controller = mediaController ?: return
        when (mode) {
            PlaylistDetailActivity.MODE_LIST -> {
                controller.repeatMode = Player.REPEAT_MODE_ALL
                controller.shuffleModeEnabled = false
            }
            PlaylistDetailActivity.MODE_SINGLE -> {
                controller.repeatMode = Player.REPEAT_MODE_ONE
                controller.shuffleModeEnabled = false
            }
            PlaylistDetailActivity.MODE_RANDOM -> {
                controller.repeatMode = Player.REPEAT_MODE_ALL
                controller.shuffleModeEnabled = true
            }
        }
    }

    fun reloadLyrics() { lyricsManager.loadLyrics(serverUrl, songPath, currentTrack?.name ?: "") }

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
                currentCoverUrl?.let { url -> CoverImageStorage.deleteLocalFile(trackId, url) }
                currentCoverUrl = null
                loadCoverAndMetadata()
                Toast.makeText(getApplication(), "已删除封面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onActivityPause() {
        handler.removeCallbacks(progressUpdater)
        saveRecoveryInfo()
    }

    fun onActivityResume() {
        handler.post(progressUpdater)
        refreshPositionImmediately()
    }

    fun refreshPositionImmediately() {
        val controller = mediaController ?: return
        if (controller.duration > 0) {
            currentPosition.value = controller.currentPosition
            duration.value = controller.duration
            isPlaying.value = controller.isPlaying
        }
    }

    fun release() {
        saveRecoveryInfo()
        mediaController?.removeListener(playerListener)
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture!!)
        }
        mediaController = null
        handler.removeCallbacks(progressUpdater)
        scope.cancel()
    }

    private fun saveRecoveryInfo() {
        val controller = mediaController ?: return
        val ids = (0 until controller.mediaItemCount).mapNotNull { i ->
            controller.getMediaItemAt(i).mediaId
        }
        prefs.edit()
            .putString("last_playlist", ids.joinToString("|||"))
            .putInt("last_index", controller.currentMediaItemIndex)
            .putLong("last_position", controller.currentPosition)
            .putLong("last_duration", controller.duration)
            .putString("server_url", serverUrl)
            .apply()
    }

    // LyricsManager 回调
    override fun onLyricsLoaded(data: LyricsData?, title: String?) { lyricsManager.startLyricsUpdates() }
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
        release()
        super.onCleared()
    }
}