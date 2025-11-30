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
    private val videoLibraryPath = "data/影视"  // 视频库根目录

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

    companion object {
        private const val TAG = "VideoLibraryActivity"
        private const val PREFS_NAME = "video_library_prefs"
        private const val KEY_RECENT_WATCHED = "recent_watched"

        // 视频文件扩展名
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

        // 初始化 SharedPreferences
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

        // 设置返回按钮
        backButton.setOnClickListener {
            finish()
        }

        // 设置最近观看列表（水平滚动）
        recentWatchedRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recentWatchedAdapter = RecentWatchedAdapter(
            currentServerUrl,
            recentWatchedList,
            { videoItem -> playVideo(videoItem, getCurrentFolderVideos()) },
            coroutineScope
        )
        recentWatchedRecyclerView.adapter = recentWatchedAdapter

        // 设置文件夹列表（水平滚动）
        foldersRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        foldersAdapter = VideoFoldersAdapter(currentServerUrl, folderList) { folderItem ->
            onFolderSelected(folderItem)
        }
        foldersRecyclerView.adapter = foldersAdapter

        // 设置视频文件列表（水平滚动）
        videosRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        videosAdapter = VideoFilesAdapter(
            currentServerUrl,
            videoList,
            { videoItem -> playVideo(videoItem, getCurrentFolderVideos()) },
            coroutineScope
        )
        videosRecyclerView.adapter = videosAdapter
    }

    private fun loadRecentWatched() {
        coroutineScope.launch {
            try {
                val recentItems = loadRecentWatchedFromStorage()
                recentWatchedList.clear()
                recentWatchedList.addAll(recentItems)
                recentWatchedAdapter.notifyDataSetChanged()

                if (recentWatchedList.isEmpty()) {
                    Log.d(TAG, "没有找到最近观看记录")
                } else {
                    Log.d(TAG, "加载了 ${recentWatchedList.size} 个最近观看记录")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载最近观看记录失败", e)
            }
        }
    }

    /**
     * 从本地存储加载最近观看记录
     */
    private suspend fun loadRecentWatchedFromStorage(): List<FileSystemItem> = withContext(Dispatchers.IO) {
        return@withContext try {
            val recentWatchedJson = sharedPreferences.getString(KEY_RECENT_WATCHED, null)

            if (recentWatchedJson.isNullOrEmpty()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<FileSystemItem>>() {}.type
                gson.fromJson(recentWatchedJson, type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析最近观看记录失败", e)
            emptyList()
        }
    }

    private fun loadVideoFolders() {
        coroutineScope.launch {
            statusText.text = "正在加载影视文件夹..."

            try {
                Log.d(TAG, "开始加载视频库目录: $videoLibraryPath")

                // 获取所有包含视频文件的文件夹（包括两层深度）
                val allVideoFolders = findAllVideoFolders(videoLibraryPath, 2)

                Log.d(TAG, "找到 ${allVideoFolders.size} 个包含视频的文件夹")

                // 更新文件夹列表
                folderList.clear()
                folderList.addAll(allVideoFolders)

                if (folderList.isEmpty()) {
                    statusText.text = "没有找到包含视频的文件夹"
                } else {
                    statusText.text = "找到 ${folderList.size} 个影视文件夹"
                    foldersAdapter.notifyDataSetChanged()

                    // 默认选择第一个文件夹
                    if (folderList.isNotEmpty()) {
                        onFolderSelected(folderList[0])
                    }
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载影视文件夹异常", e)
            }
        }
    }

    /**
     * 递归查找包含视频文件的文件夹，最多搜索指定深度
     */
    private suspend fun findAllVideoFolders(startPath: String, maxDepth: Int): List<FileSystemItem> {
        val result = mutableListOf<FileSystemItem>()

        // 使用递归查找
        findVideoFoldersRecursive(startPath, 0, maxDepth, result)

        return result
    }

    /**
     * 递归查找包含视频文件的文件夹
     */
    private suspend fun findVideoFoldersRecursive(
        currentPath: String,
        currentDepth: Int,
        maxDepth: Int,
        result: MutableList<FileSystemItem>
    ) {
        if (currentDepth > maxDepth) {
            return
        }

        try {
            val items = withContext(Dispatchers.IO) {
                fileServerService.getFileList(currentServerUrl, currentPath)
            }

            for (item in items) {
                if (item.isDirectory && item.name != "..") {
                    // 检查该文件夹是否包含视频文件
                    if (hasVideoFiles(item.path)) {
                        result.add(item)
                    }

                    // 如果还有深度，继续递归搜索
                    if (currentDepth < maxDepth) {
                        findVideoFoldersRecursive(item.path, currentDepth + 1, maxDepth, result)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "搜索文件夹 $currentPath 失败", e)
        }
    }

    private suspend fun hasVideoFiles(folderPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val folderItems = fileServerService.getFileList(currentServerUrl, folderPath)
                folderItems.any { item ->
                    !item.isDirectory && isVideoFile(item)
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查文件夹 $folderPath 失败", e)
                false
            }
        }
    }

    private fun onFolderSelected(folderItem: FileSystemItem) {
        selectedFolder = folderItem
        statusText.text = "正在加载: ${folderItem.name}"
        loadVideosInFolder(folderItem.path)

        // 更新文件夹选中状态
        foldersAdapter.setSelectedFolder(folderItem)
    }

    private fun loadVideosInFolder(folderPath: String) {
        coroutineScope.launch {
            try {
                Log.d(TAG, "开始加载文件夹视频: $folderPath")

                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, folderPath)
                }

                // 过滤出视频文件
                videoList.clear()
                videoList.addAll(allItems.filter { item ->
                    !item.isDirectory && isVideoFile(item)
                })

                Log.d(TAG, "在文件夹中找到 ${videoList.size} 个视频文件")
                videosAdapter.notifyDataSetChanged()

                if (videoList.isEmpty()) {
                    statusText.text = "该文件夹没有视频文件"
                } else {
                    statusText.text = "${selectedFolder?.name} - ${videoList.size} 个视频"

                    // 预加载视频缩略图以提高用户体验
                    preloadVideoThumbnails()
                }

            } catch (e: Exception) {
                statusText.text = "加载视频失败: ${e.message}"
                Log.e(TAG, "加载文件夹视频异常", e)
            }
        }
    }

    /**
     * 预加载视频缩略图以提高用户体验
     */
    private fun preloadVideoThumbnails() {
        if (videoList.size > 10) {
            // 如果视频太多，只预加载前10个
            val videosToPreload = videoList.take(10)
            coroutineScope.launch {
                ThumbnailLoader.preloadVideoThumbnails(
                    serverUrl = currentServerUrl,
                    videoItems = videosToPreload,
                    width = 320,
                    height = 180
                )
            }
        }
    }

    private fun isVideoFile(item: FileSystemItem): Boolean {
        return VIDEO_EXTENSIONS.any { item.name.endsWith(it, ignoreCase = true) }
    }

    private fun getCurrentFolderVideos(): List<FileSystemItem> {
        return videoList
    }

    private fun playVideo(videoItem: FileSystemItem, allVideosInFolder: List<FileSystemItem>) {
        try {
            val encodedPath = java.net.URLEncoder.encode(videoItem.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            Log.d(TAG, "播放视频: ${videoItem.name}, URL: $fileUrl")

            // 找到当前视频在列表中的位置
            val currentIndex = allVideosInFolder.indexOfFirst { it.path == videoItem.path }

            val intent = Intent(this, PreviewActivity::class.java).apply {
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

            // 添加到最近观看
            addToRecentWatched(videoItem)

        } catch (e: Exception) {
            Log.e(TAG, "播放视频失败", e)
        }
    }

    private fun addToRecentWatched(videoItem: FileSystemItem) {
        coroutineScope.launch {
            try {
                // 移除已存在的相同项目
                recentWatchedList.removeAll { it.path == videoItem.path }

                // 添加到开头
                recentWatchedList.add(0, videoItem)

                // 限制最近观看数量
                if (recentWatchedList.size > 10) {
                    recentWatchedList.removeAt(recentWatchedList.size - 1)
                }

                recentWatchedAdapter.notifyDataSetChanged()

                // 保存到本地存储
                saveRecentWatchedToStorage()

            } catch (e: Exception) {
                Log.e(TAG, "保存最近观看记录失败", e)
            }
        }
    }

    /**
     * 保存最近观看记录到本地存储
     */
    private suspend fun saveRecentWatchedToStorage() = withContext(Dispatchers.IO) {
        try {
            val recentWatchedJson = gson.toJson(recentWatchedList)
            sharedPreferences.edit().putString(KEY_RECENT_WATCHED, recentWatchedJson).apply()

            Log.d(TAG, "最近观看记录已保存，共 ${recentWatchedList.size} 个项目")
        } catch (e: Exception) {
            Log.e(TAG, "保存最近观看记录失败", e)
            throw e
        }
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