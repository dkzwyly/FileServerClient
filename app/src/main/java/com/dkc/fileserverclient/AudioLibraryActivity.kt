@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
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
    private val audioList = mutableListOf<FileSystemItem>()
    private val filteredAudioList = mutableListOf<FileSystemItem>()
    private val playlistList = mutableListOf<Playlist>() // 歌单列表
    private lateinit var audioAdapter: AudioLibraryAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private var currentServerUrl = ""

    // 当前选中的选项卡
    private var currentTab: TabType = TabType.SONGS

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val audioLibraryPath = "data/音乐"  // 固定音频目录路径

    private enum class TabType {
        PLAYLISTS, SONGS
    }

    companion object {
        private const val TAG = "AudioLibraryActivity"
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

        initViews()
        setupTabs()
        loadAudios()
        // 初始化歌单列表（空列表）
        updatePlaylistUI()
        // 初始化歌单管理器
        PlaylistManager.initialize(this)
        // 加载歌单
        loadPlaylists()
    }

    private fun initViews() {
        audioRecyclerView = findViewById(R.id.audioRecyclerView)
        playlistRecyclerView = findViewById(R.id.playlistRecyclerView)
        statusText = findViewById(R.id.audioStatusText)
        titleText = findViewById(R.id.audioTitleText)
        backButton = findViewById(R.id.backButton)
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

        // 设置返回按钮
        backButton.setOnClickListener {
            finish()
        }

        // 设置搜索图标点击事件
        searchIconButton.setOnClickListener {
            showSearchContainer()
        }

        // 设置关闭搜索按钮
        closeSearchButton.setOnClickListener {
            hideSearchContainer()
        }

        // 设置搜索功能
        setupSearch()

        // 使用线性布局管理器
        audioRecyclerView.layoutManager = LinearLayoutManager(this)
        playlistRecyclerView.layoutManager = LinearLayoutManager(this)

        // 初始化音频适配器
        audioAdapter = AudioLibraryAdapter(
            currentServerUrl,
            filteredAudioList,
            onAudioClick = { audioItem -> playAudio(audioItem) },
            onAudioLongClick = { audioItem -> showAddToPlaylistDialog(audioItem) }
        )
        audioRecyclerView.adapter = audioAdapter

        // 初始化歌单适配器
        playlistAdapter = PlaylistAdapter(playlistList) { playlist ->
            val intent = Intent(this, PlaylistDetailActivity::class.java).apply {
                putExtra("PLAYLIST_ID", playlist.id)
                putExtra("SERVER_URL", currentServerUrl)
            }
            startActivity(intent)
        }
        playlistRecyclerView.adapter = playlistAdapter

        // 新建歌单按钮
        addPlaylistButton.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun setupTabs() {
        // 歌曲选项卡默认选中
        songsTab.setOnClickListener {
            switchToTab(TabType.SONGS)
        }

        playlistTab.setOnClickListener {
            switchToTab(TabType.PLAYLISTS)
        }
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
                // 更新选项卡样式
                playlistTab.setBackgroundResource(R.drawable.tab_background_selected)
                songsTab.setBackgroundResource(R.drawable.tab_background)
                playlistTab.setTextColor(getColor(R.color.primary_color))
                songsTab.setTextColor(getColor(R.color.text_primary))

                // 显示歌单列表，隐藏音频列表
                playlistRecyclerView.visibility = View.VISIBLE
                audioRecyclerView.visibility = View.GONE

                // 显示新建歌单按钮
                addPlaylistButton.visibility = View.VISIBLE

                // 更新状态文本
                updatePlaylistUI()

                // 如果搜索框显示，更新搜索提示
                if (searchContainer.visibility == View.VISIBLE) {
                    searchEditText.hint = "搜索歌单..."
                    performSearch(searchEditText.text.toString())
                }
            }

            TabType.SONGS -> {
                // 更新选项卡样式
                playlistTab.setBackgroundResource(R.drawable.tab_background)
                songsTab.setBackgroundResource(R.drawable.tab_background_selected)
                playlistTab.setTextColor(getColor(R.color.text_primary))
                songsTab.setTextColor(getColor(R.color.primary_color))

                // 显示音频列表，隐藏歌单列表
                audioRecyclerView.visibility = View.VISIBLE
                playlistRecyclerView.visibility = View.GONE

                // 隐藏新建歌单按钮
                addPlaylistButton.visibility = View.GONE

                // 更新状态文本
                if (audioList.isEmpty()) {
                    statusText.text = "没有找到音频文件"
                } else {
                    statusText.text = "共找到 ${audioList.size} 个音频文件"
                }

                // 如果搜索框显示，更新搜索提示
                if (searchContainer.visibility == View.VISIBLE) {
                    searchEditText.hint = "搜索音频..."
                    performSearch(searchEditText.text.toString())
                }
            }
        }
    }

    private fun showAddToPlaylistDialog(audioItem: FileSystemItem) {
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
                val audioTrack = AudioTrack.fromFileSystemItem(audioItem, currentServerUrl)
                val added = PlaylistManager.addTrackToPlaylist(selectedPlaylist.id, audioTrack)
                if (added) {
                    showToast("已添加到歌单 \"${selectedPlaylist.name}\"")
                } else {
                    showToast("歌曲已存在于该歌单")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSearchContainer() {
        searchContainer.visibility = View.VISIBLE
        searchIconButton.visibility = View.GONE

        // 根据当前选项卡设置提示文字
        searchEditText.hint = if (currentTab == TabType.PLAYLISTS) {
            "搜索歌单..."
        } else {
            "搜索音频..."
        }

        // 显示键盘
        searchEditText.requestFocus()
    }

    private fun hideSearchContainer() {
        searchContainer.visibility = View.GONE
        searchIconButton.visibility = View.VISIBLE
        searchEditText.setText("")

        // 隐藏键盘
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        // 恢复列表
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

        clearSearchButton.setOnClickListener {
            searchEditText.setText("")
            performSearch("")
        }

        // 初始隐藏清除按钮
        clearSearchButton.visibility = View.GONE
    }

    private fun performSearch(query: String) {
        val searchQuery = query.trim()

        // 显示/隐藏清除按钮
        clearSearchButton.visibility = if (searchQuery.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        when (currentTab) {
            TabType.SONGS -> {
                // 搜索音频
                filteredAudioList.clear()

                if (searchQuery.isEmpty()) {
                    filteredAudioList.addAll(audioList)
                } else {
                    filteredAudioList.addAll(audioList.filter { audio ->
                        audio.name.contains(searchQuery, true) ||
                                audio.extension.contains(searchQuery, true)
                    })
                }

                audioAdapter.notifyDataSetChanged()

                // 更新状态文本
                if (searchQuery.isNotEmpty()) {
                    statusText.text = "找到 ${filteredAudioList.size} 个匹配的音频文件"
                } else {
                    statusText.text = "共找到 ${audioList.size} 个音频文件"
                }
            }

            TabType.PLAYLISTS -> {
                // 搜索歌单
                // TODO: 实现歌单搜索逻辑
                if (searchQuery.isEmpty()) {
                    // 显示所有歌单
                    updatePlaylistUI()
                } else {
                    // 过滤歌单
                    val filteredPlaylists = playlistList.filter { playlist ->
                        playlist.name.contains(searchQuery, true)
                    }
                    playlistAdapter.updateData(filteredPlaylists)

                    // 更新状态文本
                    statusText.text = "找到 ${filteredPlaylists.size} 个匹配的歌单"
                }
            }
        }
    }

    private fun loadAudios() {
        coroutineScope.launch {
            statusText.text = "正在加载音频..."

            try {
                Log.d(TAG, "开始加载音频目录: $audioLibraryPath")

                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, audioLibraryPath)
                }

                Log.d(TAG, "获取到 ${allItems.size} 个项目")

                // 过滤出音频文件
                audioList.clear()
                audioList.addAll(allItems.filter { item ->
                    !item.isDirectory && AudioUtils.isAudioFile(item)
                })

                Log.d(TAG, "过滤后得到 ${audioList.size} 个音频文件")

                // 更新过滤列表
                filteredAudioList.clear()
                filteredAudioList.addAll(audioList)

                if (audioList.isEmpty()) {
                    statusText.text = "没有找到音频文件"
                } else {
                    statusText.text = "共找到 ${audioList.size} 个音频文件"
                    audioAdapter.notifyDataSetChanged()
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载音频异常", e)
            }
        }
    }

    private fun updatePlaylistUI() {
        // TODO: 从数据库或SharedPreferences加载用户歌单
        // 暂时显示空状态
        if (playlistList.isEmpty()) {
            statusText.text = "暂无歌单，点击右下角按钮创建"
        } else {
            statusText.text = "共 ${playlistList.size} 个歌单"
        }

        playlistAdapter.notifyDataSetChanged()
    }

    private fun showCreatePlaylistDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("新建歌单")

        val input = EditText(this)
        input.hint = "输入歌单名称"
        builder.setView(input)

        builder.setPositiveButton("创建") { dialog, which ->
            val playlistName = input.text.toString().trim()
            if (playlistName.isNotEmpty()) {
                createNewPlaylist(playlistName)
            }
        }

        builder.setNegativeButton("取消") { dialog, which ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun createNewPlaylist(name: String) {
        val newPlaylist = PlaylistManager.createPlaylist(name)
        playlistList.add(newPlaylist)
        playlistAdapter.notifyItemInserted(playlistList.size - 1)
        updatePlaylistUI()
        showToast("歌单 '$name' 创建成功")
    }

    private fun playAudio(audioItem: FileSystemItem) {
        try {
            // 转换为AudioTrack
            val audioTrack = AudioTrack.fromFileSystemItem(audioItem, currentServerUrl)

            // 获取音频文件列表并转换为AudioTrack列表
            val audioTracks = AudioUtils.convertToAudioTracks(filteredAudioList, currentServerUrl)
            val currentIndex = filteredAudioList.indexOf(audioItem)

            // 获取音频文件所在目录
            val directory = getDirectoryFromPath(audioItem.path)
            Log.d(TAG, "音频目录: $directory")

            // 设置自动连播 - 传递AudioTrack列表
            val intent = Intent(this, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", audioItem.name)
                putExtra("FILE_URL", audioTrack.url)
                putExtra("FILE_TYPE", "audio")
                putExtra("FILE_PATH", audioItem.path)  // 完整路径，用于歌词查找

                // 关键：传递AudioTrack列表，这会触发音频模式
                putExtra("AUDIO_TRACK", audioTrack)
                putExtra("AUDIO_TRACKS", ArrayList(audioTracks))

                putExtra("CURRENT_INDEX", currentIndex)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", directory)

                // 从音乐库进入的标志
                putExtra("FROM_MUSIC_LIBRARY", true)
                putExtra("SHOULD_AUTO_PLAY", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败", e)
        }
    }

    // 辅助方法：从路径中提取目录
    private fun getDirectoryFromPath(filePath: String): String {
        return try {
            val file = java.io.File(filePath)
            val parent = file.parent ?: ""
            Log.d(TAG, "从路径 $filePath 提取目录: $parent")
            parent
        } catch (e: Exception) {
            Log.e(TAG, "提取目录失败", e)
            ""
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        Log.d(TAG, "onDestroy")
    }
}