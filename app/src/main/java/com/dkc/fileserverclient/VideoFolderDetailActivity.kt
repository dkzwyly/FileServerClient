package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.net.URLEncoder

class VideoFolderDetailActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var previewImage: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecyclerView.Adapter<*>  // 动态切换

    private val episodeList = mutableListOf<FileSystemItem>()
    private val folderList = mutableListOf<FileSystemItem>()
    private var serverUrl = ""
    private var currentPath = ""  // 当前浏览路径
    private var rootPath = ""    // 进入时的根路径（剧集路径）
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val fileServerService by lazy { FileServerService(this) }

    private enum class SortType { NAME, SIZE, DATE }
    private var currentSort = SortType.NAME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_folder_detail)

        serverUrl = intent.getStringExtra("SERVER_URL") ?: run { finish(); return }
        val folderItem = intent.getParcelableExtra<FileSystemItem>("FOLDER_ITEM")
        if (folderItem == null) {
            finish()
            return
        }
        rootPath = folderItem.path
        currentPath = rootPath

        initViews()
        loadContent()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "加载中..."

        previewImage = findViewById(R.id.folderPreviewImage)
        recyclerView = findViewById(R.id.episodeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 默认显示空列表
        adapter = createEpisodeAdapter()
        recyclerView.adapter = adapter
    }

    private fun createEpisodeAdapter(): EpisodeAdapter {
        return EpisodeAdapter(
            serverUrl,
            episodeList,
            onItemClick = { clickedVideo -> updatePreview(clickedVideo) },
            onPlayClick = { video -> playVideo(video) }   // 新增播放回调
        )
    }

    private fun createFolderAdapter(): FolderAdapter {
        return FolderAdapter(serverUrl, folderList) { clickedFolder ->
            // 进入子目录
            currentPath = clickedFolder.path
            loadContent()
        }
    }

    /**
     * 启动视频播放器，支持自动连播
     */
    private fun playVideo(video: FileSystemItem) {
        val encodedPath = URLEncoder.encode(video.path, "UTF-8")
        val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath"

        // 获取当前视频在列表中的位置（按当前排序）
        val currentIndex = episodeList.indexOf(video)
        if (currentIndex == -1) {
            // 安全兜底
            return
        }

        val intent = Intent(this, VideoPlayerActivityV2::class.java).apply {
            putExtra("FILE_NAME", video.name)
            putExtra("FILE_URL", fileUrl)
            putExtra("FILE_PATH", video.path)
            putExtra("SERVER_URL", serverUrl)
            putExtra("CURRENT_PATH", currentPath)          // 当前目录，用于加载同名字幕
            putExtra("CURRENT_INDEX", currentIndex)
            putExtra("AUTO_PLAY_ENABLED", true)            // 启用自动连播
            // 传递当前目录下的所有视频列表（用于连播）
            putParcelableArrayListExtra("MEDIA_FILE_LIST", ArrayList(episodeList))
        }
        startActivity(intent)
    }

    private fun loadContent() {
        coroutineScope.launch {
            try {
                supportActionBar?.title = "加载中..."
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(serverUrl, currentPath)
                }

                // 分离目录和视频
                val dirs = items.filter { it.isDirectory && it.name != ".." }
                val videos = items.filter { it.isVideo }

                if (dirs.isNotEmpty()) {
                    // 有子目录，显示目录列表（季文件夹）
                    folderList.clear()
                    folderList.addAll(dirs)
                    // 切换到 FolderAdapter
                    val newAdapter = createFolderAdapter()
                    if (adapter != newAdapter) {
                        recyclerView.adapter = newAdapter
                        adapter = newAdapter
                    }
                    newAdapter.notifyDataSetChanged()
                    supportActionBar?.title = "选择季"
                    previewImage.setImageResource(R.drawable.ic_video_placeholder)
                } else if (videos.isNotEmpty()) {
                    // 没有子目录，显示视频列表
                    episodeList.clear()
                    episodeList.addAll(videos)
                    // 切换到 EpisodeAdapter
                    val newAdapter = createEpisodeAdapter()
                    if (adapter != newAdapter) {
                        recyclerView.adapter = newAdapter
                        adapter = newAdapter
                    }
                    sortEpisodes(currentSort)
                    newAdapter.notifyDataSetChanged()
                    supportActionBar?.title = "视频列表"
                    if (episodeList.isNotEmpty()) {
                        updatePreview(episodeList.first())
                    } else {
                        previewImage.setImageResource(R.drawable.ic_video_placeholder)
                    }
                } else {
                    // 空目录
                    episodeList.clear()
                    folderList.clear()
                    val newAdapter = createEpisodeAdapter()
                    if (adapter != newAdapter) {
                        recyclerView.adapter = newAdapter
                        adapter = newAdapter
                    }
                    newAdapter.notifyDataSetChanged()
                    supportActionBar?.title = "空目录"
                    previewImage.setImageResource(R.drawable.ic_video_placeholder)
                }
            } catch (e: Exception) {
                supportActionBar?.title = "加载失败"
            }
        }
    }

    private fun updatePreview(video: FileSystemItem) {
        ThumbnailLoader.loadVideoThumbnail(
            imageView = previewImage,
            serverUrl = serverUrl,
            videoPath = video.path,
            width = 320,
            height = 180
        )
    }

    // 排序（仅对视频列表有效）
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_folder_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                // 如果当前不在根目录，返回上一层
                if (currentPath != rootPath) {
                    currentPath = currentPath.substringBeforeLast('/')
                    loadContent()
                } else {
                    onBackPressed()
                }
                return true
            }
            R.id.sort_by_name -> {
                currentSort = SortType.NAME
                sortEpisodes(SortType.NAME)
                return true
            }
            R.id.sort_by_size -> {
                currentSort = SortType.SIZE
                sortEpisodes(SortType.SIZE)
                return true
            }
            R.id.sort_by_date -> {
                currentSort = SortType.DATE
                sortEpisodes(SortType.DATE)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun sortEpisodes(type: SortType) {
        when (type) {
            SortType.NAME -> episodeList.sortBy { it.name }
            SortType.SIZE -> episodeList.sortBy { it.size }
            SortType.DATE -> episodeList.sortBy { it.lastModified }
        }
        (adapter as? EpisodeAdapter)?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}