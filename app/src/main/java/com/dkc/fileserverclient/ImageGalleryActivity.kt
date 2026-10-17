package com.dkc.fileserverclient

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImageGalleryActivity : AppCompatActivity() {

    // ========== Views ==========
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumSetRecyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var sortButton: ImageButton
    private lateinit var reindexButton: ImageButton
    private lateinit var deleteSelectedButton: ImageButton
    private lateinit var breakModeButton: ImageButton
    private lateinit var trashButton: ImageButton
    private lateinit var syncLocalButton: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectedCountText: TextView
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button
    private lateinit var tabAlbum: TextView
    private lateinit var tabAlbumSet: TextView
    private lateinit var addAlbumFab: FloatingActionButton
    private lateinit var gallerySwipeRefresh: SwipeRefreshLayout
    private lateinit var albumSetSwipeRefresh: SwipeRefreshLayout


    private val fileServerService by lazy { FileServerService(this) }
    private lateinit var adapter: ImageGalleryAdapter
    private var currentServerUrl = ""
    private var isMultiSelectionMode = false
    private val selectedItems = mutableSetOf<String>()
    private var isAllSelected = false
    private var currentSortBy = "dateTaken"
    private var currentSortOrder = "desc"
    private val dateTakenMap = mutableMapOf<String, String?>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val imageGalleryPath = "data/图片"
    private var pendingRefreshJob: Job? = null
    private var lastGalleryTabLoadTime = 0L
    private var lastAlbumTabLoadTime = 0L

    // ========== 相册集相关 ==========
    private var currentTab = TAB_ALBUM
    private var albumList = mutableListOf<Album>()
    private lateinit var albumSetAdapter: AlbumSetAdapter
    private val gson = Gson()
    private val PREFS_ALBUMS = "albums_prefs"
    private val KEY_ALBUMS_JSON = "albums_json"

    // ========== 断绝模式 ==========
    private var breakModeEnabled = false

    // ========== 拍照 / 录像 ==========
    private var currentPhotoFile: File? = null
    private var currentVideoFile: File? = null

    companion object {
        private const val TAG = "ImageGalleryActivity"
        private const val TAB_ALBUM = 0
        private const val TAB_ALBUM_SET = 1
        const val EXTRA_ALBUM_ID = "album_id"
        const val EXTRA_SERVER_URL = "server_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_gallery)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }
        initViews()
        loadAlbumsFromPrefs()
        switchTab(TAB_ALBUM)
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (currentTab == TAB_ALBUM) {
            if (now - lastGalleryTabLoadTime > 5_000) {
                lastGalleryTabLoadTime = now
                loadImages()
            }
        } else {
            if (now - lastAlbumTabLoadTime > 5_000) {
                lastAlbumTabLoadTime = now
                loadAlbumsFromPrefs()
                albumSetAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.galleryRecyclerView)
        albumSetRecyclerView = findViewById(R.id.albumSetRecyclerView)
        statusText = findViewById(R.id.galleryStatusText)
        titleText = findViewById(R.id.galleryTitleText)
        backButton = findViewById(R.id.backButton)
        cameraButton = findViewById(R.id.cameraButton)
        sortButton = findViewById(R.id.sortButton)
        reindexButton = findViewById(R.id.reindexButton)
        deleteSelectedButton = findViewById(R.id.deleteSelectedButton)
        breakModeButton = findViewById(R.id.breakModeButton)
        trashButton = findViewById(R.id.trashButton)
        syncLocalButton = findViewById(R.id.syncLocalButton)
        selectionToolbar = findViewById(R.id.selectionToolbar)
        selectedCountText = findViewById(R.id.selectedCountText)
        selectAllButton = findViewById(R.id.selectAllButton)
        cancelSelectionButton = findViewById(R.id.cancelSelectionButton)
        tabAlbum = findViewById(R.id.albumTab)
        tabAlbumSet = findViewById(R.id.albumSetTab)
        addAlbumFab = findViewById(R.id.addAlbumFab)
        gallerySwipeRefresh = findViewById(R.id.gallerySwipeRefresh)
        albumSetSwipeRefresh = findViewById(R.id.albumSetSwipeRefresh)

        titleText.text = "图片"

        tabAlbum.setOnClickListener { switchTab(TAB_ALBUM) }
        tabAlbumSet.setOnClickListener { switchTab(TAB_ALBUM_SET) }

        backButton.setOnClickListener {
            if (isMultiSelectionMode) exitMultiSelectionMode() else finish()
        }

        cameraButton.setOnClickListener { checkAndRequestPermissions() }
        sortButton.setOnClickListener { showSortDialog() }
        reindexButton.setOnClickListener { openFilePicker() }
        syncLocalButton.setOnClickListener { openLocalSync() }
        deleteSelectedButton.setOnClickListener { showDeleteConfirmation() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        cancelSelectionButton.setOnClickListener { exitMultiSelectionMode() }

        breakModeButton.setOnClickListener { toggleBreakMode() }
        updateBreakModeButtonUI()

        // ★ 回收站按钮
        trashButton.setOnClickListener { openTrash() }

        gallerySwipeRefresh.setOnRefreshListener {
            ThumbnailLoader.invalidateSvgCache()
            loadImages(currentSortBy, currentSortOrder)
        }

        albumSetSwipeRefresh.setOnRefreshListener {
            loadAlbumsFromPrefs()
            albumSetAdapter.notifyDataSetChanged()
            albumSetSwipeRefresh.isRefreshing = false
            statusText.text = if (albumList.isEmpty()) "暂无相册" else "共 ${albumList.size} 个相册"
        }

        val gridLayoutManager = GridLayoutManager(this, 4)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    ImageGalleryAdapter.VIEW_TYPE_HEADER -> 4
                    else -> 1
                }
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        adapter = ImageGalleryAdapter(
            serverUrl = currentServerUrl,
            isMultiSelectionMode = { isMultiSelectionMode },
            isItemSelected = { selectedItems.contains(it) },
            onImageClick = { item ->
                if (isMultiSelectionMode) toggleItemSelection(item) else previewMedia(item)
            },
            onImageLongClick = { imageItem ->
                if (!isMultiSelectionMode) enterMultiSelectionMode()
                toggleItemSelection(imageItem)
            }
        )
        recyclerView.adapter = adapter

        albumSetRecyclerView.layoutManager = LinearLayoutManager(this)
        albumSetAdapter = AlbumSetAdapter(
            serverUrl = currentServerUrl,
            albums = albumList,
            onAlbumClick = { album ->
                val intent = Intent(this, AlbumDetailActivity::class.java).apply {
                    putExtra(EXTRA_ALBUM_ID, album.id)
                    putExtra(EXTRA_SERVER_URL, currentServerUrl)
                }
                startActivity(intent)
            },
            onAlbumLongClick = { album ->
                if (breakModeEnabled) {
                    showBreakAlbumDialog(album)
                } else {
                    showDeleteAlbumDialog(album)
                }
            }
        )
        albumSetRecyclerView.adapter = albumSetAdapter

        addAlbumFab.setOnClickListener { showCreateAlbumDialog() }
    }

    // ==================== 回收站入口 ====================
    private fun openTrash() {
        startActivity(Intent(this, TrashActivity::class.java).apply {
            putExtra("SERVER_URL", currentServerUrl)
        })
    }
    private fun openLocalSync() {
        startActivity(Intent(this, com.dkc.fileserverclient.sync.ImageSyncActivity::class.java).apply {
            putExtra("SERVER_URL", currentServerUrl)
        })
    }
    // ==================== 断绝模式 ====================
    private fun toggleBreakMode() {
        breakModeEnabled = !breakModeEnabled
        updateBreakModeButtonUI()
        if (breakModeEnabled) {
            Toast.makeText(
                this,
                "断绝模式已开启：长按相册可删除相册及其所有图片",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "断绝模式已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBreakModeButtonUI() {
        breakModeButton.setImageResource(
            if (breakModeEnabled) R.drawable.ic_heart_broken_red
            else R.drawable.ic_heart_broken_grey
        )
        breakModeButton.contentDescription =
            if (breakModeEnabled) "断绝模式（开启中）" else "断绝模式"
    }

    private fun showBreakAlbumDialog(album: Album) {
        val count = album.imagePaths.size
        val msg = if (count == 0) {
            "「${album.name}」中还没有文件。\n\n断绝后相册本身会被删除。确定继续吗？"
        } else {
            "「${album.name}」\n" +
                    "将删除相册以及其中 $count 个文件（不可恢复）。\n\n" +
                    "确定要断绝吗？"
        }
        AlertDialog.Builder(this)
            .setTitle("断绝相册")
            .setMessage(msg)
            .setPositiveButton("断绝") { _, _ -> breakAlbum(album) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun breakAlbum(album: Album) {
        val paths = album.imagePaths.toList()

        if (paths.isEmpty()) {
            albumList.remove(album)
            saveAlbumsToPrefs()
            albumSetAdapter.notifyDataSetChanged()
            statusText.text = if (albumList.isEmpty()) "暂无相册" else "共 ${albumList.size} 个相册"
            Toast.makeText(this, "已断绝「${album.name}」（无文件）", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            statusText.text = "断绝中 (${paths.size} 个文件)..."
            val (success, failed) = withContext(Dispatchers.IO) {
                fileServerService.batchDeleteFiles(currentServerUrl, paths)
            }

            albumList.remove(album)
            saveAlbumsToPrefs()
            albumSetAdapter.notifyDataSetChanged()
            statusText.text = if (albumList.isEmpty()) "暂无相册" else "共 ${albumList.size} 个相册"

            Toast.makeText(
                this@ImageGalleryActivity,
                buildString {
                    append("已断绝「${album.name}」")
                    append("，删除 $success 个文件")
                    if (failed > 0) append("，$failed 个失败")
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ==================== Tab 切换 ====================
    private fun switchTab(tab: Int) {
        currentTab = tab
        if (tab == TAB_ALBUM) {
            tabAlbum.background = resources.getDrawable(R.drawable.tab_background_selected, null)
            tabAlbum.setTextColor(resources.getColor(R.color.primary_color, null))
            tabAlbumSet.background = resources.getDrawable(R.drawable.tab_background, null)
            tabAlbumSet.setTextColor(resources.getColor(R.color.text_primary, null))

            gallerySwipeRefresh.visibility = View.VISIBLE
            albumSetSwipeRefresh.visibility = View.GONE
            addAlbumFab.visibility = View.GONE
            sortButton.visibility = View.VISIBLE
            reindexButton.visibility = View.VISIBLE
            cameraButton.visibility = View.VISIBLE
            syncLocalButton.visibility = View.VISIBLE
            breakModeButton.visibility = View.GONE
            trashButton.visibility = View.GONE
            if (isMultiSelectionMode) exitMultiSelectionMode()
            loadImages()
        } else {
            tabAlbumSet.background = resources.getDrawable(R.drawable.tab_background_selected, null)
            tabAlbumSet.setTextColor(resources.getColor(R.color.primary_color, null))
            tabAlbum.background = resources.getDrawable(R.drawable.tab_background, null)
            tabAlbum.setTextColor(resources.getColor(R.color.text_primary, null))

            gallerySwipeRefresh.visibility = View.GONE
            albumSetSwipeRefresh.visibility = View.VISIBLE
            addAlbumFab.visibility = View.VISIBLE
            sortButton.visibility = View.GONE
            reindexButton.visibility = View.GONE
            cameraButton.visibility = View.GONE
            syncLocalButton.visibility = View.GONE
            // ★ 相册集 Tab 显示「心碎 + 回收站」，心碎在左
            breakModeButton.visibility = View.VISIBLE
            trashButton.visibility = View.VISIBLE
            if (isMultiSelectionMode) exitMultiSelectionMode()
            loadAlbumsFromPrefs()
            albumSetAdapter.notifyDataSetChanged()
            statusText.text = if (albumList.isEmpty()) "暂无相册" else "共 ${albumList.size} 个相册"
        }
    }

    // ==================== 相册集存储 ====================
    private fun loadAlbumsFromPrefs() {
        val json = getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
            .getString(KEY_ALBUMS_JSON, null)
        albumList.clear()
        if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<Album>>() {}.type
                val list: List<Album> = gson.fromJson(json, type)
                albumList.addAll(list)
            } catch (e: Exception) {
                Log.e(TAG, "loadAlbums error", e)
            }
        }
    }

    private fun saveAlbumsToPrefs() {
        val json = gson.toJson(albumList)
        getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALBUMS_JSON, json)
            .apply()
    }

    private fun showDeleteAlbumDialog(album: Album) {
        AlertDialog.Builder(this)
            .setTitle("删除相册")
            .setMessage("确定要删除相册「${album.name}」吗？\n（不会删除服务器上的图片）")
            .setPositiveButton("删除") { _, _ -> deleteAlbum(album) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteAlbum(album: Album) {
        albumList.remove(album)
        saveAlbumsToPrefs()
        albumSetAdapter.notifyDataSetChanged()
        statusText.text = if (albumList.isEmpty()) "暂无相册" else "共 ${albumList.size} 个相册"
        Toast.makeText(this, "相册「${album.name}」已删除", Toast.LENGTH_SHORT).show()
    }

    private fun showCreateAlbumDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("新建相册")
            .setMessage("请输入相册名称")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    val newAlbum = Album(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        imagePaths = mutableListOf()
                    )
                    albumList.add(newAlbum)
                    saveAlbumsToPrefs()
                    albumSetAdapter.notifyItemInserted(albumList.size - 1)
                    statusText.text = "共 ${albumList.size} 个相册"
                    Toast.makeText(this, "已创建相册：$name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 媒体加载 ====================
    private fun showSortDialog() {
        val options = arrayOf(
            "按名称 (升序)", "按名称 (降序)",
            "按修改时间 (升序)", "按修改时间 (降序)",
            "按文件大小 (升序)", "按文件大小 (降序)",
            "按拍摄时间 (升序)", "按拍摄时间 (降序)"
        )
        val index = when (currentSortBy) {
            "name" -> if (currentSortOrder == "asc") 0 else 1
            "modified" -> if (currentSortOrder == "asc") 2 else 3
            "size" -> if (currentSortOrder == "asc") 4 else 5
            "dateTaken" -> if (currentSortOrder == "asc") 6 else 7
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("排序方式")
            .setSingleChoiceItems(options, index) { _, which ->
                val (sortBy, sortOrder) = when (which) {
                    0 -> "name" to "asc"
                    1 -> "name" to "desc"
                    2 -> "modified" to "asc"
                    3 -> "modified" to "desc"
                    4 -> "size" to "asc"
                    5 -> "size" to "desc"
                    6 -> "dateTaken" to "asc"
                    7 -> "dateTaken" to "desc"
                    else -> "name" to "asc"
                }
                loadImages(sortBy, sortOrder)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadImages(
        sortBy: String = currentSortBy,
        sortOrder: String = currentSortOrder
    ) {
        if (currentTab != TAB_ALBUM) return
        currentSortBy = sortBy
        currentSortOrder = sortOrder
        lastGalleryTabLoadTime = System.currentTimeMillis()
        coroutineScope.launch {
            statusText.text = "加载中..."
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryPath, sortBy, sortOrder)
                }
                val mediaItems = items.filter { !it.isDirectory && (it.isImage || it.isVideo) }

                if (sortBy == "dateTaken") {
                    val paths = mediaItems.map { it.path }
                    val dates = withContext(Dispatchers.IO) {
                        fileServerService.getBatchDateTaken(currentServerUrl, paths)
                    }
                    dateTakenMap.clear()
                    dateTakenMap.putAll(dates)
                    adapter.submitList(groupByDateTaken(mediaItems))
                } else {
                    adapter.submitList(mediaItems.map { GalleryItem.ImageEntry(it) })
                }

                val imageCount = mediaItems.count { it.isImage }
                val videoCount = mediaItems.count { it.isVideo }
                statusText.text = when {
                    mediaItems.isEmpty() -> "无媒体文件"
                    videoCount == 0 -> "共 $imageCount 张"
                    imageCount == 0 -> "共 $videoCount 个视频"
                    else -> "图片 $imageCount · 视频 $videoCount"
                }
            } catch (e: Exception) {
                statusText.text = "加载失败"
                Log.e(TAG, "loadImages error", e)
            } finally {
                gallerySwipeRefresh.isRefreshing = false
            }
        }
    }

    private fun groupByDateTaken(items: List<FileSystemItem>): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()
        var currentLabel: String? = null

        for (item in items) {
            val dateStr = dateTakenMap[item.path]
            val isoDate: String? = when {
                !dateStr.isNullOrEmpty() -> dateStr.substringBefore("T").take(10)
                item.lastModified.isNotEmpty() -> item.lastModified.substringBefore("T").take(10)
                else -> null
            }
            val label: String = if (isoDate == null) {
                "未知日期"
            } else try {
                val cal = Calendar.getInstance().apply {
                    time = dateFormat.parse(isoDate)!!
                }
                when {
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                        SimpleDateFormat("M月d日", Locale.getDefault()).format(cal.time)
                    else ->
                        SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(cal.time)
                }
            } catch (e: Exception) { "未知日期" }

            if (label != currentLabel) {
                currentLabel = label
                result.add(GalleryItem.DateHeader(label))
            }
            result.add(GalleryItem.ImageEntry(item))
        }
        return result
    }

    // ==================== 多选模式 ====================
    private fun enterMultiSelectionMode() {
        isMultiSelectionMode = true
        selectionToolbar.visibility = View.VISIBLE
        deleteSelectedButton.visibility = View.VISIBLE
        deleteSelectedButton.isEnabled = false
        selectedItems.clear()
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun exitMultiSelectionMode() {
        isMultiSelectionMode = false
        selectionToolbar.visibility = View.GONE
        deleteSelectedButton.visibility = View.GONE
        selectedItems.clear()
        isAllSelected = false
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun toggleItemSelection(item: FileSystemItem) {
        val path = item.path
        if (selectedItems.contains(path)) selectedItems.remove(path) else selectedItems.add(path)
        updateSelectionUI()
        val pos = adapter.currentList.indexOfFirst {
            it is GalleryItem.ImageEntry && it.image.path == path
        }
        if (pos != -1) adapter.notifyItemChanged(pos, "selection")
    }

    private fun toggleSelectAll() {
        if (isAllSelected) {
            selectedItems.clear()
            isAllSelected = false
        } else {
            selectedItems.clear()
            adapter.currentList.filterIsInstance<GalleryItem.ImageEntry>().forEach {
                selectedItems.add(it.image.path)
            }
            isAllSelected = true
        }
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionUI() {
        selectedCountText.text = "已选择 ${selectedItems.size} 项"
        deleteSelectedButton.isEnabled = selectedItems.isNotEmpty()
        selectAllButton.text = if (isAllSelected) "取消全选" else "全选"
    }

    private fun showDeleteConfirmation() {
        if (selectedItems.isEmpty()) return
        val message = if (selectedItems.size == 1) "确定删除选中的1个文件？"
        else "确定删除选中的${selectedItems.size}个文件？"
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> deleteSelectedImages() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedImages() {
        val toDelete = selectedItems.toSet()
        if (toDelete.isEmpty()) return

        coroutineScope.launch {
            statusText.text = "删除中 (${toDelete.size} 个文件)..."
            val (successCount, _) = withContext(Dispatchers.IO) {
                fileServerService.batchDeleteFiles(currentServerUrl, toDelete.toList())
            }

            // 成功删除的路径同步从所有相册集里移除
            // 注意：批量接口不返回具体哪些成功，简化处理——只要 successCount > 0 就按全部成功处理
            // 更严谨的话可以在 batchDeleteFiles 里返回实际成功路径列表
            if (successCount > 0) {
                syncRemoveFromAlbums(toDelete)
            }

            loadImages()
            exitMultiSelectionMode()
            Toast.makeText(
                this@ImageGalleryActivity,
                "已删除 $successCount / ${toDelete.size} 个",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    private fun syncRemoveFromAlbums(deletedPaths: Set<String>) {
        val json = getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
            .getString(KEY_ALBUMS_JSON, null)
        if (json.isNullOrEmpty()) return

        val type = object : TypeToken<MutableList<Album>>() {}.type
        val albums: MutableList<Album> = gson.fromJson(json, type)
        var changed = false
        albums.forEach { album ->
            if (album.imagePaths.removeAll(deletedPaths)) changed = true
        }
        if (changed) {
            getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ALBUMS_JSON, gson.toJson(albums))
                .apply()
            loadAlbumsFromPrefs()
            albumSetAdapter.notifyDataSetChanged()
        }
    }

    // ==================== 预览 ====================
    private fun previewMedia(item: FileSystemItem) {
        try {
            val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
            val url = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encoded"
            startActivity(Intent(this, ImageActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", url)
                putExtra("FILE_PATH", item.path)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", imageGalleryPath)
                putExtra("SORT_BY", currentSortBy)
                putExtra("SORT_ORDER", currentSortOrder)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 相机权限 ====================
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
            if (need.isEmpty()) showCameraChoiceDialog()
            else requestMultiplePermissionsLauncher.launch(need.toTypedArray())
        } else {
            val need = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (need.isEmpty()) showCameraChoiceDialog()
            else requestMultiplePermissionsLauncher.launch(need.toTypedArray())
        }
    }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) showCameraChoiceDialog()
            else Toast.makeText(this, "需要相机和麦克风权限", Toast.LENGTH_SHORT).show()
        }

    private fun showCameraChoiceDialog() {
        val options = arrayOf("拍照", "录像")
        AlertDialog.Builder(this)
            .setTitle("拍摄方式")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> capturePhoto()
                    1 -> captureVideo()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun capturePhoto() {
        val photoFile = createImageFile() ?: run {
            Toast.makeText(this, "无法创建图片文件", Toast.LENGTH_SHORT).show(); return
        }
        currentPhotoFile = photoFile
        val photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) != null) {
            takePictureLauncher.launch(intent)
        } else {
            Toast.makeText(this, "无相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentPhotoFile?.let { enqueueSingleUpload(it) }
            } else {
                currentPhotoFile?.delete()
                currentPhotoFile = null
            }
        }

    private fun createImageFile(): File? = try {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.apply { mkdirs() }
        File.createTempFile("IMG_$ts", ".jpg", dir)
    } catch (e: Exception) {
        Log.e(TAG, "创建图片文件失败", e); null
    }

    private fun captureVideo() {
        val videoFile = createVideoFile() ?: run {
            Toast.makeText(this, "无法创建视频文件", Toast.LENGTH_SHORT).show(); return
        }
        currentVideoFile = videoFile
        val videoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", videoFile)
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) != null) {
            takeVideoLauncher.launch(intent)
        } else {
            Toast.makeText(this, "无相机应用（或系统不支持录像）", Toast.LENGTH_SHORT).show()
        }
    }

    private val takeVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentVideoFile?.let { enqueueSingleUpload(it) }
            } else {
                currentVideoFile?.delete()
                currentVideoFile = null
            }
        }

    private fun createVideoFile(): File? = try {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.apply { mkdirs() }
        File.createTempFile("VID_$ts", ".mp4", dir)
    } catch (e: Exception) {
        Log.e(TAG, "创建视频文件失败", e); null
    }

    private fun enqueueSingleUpload(file: File) {
        coroutineScope.launch {
            statusText.text = "加入上传队列..."
            try {
                com.dkc.fileserverclient.transfer.TransferManager.enqueueUpload(
                    serverUrl = currentServerUrl,
                    localFile = file,
                    remoteDir = imageGalleryPath
                )
                Toast.makeText(this@ImageGalleryActivity, "已加入上传队列", Toast.LENGTH_SHORT).show()
                startQueuePolling()
            } catch (e: Exception) {
                Toast.makeText(this@ImageGalleryActivity, "入队失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                currentPhotoFile = null
                currentVideoFile = null
                statusText.text = if (adapter.itemCount == 0) "无媒体文件" else "共 ${adapter.itemCount} 项"
            }
        }
    }

    // ==================== 本地多选上传 ====================
    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val uris = if (data.clipData != null) {
                    (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }
                } else if (data.data != null) {
                    listOf(data.data!!)
                } else emptyList()
                if (uris.isNotEmpty()) uploadSelectedFiles(uris)
            }
        }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        filePickerLauncher.launch(intent)
    }

    private fun uploadSelectedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return

        coroutineScope.launch {
            statusText.text = "准备入队 (${uris.size} 个文件)..."

            // 主线程只负责调 IO 拿元数据，不碰文件内容
            val prepared = withContext(Dispatchers.IO) {
                uris.map { uri -> uri to queryUriMeta(uri) }
            }

            var queuedCount = 0
            var failedCount = 0
            for ((uri, meta) in prepared) {
                try {
                    com.dkc.fileserverclient.transfer.TransferManager.enqueueUploadUri(
                        serverUrl = currentServerUrl,
                        uri = uri,
                        fileName = meta.first,
                        fileSize = meta.second,
                        remoteDir = imageGalleryPath
                    )
                    queuedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "入队失败: $uri", e)
                    failedCount++
                }
            }

            val msg = buildString {
                append("已加入队列 $queuedCount / ${uris.size} 个")
                if (failedCount > 0) append("，$failedCount 个失败")
            }
            Toast.makeText(this@ImageGalleryActivity, msg, Toast.LENGTH_LONG).show()
            startQueuePolling()
            statusText.text = if (adapter.itemCount == 0) "无媒体文件" else "共 ${adapter.itemCount} 项"
        }
    }

    /**
     * 查询 URI 的 displayName 和 size。
     * 即使 query 失败也返回 fallback，保证文件不丢。
     */
    private fun queryUriMeta(uri: Uri): Pair<String, Long> {
        return try {
            var name: String? = null
            var size: Long = -1L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
            val finalName = name?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "upload_${System.currentTimeMillis()}"
            Pair(finalName, size)
        } catch (e: Exception) {
            Log.w(TAG, "queryUriMeta failed: $uri", e)
            val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "upload_${System.currentTimeMillis()}"
            Pair(fallback, -1L)
        }
    }

    private fun startQueuePolling() {
        pendingRefreshJob?.cancel()
        pendingRefreshJob = coroutineScope.launch {
            delay(5_000)
            if (currentTab == TAB_ALBUM) loadImages(currentSortBy, currentSortOrder)
        }
    }


    // ==================== 生命周期 ====================
    override fun onDestroy() {
        pendingRefreshJob?.cancel()
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}