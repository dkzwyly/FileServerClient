@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class VideoLibraryActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var foldersRecyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var switchToListButton: ImageButton
    private lateinit var switchToGridButton: ImageButton
    private lateinit var searchView: SearchView

    private val fileServerService by lazy { FileServerService(this) }
    private var currentServerUrl = ""

    // 文件夹数据
    private val folderList = mutableListOf<FileSystemItem>()
    private lateinit var foldersAdapter: VideoFoldersAdapter
    private var fullFolderList = listOf<FileSystemItem>()   // 保存原始数据用于搜索过滤

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "VideoLibraryActivity"
        private const val PREFS_NAME = "video_library_prefs"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val VIEW_MODE_LIST = 0
        private const val VIEW_MODE_GRID = 1

        // 缓存树（供详情页使用）
        var cachedRoot: VideoLibraryNode? = null
            private set
        var cachedServerUrl: String = ""
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_library)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }
        cachedServerUrl = currentServerUrl

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        initViews()
        loadVideoFolders()
    }

    private fun initViews() {
        backButton = findViewById(R.id.backButton)
        titleText = findViewById(R.id.videoLibraryTitleText)
        foldersRecyclerView = findViewById(R.id.foldersRecyclerView)
        statusText = findViewById(R.id.videoLibraryStatusText)
        switchToListButton = findViewById(R.id.switchToListButton)
        switchToGridButton = findViewById(R.id.switchToGridButton)
        searchView = findViewById(R.id.searchView)

        titleText.text = "视频库"

        backButton.setOnClickListener {
            finish()
        }

        // 文件夹适配器
        // 替换原来的 Toast 回调
        // 替换原来的 Toast 回调
        foldersAdapter = VideoFoldersAdapter(currentServerUrl, folderList) { folderItem ->
            val intent = Intent(this, VideoFolderDetailActivity::class.java).apply {
                putExtra("FOLDER_ITEM", folderItem)
                putExtra("SERVER_URL", currentServerUrl)
            }
            startActivity(intent)
        }
        foldersRecyclerView.adapter = foldersAdapter

        // 设置默认视图模式（从SharedPreferences读取）
        val savedMode = sharedPreferences.getInt(KEY_VIEW_MODE, VIEW_MODE_GRID)
        applyViewMode(savedMode)

        // 切换按钮点击
        switchToListButton.setOnClickListener {
            applyViewMode(VIEW_MODE_LIST)
            sharedPreferences.edit().putInt(KEY_VIEW_MODE, VIEW_MODE_LIST).apply()
        }

        switchToGridButton.setOnClickListener {
            applyViewMode(VIEW_MODE_GRID)
            sharedPreferences.edit().putInt(KEY_VIEW_MODE, VIEW_MODE_GRID).apply()
        }

        // 搜索监听
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterFolders(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterFolders(newText)
                return true
            }
        })
    }

    // 应用视图模式
    private fun applyViewMode(mode: Int) {
        when (mode) {
            VIEW_MODE_LIST -> {
                foldersRecyclerView.layoutManager = LinearLayoutManager(this)
                switchToListButton.visibility = View.GONE
                switchToGridButton.visibility = View.VISIBLE
                foldersAdapter.isListMode = true
            }
            VIEW_MODE_GRID -> {
                foldersRecyclerView.layoutManager = GridLayoutManager(this, 2)
                switchToListButton.visibility = View.VISIBLE
                switchToGridButton.visibility = View.GONE
                foldersAdapter.isListMode = false
            }
        }
        // 适配器内部已通过 setter 触发刷新
    }

    // 搜索过滤
    private fun filterFolders(query: String?) {
        if (query.isNullOrBlank()) {
            foldersAdapter.updateFolders(fullFolderList)
        } else {
            val filtered = fullFolderList.filter {
                it.name.contains(query, ignoreCase = true)
            }
            foldersAdapter.updateFolders(filtered)
        }
    }

    // ---------- 加载视频库 ----------
    private fun loadVideoFolders() {
        coroutineScope.launch {
            statusText.text = "正在加载影视库..."
            try {
                val root = withContext(Dispatchers.IO) {
                    fileServerService.getVideoLibrary(currentServerUrl)
                }
                if (root == null) {
                    statusText.text = "获取影视库失败"
                    return@launch
                }
                cachedRoot = root

                // 提取第一层子目录（电视剧/电影目录）
                val dirNodes = root.children?.filter { it.type != "video" } ?: emptyList()
                if (dirNodes.isEmpty()) {
                    statusText.text = "没有找到影视目录"
                    foldersAdapter.updateFolders(emptyList())
                    fullFolderList = emptyList()
                    return@launch
                }

                val folderItems = dirNodes.map { node ->
                    FileSystemItem(
                        name = node.name,
                        path = node.path,
                        size = 0,
                        extension = "",
                        sizeFormatted = "",
                        lastModified = "",
                        isVideo = false,
                        isAudio = false,
                        mimeType = "inode/directory",
                        encoding = ""
                    )
                }
                fullFolderList = folderItems
                foldersAdapter.updateFolders(folderItems)

                // 统计视频数量
                dirNodes.forEach { node ->
                    val videoCount = node.children?.count { it.type == "video" } ?: 0
                    foldersAdapter.setVideoCount(node.path, videoCount)
                }

                statusText.text = "找到 ${folderItems.size} 个影视目录"

                // 确保视图模式生效
                val savedMode = sharedPreferences.getInt(KEY_VIEW_MODE, VIEW_MODE_GRID)
                applyViewMode(savedMode)

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载影视库异常", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}