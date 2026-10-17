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
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 相册集详情页。
 *
 * 上传策略：
 *  - 从相册选择：路径已在服务器，直接加
 *  - 从本地/拍照/录像：通过 TransferManager 入队，乐观地把预期路径加入相册
 *
 * 缩略图策略（无 TTL、无轮询）：
 *  - 命中本地缓存（jpg/svg）直接用
 *  - 无缓存才请求一次
 *  - 用户下拉刷新时清空 SVG 占位图缓存，重新请求
 */
class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var addImageButton: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectedCountText: TextView
    private lateinit var removeSelectedButton: Button
    private lateinit var detailSwipeRefresh: SwipeRefreshLayout

    private val dateCache = mutableMapOf<String, String?>()

    private val imageGalleryRoot = "data/图片"
    private val fileServerService by lazy { FileServerService(this) }
    private lateinit var adapter: ImageGalleryAdapter
    private var currentServerUrl = ""
    private var albumId = ""
    private var currentAlbum: Album? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val imageGalleryPath = "data/图片"

    private var isMultiSelectionMode = false
    private val selectedItems = mutableSetOf<String>()

    private val gson = Gson()
    private val PREFS_ALBUMS = "albums_prefs"
    private val KEY_ALBUMS_JSON = "albums_json"

    private var currentPhotoFile: File? = null
    private var currentVideoFile: File? = null
    private var pendingRefreshJob: Job? = null
    private var lastLoadTime = 0L

    companion object {
        private const val TAG = "AlbumDetailActivity"

        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv",
            "m4v", "3gp", "ts", "rmvb", "wmv"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album_detail)

        currentServerUrl = intent.getStringExtra(ImageGalleryActivity.EXTRA_SERVER_URL) ?: ""
        albumId = intent.getStringExtra(ImageGalleryActivity.EXTRA_ALBUM_ID) ?: ""
        if (currentServerUrl.isEmpty() || albumId.isEmpty()) {
            finish()
            return
        }

        initViews()
        loadAlbumData()
    }

    override fun onResume() {
        super.onResume()
        // 从其它页面返回时，5 秒时间窗内不重复加载
        if (System.currentTimeMillis() - lastLoadTime > 5_000) {
            loadAlbumMedia()
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.detailRecyclerView)
        statusText = findViewById(R.id.detailStatusText)
        titleText = findViewById(R.id.detailTitleText)
        backButton = findViewById(R.id.detailBackButton)
        addImageButton = findViewById(R.id.addImageButton)
        selectionToolbar = findViewById(R.id.detailSelectionToolbar)
        selectedCountText = findViewById(R.id.detailSelectedCountText)
        removeSelectedButton = findViewById(R.id.detailRemoveSelectedButton)
        detailSwipeRefresh = findViewById(R.id.detailSwipeRefresh)

        backButton.setOnClickListener {
            if (isMultiSelectionMode) exitMultiSelectionMode() else finish()
        }
        addImageButton.setOnClickListener { showAddMediaOptions() }
        removeSelectedButton.setOnClickListener { removeSelectedMedia() }

        // ★ 下拉刷新：清空 SVG 缓存，重新请求缩略图
        detailSwipeRefresh.setOnRefreshListener {
            ThumbnailLoader.invalidateSvgCache()
            loadAlbumMedia(fromSwipe = true)
        }

        val gridLayoutManager = GridLayoutManager(this, 4)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    0 -> 4
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
                if (isMultiSelectionMode) toggleItemSelection(item) else previewImage(item)
            },
            onImageLongClick = { item ->
                if (!isMultiSelectionMode) enterMultiSelectionMode()
                toggleItemSelection(item)
            }
        )
        recyclerView.adapter = adapter
    }

    // ==================== 加载相册 ====================
    private fun loadAlbumData() {
        val json = getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
            .getString(KEY_ALBUMS_JSON, null)
        if (json.isNullOrEmpty()) {
            Toast.makeText(this, "相册数据为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val type = object : TypeToken<List<Album>>() {}.type
        val albums: List<Album> = gson.fromJson(json, type)
        currentAlbum = albums.find { it.id == albumId }
        if (currentAlbum == null) {
            Toast.makeText(this, "相册不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        titleText.text = currentAlbum!!.name
        loadAlbumMedia()
    }

    private fun loadAlbumMedia(fromSwipe: Boolean = false) {
        lastLoadTime = System.currentTimeMillis()

        val paths = currentAlbum?.imagePaths ?: emptyList()
        if (paths.isEmpty()) {
            statusText.text = "相册为空，点击右上角添加"
            adapter.submitList(emptyList())
            if (fromSwipe) detailSwipeRefresh.isRefreshing = false
            return
        }
        coroutineScope.launch {
            statusText.text = "加载中..."
            try {
                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryRoot, "name", "asc")
                }
                val allMediaMap = allItems
                    .filter { !it.isDirectory && (it.isImage || it.isVideo) }
                    .associateBy { it.path }

                val validItems = paths.mapNotNull { allMediaMap[it] }
                val validPathSet = validItems.map { it.path }.toSet()
                val pendingItems = paths
                    .filter { it !in validPathSet }
                    .map { toPendingFileSystemItem(it) }

                val displayItems = validItems + pendingItems

                if (displayItems.isEmpty()) {
                    statusText.text = "相册中的媒体已失效"
                    adapter.submitList(emptyList())
                    return@launch
                }

                // 只查缺失的日期
                val missingPaths = validItems
                    .map { it.path }
                    .filter { !dateCache.containsKey(it) }
                if (missingPaths.isNotEmpty()) {
                    val newDates = withContext(Dispatchers.IO) {
                        fileServerService.getBatchDateTaken(currentServerUrl, missingPaths)
                    }
                    dateCache.putAll(newDates)
                }

                // 排序：EXIF dateTaken 优先，缺省用 lastModified
                val sortedItems = displayItems.sortedWith(
                    compareByDescending { item ->
                        val dt = dateCache[item.path]
                        if (!dt.isNullOrEmpty()) dt else item.lastModified
                    }
                )

                // 回写排序后的顺序，让相册集封面用最新的媒体
                val sortedPaths = sortedItems.map { it.path }
                if (sortedPaths != currentAlbum!!.imagePaths) {
                    currentAlbum!!.imagePaths.clear()
                    currentAlbum!!.imagePaths.addAll(sortedPaths)
                    saveAlbum()
                }

                val grouped = groupByDateTaken(sortedItems, dateCache)
                adapter.submitList(grouped)

                // ★ 不再全量 notifyItemChanged，交给 DiffUtil

                val imageCount = sortedItems.count { it.isImage }
                val videoCount = sortedItems.count { it.isVideo }
                val pendingCount = pendingItems.size

                statusText.text = buildString {
                    append(when {
                        videoCount == 0 && pendingCount == 0 -> "共 $imageCount 张"
                        imageCount == 0 && pendingCount == 0 -> "共 $videoCount 个视频"
                        else -> "图片 $imageCount · 视频 $videoCount"
                    })
                    if (pendingCount > 0) append(" · 上传中 $pendingCount")
                }
            } catch (e: Exception) {
                statusText.text = "加载失败"
                Log.e(TAG, "loadAlbumMedia error", e)
            } finally {
                if (fromSwipe) detailSwipeRefresh.isRefreshing = false
            }
        }
    }

    private fun toPendingFileSystemItem(path: String): FileSystemItem {
        val name = path.substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        val isVideo = ext in VIDEO_EXTS
        return FileSystemItem(
            name = name,
            path = path,
            size = 0,
            extension = ext,
            sizeFormatted = "",
            lastModified = "",
            isVideo = isVideo,
            isAudio = false,
            mimeType = if (isVideo) "video/*" else "image/*",
            encoding = ""
        )
    }

    private fun groupByDateTaken(
        images: List<FileSystemItem>,
        dateMap: Map<String, String?>
    ): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()
        var currentLabel: String? = null

        for (item in images) {
            val dateStr = dateMap[item.path]
            val isoDate: String? = when {
                !dateStr.isNullOrEmpty() -> dateStr.substringBefore("T").take(10)
                item.lastModified.isNotEmpty() -> item.lastModified.substringBefore("T").take(10)
                else -> null
            }

            val label: String = if (isoDate == null) {
                "未知日期"
            } else try {
                val cal = Calendar.getInstance().apply { time = dateFormat.parse(isoDate)!! }
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

    // ==================== 添加媒体 ====================
    private fun showAddMediaOptions() {
        val options = arrayOf("从相册选择", "从本地添加", "拍照", "录像")
        AlertDialog.Builder(this)
            .setTitle("添加媒体")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickFromServerImages()
                    1 -> pickFromLocal()
                    2 -> capturePhotoAndAdd()
                    3 -> captureVideoAndAdd()
                }
            }
            .show()
    }

    // -------- 1. 从服务器已有媒体中选择 --------
    private val galleryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val paths = result.data
                    ?.getStringArrayListExtra(ImagePickerActivity.EXTRA_SELECTED_PATHS)
                    ?: return@registerForActivityResult
                addPathsToAlbum(paths, queryDate = true)
            }
        }

    private fun pickFromServerImages() {
        val intent = Intent(this, ImagePickerActivity::class.java).apply {
            putExtra(ImageGalleryActivity.EXTRA_SERVER_URL, currentServerUrl)
        }
        galleryPickerLauncher.launch(intent)
    }

    // -------- 2. 从本地选择 → 走队列 --------
    private val localPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uris = if (data.clipData != null) {
                (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }
            } else if (data.data != null) {
                listOf(data.data!!)
            } else emptyList()
            if (uris.isNotEmpty()) enqueueLocalUploads(uris)
        }

    private fun pickFromLocal() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        localPickerLauncher.launch(intent)
    }

    private fun enqueueLocalUploads(uris: List<Uri>) {
        coroutineScope.launch {
            statusText.text = "加入上传队列 (${uris.size} 个)..."
            val expectedPaths = mutableListOf<String>()
            var queuedCount = 0

            for (uri in uris) {
                val tempFile = createTempMediaFile(uri) ?: continue
                try {
                    com.dkc.fileserverclient.transfer.TransferManager.enqueueUpload(
                        serverUrl = currentServerUrl,
                        localFile = tempFile,
                        remoteDir = imageGalleryPath
                    )
                    expectedPaths.add("$imageGalleryPath/${tempFile.name}")
                    queuedCount++
                } catch (e: Exception) {
                    Log.e(TAG, "入队失败", e)
                }
            }

            if (queuedCount > 0) {
                addPathsToAlbum(expectedPaths, showToast = false, queryDate = false)
                startPendingRefresh()
                Toast.makeText(
                    this@AlbumDetailActivity,
                    "已加入上传队列 $queuedCount 个",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@AlbumDetailActivity, "没有可上传的文件", Toast.LENGTH_SHORT).show()
            }
            statusText.text = "共 ${currentAlbum?.imagePaths?.size ?: 0} 项"
        }
    }

    // -------- 3. 拍照 → 走队列 --------
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentPhotoFile?.let { enqueueSingleUpload(it) }
            } else {
                currentPhotoFile?.delete()
                currentPhotoFile = null
            }
        }

    private fun capturePhotoAndAdd() {
        if (!ensureCameraPermissions()) return
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

    private fun createImageFile(): File? = try {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.apply { mkdirs() }
        File.createTempFile("IMG_$ts", ".jpg", dir)
    } catch (e: Exception) {
        Log.e(TAG, "创建图片文件失败", e); null
    }

    // -------- 4. 录像 → 走队列 --------
    private val takeVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentVideoFile?.let { enqueueSingleUpload(it) }
            } else {
                currentVideoFile?.delete()
                currentVideoFile = null
            }
        }

    private fun captureVideoAndAdd() {
        if (!ensureCameraPermissions()) return
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

    private fun createVideoFile(): File? = try {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.apply { mkdirs() }
        File.createTempFile("VID_$ts", ".mp4", dir)
    } catch (e: Exception) {
        Log.e(TAG, "创建视频文件失败", e); null
    }

    // -------- 拍照 / 录像共用：单文件入队 --------
    private fun enqueueSingleUpload(file: File) {
        coroutineScope.launch {
            statusText.text = "加入上传队列..."
            try {
                com.dkc.fileserverclient.transfer.TransferManager.enqueueUpload(
                    serverUrl = currentServerUrl,
                    localFile = file,
                    remoteDir = imageGalleryPath
                )
                val expectedPath = "$imageGalleryPath/${file.name}"
                addPathsToAlbum(listOf(expectedPath), showToast = false, queryDate = false)
                startPendingRefresh()
                Toast.makeText(this@AlbumDetailActivity, "已加入上传队列", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@AlbumDetailActivity, "入队失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                currentPhotoFile = null
                currentVideoFile = null
                statusText.text = "共 ${currentAlbum?.imagePaths?.size ?: 0} 项"
            }
        }
    }

    // -------- 相机权限 --------
    private fun ensureCameraPermissions(): Boolean {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isEmpty()) return true
        permissionLauncher.launch(need.toTypedArray())
        return false
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (!perms.values.all { it }) {
                Toast.makeText(this, "需要相机和麦克风权限", Toast.LENGTH_SHORT).show()
            }
        }

    // ==================== 相册数据维护 ====================

    private fun addPathsToAlbum(
        paths: List<String>,
        showToast: Boolean = true,
        queryDate: Boolean = true
    ) {
        if (paths.isEmpty() || currentAlbum == null) return

        coroutineScope.launch {
            try {
                val newPaths = paths.filter { !currentAlbum!!.imagePaths.contains(it) }
                if (newPaths.isEmpty()) {
                    if (showToast) {
                        Toast.makeText(
                            this@AlbumDetailActivity,
                            "已存在，无需添加",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                if (queryDate) {
                    val newDateMap = withContext(Dispatchers.IO) {
                        fileServerService.getBatchDateTaken(currentServerUrl, newPaths)
                    }
                    dateCache.putAll(newDateMap)
                }
                currentAlbum!!.imagePaths.addAll(newPaths)
                saveAlbum()
                loadAlbumMedia()
                if (showToast) {
                    Toast.makeText(
                        this@AlbumDetailActivity,
                        "已添加 ${newPaths.size} 项",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "增量更新相册失败", e)
            }
        }
    }

    private fun removePathsFromAlbum(paths: Collection<String>) {
        currentAlbum?.imagePaths?.removeAll(paths)
        saveAlbum()
        loadAlbumMedia()
    }

    private fun saveAlbum() {
        currentAlbum ?: return
        val json = getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
            .getString(KEY_ALBUMS_JSON, null)
        if (json.isNullOrEmpty()) return
        val type = object : TypeToken<MutableList<Album>>() {}.type
        val albums: MutableList<Album> = gson.fromJson(json, type)
        val index = albums.indexOfFirst { it.id == albumId }
        if (index != -1) {
            albums[index] = currentAlbum!!
            getSharedPreferences(PREFS_ALBUMS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ALBUMS_JSON, gson.toJson(albums))
                .apply()
        }
    }

    /**
     * 上传后延迟 5 秒刷一次列表，不循环。
     * 服务端上传通常几秒内完成，ffmpeg 生成缩略图可能需要更久，
     * 但用户手动下拉刷新可随时重新请求 SVG。
     */
    private fun startPendingRefresh() {
        pendingRefreshJob?.cancel()
        pendingRefreshJob = coroutineScope.launch {
            delay(5_000)
            loadAlbumMedia()
        }
    }

    // ==================== 多选移除 ====================
    private fun enterMultiSelectionMode() {
        isMultiSelectionMode = true
        selectionToolbar.visibility = View.VISIBLE
        selectedItems.clear()
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun exitMultiSelectionMode() {
        isMultiSelectionMode = false
        selectionToolbar.visibility = View.GONE
        selectedItems.clear()
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    private fun toggleItemSelection(item: FileSystemItem) {
        if (selectedItems.contains(item.path)) selectedItems.remove(item.path)
        else selectedItems.add(item.path)
        updateSelectionUI()
        val pos = adapter.currentList.indexOfFirst {
            it is GalleryItem.ImageEntry && it.image.path == item.path
        }
        if (pos != -1) adapter.notifyItemChanged(pos, "selection")
    }

    private fun updateSelectionUI() {
        selectedCountText.text = "已选择 ${selectedItems.size} 项"
        removeSelectedButton.isEnabled = selectedItems.isNotEmpty()
    }

    private fun removeSelectedMedia() {
        AlertDialog.Builder(this)
            .setTitle("移除媒体")
            .setMessage("确定从本相册移除选中的 ${selectedItems.size} 项吗？\n（不会删除服务器文件）")
            .setPositiveButton("移除") { _, _ ->
                removePathsFromAlbum(selectedItems)
                exitMultiSelectionMode()
                Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 预览 ====================
    private fun previewImage(item: FileSystemItem) {
        val allPaths = mutableListOf<String>()
        for (g in adapter.currentList) {
            if (g is GalleryItem.ImageEntry) allPaths.add(g.image.path)
        }
        val idx = allPaths.indexOf(item.path).coerceAtLeast(0)

        try {
            val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
            val url = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encoded"
            startActivity(Intent(this, ImageActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", url)
                putExtra("FILE_PATH", item.path)
                putExtra("SERVER_URL", currentServerUrl)
                putStringArrayListExtra("IMAGE_LIST", ArrayList(allPaths))
                putExtra("CURRENT_INDEX", idx)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 工具 ====================
    private fun createTempMediaFile(uri: Uri): File? {
        return try {
            val mime = contentResolver.getType(uri) ?: ""
            val displayName = queryDisplayName(uri)
            val extFromName = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            val ext = extFromName ?: when {
                mime.startsWith("video/") ->
                    mime.substringAfter("video/").takeIf { it.isNotEmpty() } ?: "mp4"
                mime.startsWith("image/") ->
                    mime.substringAfter("image/").takeIf { it.isNotEmpty() } ?: "jpg"
                else -> "bin"
            }
            val safeName = displayName ?: "upload_${System.currentTimeMillis()}.$ext"
            val tempDir = File(cacheDir, "upload_temp").apply { mkdirs() }
            val tempFile = File(tempDir, safeName)

            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "创建临时文件失败", e)
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        pendingRefreshJob?.cancel()
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}