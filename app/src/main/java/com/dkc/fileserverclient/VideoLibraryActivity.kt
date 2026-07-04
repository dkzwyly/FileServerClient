@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class VideoLibraryActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var recentWatchedRecyclerView: RecyclerView
    private lateinit var foldersRecyclerView: RecyclerView
    private lateinit var videosRecyclerView: RecyclerView
    private lateinit var statusText: TextView

    private val fileServerService by lazy { FileServerService(this) }
    private var currentServerUrl = ""
    private val videoLibraryPath = "data/影视"  // 视频库根目录（仅用于显示，不再实际使用）

    // 数据列表
    private val recentWatchedList = mutableListOf<FileSystemItem>()
    private val folderList = mutableListOf<FileSystemItem>()
    private val videoList = mutableListOf<FileSystemItem>()
    private var selectedFolder: FileSystemItem? = null

    // 适配器
    private lateinit var recentWatchedAdapter: RecentWatchedAdapter
    private lateinit var foldersAdapter: VideoFoldersAdapter
    private lateinit var videosAdapter: VideoFilesAdapter

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()

    // 新增：存储完整的视频库树
    private var videoLibraryRoot: VideoLibraryNode? = null

    companion object {
        private const val TAG = "VideoLibraryActivity"
        private const val PREFS_NAME = "video_library_prefs"
        private const val KEY_RECENT_WATCHED = "recent_watched"

        // 视频文件扩展名（用于兼容旧逻辑，但新逻辑从树中直接判断 type）
        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm",
            ".m4v", ".3gp", ".mpeg", ".mpg", ".ts", ".m2ts"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_library)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        initViews()
        loadRecentWatched()
        loadVideoFolders()
    }

    private fun initViews() {
        backButton = findViewById(R.id.backButton)
        titleText = findViewById(R.id.videoLibraryTitleText)
        recentWatchedRecyclerView = findViewById(R.id.recentWatchedRecyclerView)
        foldersRecyclerView = findViewById(R.id.foldersRecyclerView)
        videosRecyclerView = findViewById(R.id.videosRecyclerView)
        statusText = findViewById(R.id.videoLibraryStatusText)

        titleText.text = "视频库"

        backButton.setOnClickListener {
            finish()
        }

        // 最近观看
        recentWatchedRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recentWatchedAdapter = RecentWatchedAdapter(
            currentServerUrl,
            recentWatchedList,
            { videoItem -> playVideo(videoItem, getCurrentFolderVideos()) },
            coroutineScope
        )
        recentWatchedRecyclerView.adapter = recentWatchedAdapter

        // 文件夹
        foldersRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        foldersAdapter = VideoFoldersAdapter(currentServerUrl, folderList) { folderItem ->
            onFolderSelected(folderItem)
        }
        foldersRecyclerView.adapter = foldersAdapter

        // 视频文件
        videosRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        videosAdapter = VideoFilesAdapter(
            currentServerUrl,
            videoList,
            { videoItem -> playVideo(videoItem, getCurrentFolderVideos()) },
            coroutineScope
        )
        videosRecyclerView.adapter = videosAdapter
    }

    // ---------- 最近观看 ----------
    private fun loadRecentWatched() {
        coroutineScope.launch {
            try {
                val recentItems = loadRecentWatchedFromStorage()
                recentWatchedList.clear()
                recentWatchedList.addAll(recentItems)
                recentWatchedAdapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Log.e(TAG, "加载最近观看记录失败", e)
            }
        }
    }

    private suspend fun loadRecentWatchedFromStorage(): List<FileSystemItem> = withContext(Dispatchers.IO) {
        try {
            val json = sharedPreferences.getString(KEY_RECENT_WATCHED, null)
            if (json.isNullOrEmpty()) emptyList()
            else {
                val type = object : TypeToken<List<FileSystemItem>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- 视频库加载（使用 getVideoLibrary 一次获取） ----------
    private fun loadVideoFolders() {
        coroutineScope.launch {
            statusText.text = "正在加载影视库..."
            try {
                // 1. 获取树
                val root = withContext(Dispatchers.IO) {
                    fileServerService.getVideoLibrary(currentServerUrl)
                }
                if (root == null) {
                    statusText.text = "获取影视库失败"
                    return@launch
                }
                videoLibraryRoot = root

                // 2. 提取第一层子目录（即电视剧/电影目录），树中 type != "video" 的节点
                val dirNodes = root.children?.filter { it.type != "video" } ?: emptyList()
                if (dirNodes.isEmpty()) {
                    statusText.text = "没有找到影视目录"
                    foldersAdapter.updateFolders(emptyList())
                    return@launch
                }

                // 3. 转换为 FileSystemItem 并更新文件夹列表
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
                foldersAdapter.updateFolders(folderItems)

                // 4. 统计每个目录下的视频数量（从 children 中 type == "video" 计数）
                dirNodes.forEach { node ->
                    val videoCount = node.children?.count { it.type == "video" } ?: 0
                    foldersAdapter.setVideoCount(node.path, videoCount)
                }

                statusText.text = "找到 ${folderItems.size} 个影视目录"

                // 5. 默认选中第一个目录，加载其视频
                if (folderItems.isNotEmpty()) {
                    onFolderSelected(folderItems[0])
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载影视库异常", e)
            }
        }
    }

    // ---------- 选中文件夹 ----------
    private fun onFolderSelected(folderItem: FileSystemItem) {
        selectedFolder = folderItem
        statusText.text = "正在加载: ${folderItem.name}"

        // 1. 从树中查找对应节点
        val targetNode = findNodeByPath(videoLibraryRoot, folderItem.path)
        if (targetNode == null) {
            statusText.text = "未找到该目录"
            return
        }

        // 2. 提取视频节点（type == "video"）
        val videoNodes = targetNode.children?.filter { it.type == "video" } ?: emptyList()
        val videoItems = videoNodes.map { node ->
            FileSystemItem(
                name = node.name,
                path = node.path,
                size = node.size ?: 0,
                extension = node.name.substringAfterLast('.', ""),
                sizeFormatted = node.sizeFormatted ?: "",
                lastModified = "",
                isVideo = true,
                isAudio = false,
                mimeType = "video/*",
                encoding = ""
            )
        }

        // 3. 更新视频列表
        videoList.clear()
        videoList.addAll(videoItems)
        videosAdapter.notifyDataSetChanged()

        if (videoList.isEmpty()) {
            statusText.text = "${folderItem.name} 没有视频文件"
        } else {
            statusText.text = "${folderItem.name} - ${videoList.size} 个视频"
            preloadVideoThumbnails()
        }

        // 更新文件夹选中状态
        foldersAdapter.setSelectedFolder(folderItem)
    }

    // ---------- 辅助：在树中按路径查找节点 ----------
    private fun findNodeByPath(root: VideoLibraryNode?, path: String): VideoLibraryNode? {
        if (root == null) return null
        if (root.path == path) return root
        root.children?.forEach { child ->
            val found = findNodeByPath(child, path)
            if (found != null) return found
        }
        return null
    }

    // ---------- 获取当前文件夹的视频列表（用于播放列表） ----------
    private fun getCurrentFolderVideos(): List<FileSystemItem> {
        return videoList
    }

    // ---------- 播放视频 ----------
    private fun playVideo(videoItem: FileSystemItem, allVideosInFolder: List<FileSystemItem>) {
        try {
            val encodedPath = java.net.URLEncoder.encode(videoItem.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            val currentIndex = allVideosInFolder.indexOfFirst { it.path == videoItem.path }

            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra("FILE_NAME", videoItem.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", "video")
                putExtra("FILE_PATH", videoItem.path)
                putExtra("AUTO_PLAY_ENABLED", true)
                putExtra("MEDIA_FILE_LIST", ArrayList(allVideosInFolder))
                putExtra("CURRENT_INDEX", currentIndex)
                putExtra("SERVER_URL", currentServerUrl)
            }
            startActivity(intent)

            addToRecentWatched(videoItem)
        } catch (e: Exception) {
            Log.e(TAG, "播放视频失败", e)
        }
    }

    // ---------- 最近观看持久化 ----------
    private fun addToRecentWatched(videoItem: FileSystemItem) {
        coroutineScope.launch {
            try {
                recentWatchedList.removeAll { it.path == videoItem.path }
                recentWatchedList.add(0, videoItem)
                if (recentWatchedList.size > 10) {
                    recentWatchedList.removeAt(recentWatchedList.size - 1)
                }
                recentWatchedAdapter.notifyDataSetChanged()
                saveRecentWatchedToStorage()
            } catch (e: Exception) {
                Log.e(TAG, "保存最近观看记录失败", e)
            }
        }
    }

    private suspend fun saveRecentWatchedToStorage() = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(recentWatchedList)
            sharedPreferences.edit().putString(KEY_RECENT_WATCHED, json).apply()
        } catch (e: Exception) {
            throw e
        }
    }

    // ---------- 缩略图预加载 ----------
    private fun preloadVideoThumbnails() {
        if (videoList.size > 10) {
            val videosToPreload = videoList.take(10)
            coroutineScope.launch {
                videosToPreload.forEach { videoItem ->
                    try {
                        ThumbnailLoader.loadVideoThumbnailBitmap(
                            serverUrl = currentServerUrl,
                            videoPath = videoItem.path,
                            width = 320,
                            height = 180
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "预加载失败: ${videoItem.path}", e)
                    }
                }
            }
        }
    }

    // ---------- 生命周期 ----------
    override fun onResume() {
        super.onResume()
        loadRecentWatched()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}