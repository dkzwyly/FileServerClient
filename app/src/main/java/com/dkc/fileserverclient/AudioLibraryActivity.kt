@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*

class AudioLibraryActivity : AppCompatActivity() {

    private lateinit var audioRecyclerView: RecyclerView
    private lateinit var playlistRecyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var modeSwitchButton: ImageButton
    private lateinit var searchIconButton: ImageButton
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: ImageButton
    private lateinit var closeSearchButton: ImageButton
    private lateinit var searchContainer: View
    private lateinit var playlistTab: TextView
    private lateinit var songsTab: TextView
    private lateinit var tabContainer: View
    private lateinit var addPlaylistButton: FloatingActionButton

    private val fileServerService by lazy { FileServerService(this) }
    private lateinit var metadataManager: SongMetadataManager

    private val audioFileItems = mutableListOf<FileSystemItem>()
    private val audioTracks = mutableListOf<AudioTrack>()
    private val filteredAudioTracks = mutableListOf<AudioTrack>()
    private val playlistList = mutableListOf<Playlist>()

    private lateinit var audioAdapter: AudioLibraryAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var currentServerUrl = ""
    private var currentTab: TabType = TabType.SONGS

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val audioLibraryPath = "data/音乐"

    private var currentPlayMode: Int = PlaylistDetailActivity.MODE_LIST

    // 播放服务相关
    private var playbackService: AudioPlaybackService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as AudioPlaybackService.AudioServiceBinder).getService()
            playbackService?.addPlaybackListener(playbackListener)
            val currentTrack = playbackService?.getCurrentTrack()
            audioAdapter.setCurrentlyPlaying(currentTrack?.id)
            currentTrack?.let {
                val position = audioAdapter.getPositionByTrackId(it.id)
                if (position != -1 && currentTab == TabType.SONGS) {
                    scrollToCenter(position)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.removePlaybackListener(playbackListener)
            playbackService = null
            isBound = false
        }
    }

    private val playbackListener = object : AudioPlaybackListener {
        override fun onTrackChanged(track: AudioTrack, index: Int) {
            runOnUiThread {
                audioAdapter.setCurrentlyPlaying(track.id)
                if (currentTab == TabType.SONGS) {
                    val position = audioAdapter.getPositionByTrackId(track.id)
                    if (position != -1) {
                        scrollToCenter(position)
                    }
                }
            }
        }

        override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {}
        override fun onPlaybackError(error: String) {}
        override fun onPlaybackEnded() {}
        override fun onAudioBuffering(isBuffering: Boolean) {}
    }

    private enum class TabType { PLAYLISTS, SONGS }

    companion object {
        private const val TAG = "AudioLibraryActivity"
        private const val PREFS_NAME = "audio_library_play_mode"
        private const val KEY_MODE = "current_play_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_library)

        supportActionBar?.hide()

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }
        CoverImageStorage.init(this, UnsafeHttpClientHolder.client)

        metadataManager = SongMetadataManager(this, fileServerService)
        PlaylistManager.initialize(this)

        loadPlayMode()

        initViews()
        setupTabs()
        loadAudios()
        loadPlaylists()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioPlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        playbackService?.removePlaybackListener(playbackListener)
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists()
    }

    // ---------- 播放模式相关 ----------
    private fun loadPlayMode() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentPlayMode = prefs.getInt(KEY_MODE, PlaylistDetailActivity.MODE_LIST)
    }

    private fun savePlayMode() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MODE, currentPlayMode)
            .apply()
    }

    private fun updateModeIcon() {
        val iconRes = when (currentPlayMode) {
            PlaylistDetailActivity.MODE_LIST -> R.drawable.ic_repeat_all
            PlaylistDetailActivity.MODE_SINGLE -> R.drawable.ic_repeat_one
            PlaylistDetailActivity.MODE_RANDOM -> R.drawable.ic_shuffle
            else -> R.drawable.ic_repeat_all
        }
        modeSwitchButton.setImageResource(iconRes)
    }

    private fun switchPlayMode() {
        currentPlayMode = when (currentPlayMode) {
            PlaylistDetailActivity.MODE_LIST -> PlaylistDetailActivity.MODE_SINGLE
            PlaylistDetailActivity.MODE_SINGLE -> PlaylistDetailActivity.MODE_RANDOM
            else -> PlaylistDetailActivity.MODE_LIST
        }
        savePlayMode()
        updateModeIcon()
        val modeName = when (currentPlayMode) {
            PlaylistDetailActivity.MODE_LIST -> "列表循环"
            PlaylistDetailActivity.MODE_SINGLE -> "单曲循环"
            else -> "随机播放"
        }
        Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
    }

    private fun initViews() {
        audioRecyclerView = findViewById(R.id.audioRecyclerView)
        playlistRecyclerView = findViewById(R.id.playlistRecyclerView)
        statusText = findViewById(R.id.audioStatusText)
        titleText = findViewById(R.id.audioTitleText)
        backButton = findViewById(R.id.backButton)
        modeSwitchButton = findViewById(R.id.modeSwitchButton)
        searchIconButton = findViewById(R.id.searchIconButton)
        searchEditText = findViewById(R.id.searchEditText)
        clearSearchButton = findViewById(R.id.clearSearchButton)
        closeSearchButton = findViewById(R.id.closeSearchButton)
        searchContainer = findViewById(R.id.searchContainer)
        playlistTab = findViewById(R.id.playlistTab)
        songsTab = findViewById(R.id.songsTab)
        tabContainer = findViewById(R.id.tabContainer)
        addPlaylistButton = findViewById(R.id.addPlaylistButton)

        titleText.text = "音频库"

        backButton.setOnClickListener { finish() }
        modeSwitchButton.setOnClickListener { switchPlayMode() }
        searchIconButton.setOnClickListener { showSearchContainer() }
        closeSearchButton.setOnClickListener { hideSearchContainer() }
        setupSearch()

        updateModeIcon()

        audioRecyclerView.layoutManager = LinearLayoutManager(this)
        playlistRecyclerView.layoutManager = LinearLayoutManager(this)

        audioAdapter = AudioLibraryAdapter(
            currentServerUrl,
            filteredAudioTracks,
            onAudioClick = { playAudio(it) },
            onAudioLongClick = { showAddToPlaylistDialog(it) },
            lifecycleScope = coroutineScope,
            resources = resources
        )
        audioRecyclerView.adapter = audioAdapter

        playlistAdapter = PlaylistAdapter(
            playlists = playlistList,
            onPlaylistClick = { playlist ->
                val intent = Intent(this, PlaylistDetailActivity::class.java).apply {
                    putExtra("PLAYLIST_ID", playlist.id)
                    putExtra("SERVER_URL", currentServerUrl)
                }
                startActivity(intent)
            },
            onRenameClick = { showRenamePlaylistDialog(it) },
            onDeleteClick = { showDeletePlaylistDialog(it) }
        )
        playlistRecyclerView.adapter = playlistAdapter

        addPlaylistButton.setOnClickListener { showCreatePlaylistDialog() }
    }

    private fun setupTabs() {
        songsTab.setOnClickListener { switchToTab(TabType.SONGS) }
        playlistTab.setOnClickListener { switchToTab(TabType.PLAYLISTS) }
    }

    private fun loadPlaylists() {
        playlistList.clear()
        playlistList.addAll(PlaylistManager.getAllPlaylists())
        updatePlaylistUI()
    }

    private fun switchToTab(tabType: TabType) {
        currentTab = tabType
        when (tabType) {
            TabType.PLAYLISTS -> {
                playlistTab.setBackgroundResource(R.drawable.tab_background_selected)
                songsTab.setBackgroundResource(R.drawable.tab_background)
                playlistTab.setTextColor(getColor(R.color.primary_color))
                songsTab.setTextColor(getColor(R.color.text_primary))

                playlistRecyclerView.visibility = View.VISIBLE
                audioRecyclerView.visibility = View.GONE
                addPlaylistButton.visibility = View.VISIBLE

                updatePlaylistUI()
                if (searchContainer.visibility == View.VISIBLE) {
                    searchEditText.hint = "搜索歌单..."
                    performSearch(searchEditText.text.toString())
                }
            }
            TabType.SONGS -> {
                playlistTab.setBackgroundResource(R.drawable.tab_background)
                songsTab.setBackgroundResource(R.drawable.tab_background_selected)
                playlistTab.setTextColor(getColor(R.color.text_primary))
                songsTab.setTextColor(getColor(R.color.primary_color))

                audioRecyclerView.visibility = View.VISIBLE
                playlistRecyclerView.visibility = View.GONE
                addPlaylistButton.visibility = View.GONE

                if (audioTracks.isEmpty()) statusText.text = "没有找到音频文件"
                else statusText.text = "共找到 ${audioTracks.size} 个音频文件"

                if (searchContainer.visibility == View.VISIBLE) {
                    searchEditText.hint = "搜索音频..."
                    performSearch(searchEditText.text.toString())
                }

                playbackService?.getCurrentTrack()?.let { track ->
                    val position = audioAdapter.getPositionByTrackId(track.id)
                    if (position != -1) {
                        scrollToCenter(position)
                    }
                }
            }
        }
    }

    private fun showAddToPlaylistDialog(audioTrack: AudioTrack) {
        val playlists = PlaylistManager.getAllPlaylists()
        if (playlists.isEmpty()) {
            showToast("请先创建歌单")
            return
        }
        val playlistNames = playlists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("添加到歌单")
            .setItems(playlistNames) { _, which ->
                val selectedPlaylist = playlists[which]
                val added = PlaylistManager.addTrackToPlaylist(selectedPlaylist.id, audioTrack)
                if (added) {
                    showToast("已添加到歌单 \"${selectedPlaylist.name}\"")
                    loadPlaylists()
                } else {
                    showToast("歌曲已存在于该歌单")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: Playlist) {
        val input = EditText(this)
        input.setText(playlist.name)
        AlertDialog.Builder(this)
            .setTitle("重命名歌单")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != playlist.name) {
                    if (PlaylistManager.renamePlaylist(playlist.id, newName)) {
                        loadPlaylists()
                        showToast("歌单已重命名")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeletePlaylistDialog(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("删除歌单")
            .setMessage("确定要删除歌单 \"${playlist.name}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                PlaylistManager.deletePlaylist(playlist.id)
                loadPlaylists()
                showToast("歌单已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSearchContainer() {
        searchContainer.visibility = View.VISIBLE
        searchIconButton.visibility = View.GONE
        searchEditText.hint = if (currentTab == TabType.PLAYLISTS) "搜索歌单..." else "搜索音频..."
        searchEditText.requestFocus()
    }

    private fun hideSearchContainer() {
        searchContainer.visibility = View.GONE
        searchIconButton.visibility = View.VISIBLE
        searchEditText.setText("")
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
        performSearch("")
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        clearSearchButton.setOnClickListener { searchEditText.setText("") }
        clearSearchButton.visibility = View.GONE
    }

    private fun performSearch(query: String) {
        val searchQuery = query.trim()
        clearSearchButton.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE

        when (currentTab) {
            TabType.SONGS -> {
                filteredAudioTracks.clear()
                filteredAudioTracks.addAll(if (searchQuery.isEmpty()) audioTracks else audioTracks.filter { track ->
                    track.name.contains(searchQuery, true) ||
                            (track.title?.contains(searchQuery, true) == true) ||
                            (track.artist?.contains(searchQuery, true) == true) ||
                            (track.album?.contains(searchQuery, true) == true)
                })
                audioAdapter.updateData(filteredAudioTracks)
                statusText.text = if (searchQuery.isNotEmpty()) "找到 ${filteredAudioTracks.size} 个匹配的音频文件"
                else "共找到 ${audioTracks.size} 个音频文件"

                playbackService?.getCurrentTrack()?.let { track ->
                    val position = audioAdapter.getPositionByTrackId(track.id)
                    if (position != -1) {
                        audioAdapter.setCurrentlyPlaying(track.id)
                        scrollToCenter(position)
                    } else {
                        audioAdapter.setCurrentlyPlaying(null)
                    }
                }
            }
            TabType.PLAYLISTS -> {
                val filtered = if (searchQuery.isEmpty()) playlistList else playlistList.filter { it.name.contains(searchQuery, true) }
                playlistAdapter.updateData(filtered)
                statusText.text = if (searchQuery.isNotEmpty()) "找到 ${filtered.size} 个匹配的歌单" else "共 ${playlistList.size} 个歌单"
            }
        }
    }

    private fun loadAudios() {
        coroutineScope.launch {
            statusText.text = "正在加载音频..."
            try {
                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, audioLibraryPath)
                }
                audioFileItems.clear()
                audioFileItems.addAll(allItems.filter { !it.isDirectory && AudioUtils.isAudioFile(it) })

                val tracks = audioFileItems.map { item ->
                    AudioTrack.fromFileSystemItem(item, currentServerUrl)
                }

                val metadataMap = withContext(Dispatchers.IO) {
                    metadataManager.getBatchMetadata(currentServerUrl, tracks.map { it.path })
                }

                val updatedTracks = tracks.map { track ->
                    val encodedPath = java.net.URLEncoder.encode(track.path, "UTF-8")
                    val meta = metadataMap[encodedPath]
                    if (meta != null) {
                        track.copy(
                            title = meta.title.ifEmpty { track.title },
                            artist = meta.artist.ifEmpty { track.artist },
                            album = meta.album.ifEmpty { track.album },
                            coverUrl = if (meta.hasCover) {
                                metadataManager.getCoverUrl(currentServerUrl, track.path, addTimestamp = false)
                            } else null
                        )
                    } else track
                }

                audioTracks.clear()
                audioTracks.addAll(updatedTracks)
                filteredAudioTracks.clear()
                filteredAudioTracks.addAll(audioTracks)

                audioAdapter.updateData(filteredAudioTracks)
                statusText.text = if (audioTracks.isEmpty()) "没有找到音频文件" else "共找到 ${audioTracks.size} 个音频文件"

                playbackService?.getCurrentTrack()?.let { track ->
                    val position = audioAdapter.getPositionByTrackId(track.id)
                    if (position != -1) {
                        audioAdapter.setCurrentlyPlaying(track.id)
                        scrollToCenter(position)
                    }
                }
            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载音频异常", e)
            }
        }
    }

    private fun updatePlaylistUI() {
        if (playlistList.isEmpty()) statusText.text = "暂无歌单，点击右下角按钮创建"
        else statusText.text = "共 ${playlistList.size} 个歌单"
        playlistAdapter.updateData(playlistList)
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(this)
        input.hint = "输入歌单名称"
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val newPlaylist = PlaylistManager.createPlaylist(name)
                    playlistList.add(newPlaylist)
                    updatePlaylistUI()
                    showToast("歌单 '$name' 创建成功")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun playAudio(audioTrack: AudioTrack) {
        try {
            val audioTracksList = AudioUtils.convertToAudioTracks(audioFileItems, currentServerUrl)
            val currentIndex = audioTracks.indexOfFirst { it.id == audioTrack.id }.takeIf { it >= 0 } ?: 0

            val intent = Intent(this, AudioPlayerActivity::class.java).apply {
                putExtra("FILE_NAME", audioTrack.name)
                putExtra("FILE_URL", audioTrack.url)
                putExtra("FILE_TYPE", "audio")
                putExtra("FILE_PATH", audioTrack.path)
                putExtra("AUDIO_TRACK", audioTrack)
                putExtra("AUDIO_TRACKS", ArrayList(audioTracksList))
                putExtra("CURRENT_INDEX", currentIndex)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("FROM_MUSIC_LIBRARY", true)
                putExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, currentPlayMode)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败", e)
        }
    }

    private fun getDirectoryFromPath(filePath: String): String {
        return try {
            java.io.File(filePath).parent ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 滚动到列表指定位置并使其偏上显示（例如屏幕高度的 35% 处）
     */
    private fun scrollToCenter(position: Int) {
        val layoutManager = audioRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val targetView = layoutManager.findViewByPosition(position)
        // 可调整偏上的比例，值越小越靠上，0.35 表示顶部在屏幕 35% 处
        val targetTopRatio = 0.35f
        if (targetView != null) {
            val itemHeight = targetView.height
            val recyclerHeight = audioRecyclerView.height
            val targetTop = (recyclerHeight * targetTopRatio).toInt()
            val offset = targetTop - (itemHeight / 2)
            val currentTop = targetView.top
            val delta = currentTop - offset
            audioRecyclerView.smoothScrollBy(0, delta)
        } else {
            layoutManager.scrollToPositionWithOffset(position, 0)
            audioRecyclerView.post {
                val newTargetView = layoutManager.findViewByPosition(position)
                newTargetView?.let {
                    val itemHeight = it.height
                    val recyclerHeight = audioRecyclerView.height
                    val targetTop = (recyclerHeight * targetTopRatio).toInt()
                    val offset = targetTop - (itemHeight / 2)
                    val currentTop = it.top
                    val delta = currentTop - offset
                    audioRecyclerView.smoothScrollBy(0, delta)
                }
            }
        }
    }
}