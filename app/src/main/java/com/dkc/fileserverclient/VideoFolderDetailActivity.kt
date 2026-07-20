package com.dkc.fileserverclient

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.net.URLEncoder

class VideoFolderDetailActivity : AppCompatActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var currentThumbnail: ImageView
    private lateinit var btnPlayCurrent: ImageButton
    private lateinit var currentVideoTitle: TextView
    private lateinit var currentVideoSize: TextView
    private lateinit var currentVideoDate: TextView
    private lateinit var recentThumbnail: ImageView
    private lateinit var btnPlayRecent: ImageButton
    private lateinit var recentCard: View                // 改为 View，避免 CardView 类型转换
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecyclerView.Adapter<*>

    private val episodeList = mutableListOf<FileSystemItem>()
    private val folderList = mutableListOf<FileSystemItem>()
    private var serverUrl = ""
    private var currentPath = ""
    private var rootPath = ""
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val fileServerService by lazy { FileServerService(this) }

    private lateinit var prefs: SharedPreferences
    private val PREF_RECENT_VIDEO_PATH = "recent_video_path"

    private enum class SortType { NAME, SIZE, DATE }
    private var currentSort = SortType.NAME

    private var currentVideo: FileSystemItem? = null
    private var recentVideo: FileSystemItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_folder_detail)

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

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

        currentThumbnail = findViewById(R.id.currentThumbnail)
        btnPlayCurrent = findViewById(R.id.btnPlayCurrent)
        currentVideoTitle = findViewById(R.id.currentVideoTitle)
        currentVideoSize = findViewById(R.id.currentVideoSize)
        currentVideoDate = findViewById(R.id.currentVideoDate)
        recentThumbnail = findViewById(R.id.recentThumbnail)
        btnPlayRecent = findViewById(R.id.btnPlayRecent)
        recentCard = findViewById(R.id.recentCard)   // 类型为 View，可安全转换

        btnPlayCurrent.setOnClickListener { currentVideo?.let { playVideo(it) } }
        btnPlayRecent.setOnClickListener { recentVideo?.let { playVideo(it) } }

        recyclerView = findViewById(R.id.episodeRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 5)

        adapter = createEpisodeAdapter()
        recyclerView.adapter = adapter
    }

    private fun createEpisodeAdapter(): EpisodeAdapter {
        return EpisodeAdapter(
            serverUrl,
            episodeList,
            onItemClick = { clickedVideo ->
                updateCurrentPreview(clickedVideo)
            }
        )
    }

    private fun createFolderAdapter(): FolderAdapter {
        return FolderAdapter(serverUrl, folderList) { clickedFolder ->
            currentPath = clickedFolder.path
            loadContent()
        }
    }

    private fun playVideo(video: FileSystemItem) {
        val encodedPath = URLEncoder.encode(video.path, "UTF-8")
        val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath"

        val currentIndex = episodeList.indexOf(video)
        if (currentIndex == -1) return

        // 持久化最近观看
        prefs.edit().putString(PREF_RECENT_VIDEO_PATH, video.path).apply()
        updateRecentView(video)

        val intent = Intent(this, VideoPlayerActivityV2::class.java).apply {
            putExtra("FILE_NAME", video.name)
            putExtra("FILE_URL", fileUrl)
            putExtra("FILE_PATH", video.path)
            putExtra("SERVER_URL", serverUrl)
            putExtra("CURRENT_PATH", currentPath)
            putExtra("CURRENT_INDEX", currentIndex)
            putExtra("AUTO_PLAY_ENABLED", true)
            putParcelableArrayListExtra("MEDIA_FILE_LIST", ArrayList(episodeList))
        }
        startActivity(intent)
    }

    private fun updateCurrentPreview(video: FileSystemItem?) {
        currentVideo = video
        if (video != null) {
            currentVideoTitle.text = video.name
            currentVideoSize.text = "大小: ${video.sizeFormatted}"
            currentVideoDate.text = "修改: ${video.lastModified}"
            ThumbnailLoader.loadVideoThumbnail(
                imageView = currentThumbnail,
                serverUrl = serverUrl,
                videoPath = video.path,
                width = 320,
                height = 180
            )
        } else {
            currentVideoTitle.text = "无视频"
            currentVideoSize.text = ""
            currentVideoDate.text = ""
            currentThumbnail.setImageResource(R.drawable.ic_video_placeholder)
        }
    }

    private fun updateRecentView(video: FileSystemItem?) {
        recentVideo = video
        if (video != null) {
            ThumbnailLoader.loadVideoThumbnail(
                imageView = recentThumbnail,
                serverUrl = serverUrl,
                videoPath = video.path,
                width = 320,
                height = 180
            )
            recentCard.visibility = View.VISIBLE
        } else {
            recentThumbnail.setImageResource(R.drawable.ic_video_placeholder)
            recentCard.visibility = View.GONE
        }
    }

    private fun loadContent() {
        coroutineScope.launch {
            try {
                supportActionBar?.title = "加载中..."
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(serverUrl, currentPath)
                }

                val dirs = items.filter { it.isDirectory && it.name != ".." }
                val videos = items.filter { it.isVideo }

                if (dirs.isNotEmpty()) {
                    folderList.clear()
                    folderList.addAll(dirs)
                    val newAdapter = createFolderAdapter()
                    if (adapter != newAdapter) {
                        recyclerView.adapter = newAdapter
                        adapter = newAdapter
                    }
                    newAdapter.notifyDataSetChanged()
                    supportActionBar?.title = "选择季"
                    updateCurrentPreview(null)
                    updateRecentView(null)
                } else if (videos.isNotEmpty()) {
                    episodeList.clear()
                    episodeList.addAll(videos)
                    sortEpisodes(currentSort)
                    val newAdapter = createEpisodeAdapter()
                    if (adapter != newAdapter) {
                        recyclerView.adapter = newAdapter
                        adapter = newAdapter
                    }
                    newAdapter.notifyDataSetChanged()
                    supportActionBar?.title = "视频列表"
                    val first = episodeList.firstOrNull()
                    updateCurrentPreview(first)

                    // 恢复最近观看
                    val recentPath = prefs.getString(PREF_RECENT_VIDEO_PATH, null)
                    if (recentPath != null) {
                        val recentItem = episodeList.find { it.path == recentPath }
                        if (recentItem != null) {
                            updateRecentView(recentItem)
                        } else {
                            updateRecentView(null)
                        }
                    } else {
                        updateRecentView(null)
                    }
                } else {
                    episodeList.clear()
                    folderList.clear()
                    val newAdapter = createEpisodeAdapter()
                    if (adapter != newAdapter) {
                        recyclerView.adapter = newAdapter
                        adapter = newAdapter
                    }
                    newAdapter.notifyDataSetChanged()
                    supportActionBar?.title = "空目录"
                    updateCurrentPreview(null)
                    updateRecentView(null)
                }
            } catch (e: Exception) {
                supportActionBar?.title = "加载失败"
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_folder_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (currentPath != rootPath) {
                    currentPath = currentPath.substringBeforeLast('/')
                    loadContent()
                } else {
                    onBackPressed()
                }
                return true
            }
            R.id.sort_by_name -> { currentSort = SortType.NAME; sortEpisodes(SortType.NAME) }
            R.id.sort_by_size -> { currentSort = SortType.SIZE; sortEpisodes(SortType.SIZE) }
            R.id.sort_by_date -> { currentSort = SortType.DATE; sortEpisodes(SortType.DATE) }
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