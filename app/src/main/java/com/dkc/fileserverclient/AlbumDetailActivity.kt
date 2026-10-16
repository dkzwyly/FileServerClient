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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var addImageButton: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectedCountText: TextView
    private lateinit var removeSelectedButton: Button

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

    // 拍照 / 录像
    private var currentPhotoFile: File? = null
    private var currentVideoFile: File? = null

    companion object {
        private const val TAG = "AlbumDetailActivity"
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

    private fun initViews() {
        recyclerView = findViewById(R.id.detailRecyclerView)
        statusText = findViewById(R.id.detailStatusText)
        titleText = findViewById(R.id.detailTitleText)
        backButton = findViewById(R.id.detailBackButton)
        addImageButton = findViewById(R.id.addImageButton)
        selectionToolbar = findViewById(R.id.detailSelectionToolbar)
        selectedCountText = findViewById(R.id.detailSelectedCountText)
        removeSelectedButton = findViewById(R.id.detailRemoveSelectedButton)

        backButton.setOnClickListener {
            if (isMultiSelectionMode) exitMultiSelectionMode() else finish()
        }

        addImageButton.setOnClickListener { showAddMediaOptions() }
        removeSelectedButton.setOnClickListener { removeSelectedMedia() }

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
                if (isMultiSelectionMode) {
                    toggleItemSelection(item)
                } else {
                    // ★ 视频走 GalleryVideoPlayerActivity，图片走 ImageActivity
                    if (item.isVideo) previewVideo(item) else previewImage(item)
                }
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

    private fun loadAlbumMedia() {
        val paths = currentAlbum?.imagePaths ?: emptyList()
        if (paths.isEmpty()) {
            statusText.text = "相册为空，点击右上角添加"
            adapter.submitList(emptyList())
            return
        }
        coroutineScope.launch {
            statusText.text = "加载中..."
            try {
                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryRoot, "name", "asc")
                }
                // ★ 同时收录图片和视频
                val allMediaMap = allItems
                    .filter { !it.isDirectory && (it.isImage || it.isVideo) }
                    .associateBy { it.path }

                val validItems = paths.mapNotNull { allMediaMap[it] }
                if (validItems.isEmpty()) {
                    statusText.text = "相册中的媒体已失效"
                    adapter.submitList(emptyList())
                    return@launch
                }

                // 只请求缺失的日期
                val missingPaths = paths.filter { !dateCache.containsKey(it) }
                if (missingPaths.isNotEmpty()) {
                    val newDates = withContext(Dispatchers.IO) {
                        fileServerService.getBatchDateTaken(currentServerUrl, missingPaths)
                    }
                    dateCache.putAll(newDates)
                }

                // ★ 排序：EXIF dateTaken 优先，缺省用 lastModified
                val sortedItems = validItems.sortedWith(
                    compareByDescending { item ->
                        val dt = dateCache[item.path]
                        if (!dt.isNullOrEmpty()) dt else item.lastModified
                    }
                )

                val grouped = groupByDateTaken(sortedItems, dateCache)
                adapter.submitList(grouped)

                val imageCount = sortedItems.count { it.isImage }
                val videoCount = sortedItems.count { it.isVideo }
                statusText.text = when {
                    videoCount == 0 -> "共 $imageCount 张"
                    imageCount == 0 -> "共 $videoCount 个视频"
                    else -> "图片 $imageCount · 视频 $videoCount"
                }
            } catch (e: Exception) {
                statusText.text = "加载失败"
                Log.e(TAG, "loadAlbumMedia error", e)
            }
        }
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
                addPathsToAlbum(paths)
            }
        }

    private fun pickFromServerImages() {
        val intent = Intent(this, ImagePickerActivity::class.java).apply {
            putExtra(ImageGalleryActivity.EXTRA_SERVER_URL, currentServerUrl)
        }
        galleryPickerLauncher.launch(intent)
    }

    // -------- 2. 从本地选择（图片 + 视频）--------
    private val localPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val uris = if (data.clipData != null) {
                (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }
            } else if (data.data != null) {
                listOf(data.data!!)
            } else emptyList()
            if (uris.isNotEmpty()) uploadAndAddToAlbum(uris)
        }

    private fun pickFromLocal() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        localPickerLauncher.launch(intent)
    }

    // -------- 3. 拍照 --------
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentPhotoFile?.let { uploadAndAddSingle(it) }
            } else {
                currentPhotoFile?.delete()
                currentPhotoFile = null
            }
        }

    private fun capturePhotoAndAdd() {
        if (!ensureCameraPermissions()) return

        val photoFile = createImageFile()
        if (photoFile == null) {
            Toast.makeText(this, "无法创建图片文件", Toast.LENGTH_SHORT).show()
            return
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

    // -------- 4. 录像 --------
    private val takeVideoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentVideoFile?.let { uploadAndAddSingle(it) }
            } else {
                currentVideoFile?.delete()
                currentVideoFile = null
            }
        }

    private fun captureVideoAndAdd() {
        if (!ensureCameraPermissions()) return

        val videoFile = createVideoFile()
        if (videoFile == null) {
            Toast.makeText(this, "无法创建视频文件", Toast.LENGTH_SHORT).show()
            return
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

    // -------- 相机权限检查（拍照 + 录像共用）--------
    private fun ensureCameraPermissions(): Boolean {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)

        if (need.isEmpty()) return true

        // 弹一次性权限请求，无论结果都先返回 false，让用户重试
        permissionLauncher.launch(need.toTypedArray())
        return false
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (!perms.values.all { it }) {
                Toast.makeText(this, "需要相机和麦克风权限", Toast.LENGTH_SHORT).show()
            }
        }

    // ==================== 上传并添加到相册 ====================

    // 单文件（拍照 / 录像）
    private fun uploadAndAddSingle(file: File) {
        coroutineScope.launch {
            statusText.text = "上传中..."
            try {
                val result = withContext(Dispatchers.IO) {
                    fileServerService.uploadFiles(
                        currentServerUrl,
                        listOf(file to file.name),
                        imageGalleryPath        // ★ 统一到 data/图片
                    )
                }
                if (result.success) {
                    val serverPath = "$imageGalleryPath/${file.name}"
                    addPathToAlbum(serverPath)
                    Toast.makeText(this@AlbumDetailActivity, "上传成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AlbumDetailActivity, "上传失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AlbumDetailActivity, "上传异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                currentPhotoFile = null
                currentVideoFile = null
            }
        }
    }

    // 多文件（本地选择）
    private fun uploadAndAddToAlbum(uris: List<Uri>) {
        coroutineScope.launch {
            statusText.text = "上传中 (0/${uris.size})..."
            val successPaths = mutableListOf<String>()
            uris.forEachIndexed { idx, uri ->
                val tempFile = createTempMediaFile(uri) ?: return@forEachIndexed
                try {
                    val result = withContext(Dispatchers.IO) {
                        fileServerService.uploadFiles(
                            currentServerUrl,
                            listOf(tempFile to tempFile.name),
                            imageGalleryPath
                        )
                    }
                    if (result.success) {
                        successPaths.add("$imageGalleryPath/${tempFile.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "upload error", e)
                } finally {
                    tempFile.delete()
                }
                statusText.text = "上传中 (${idx + 1}/${uris.size})..."
            }
            if (successPaths.isNotEmpty()) {
                addPathsToAlbum(successPaths)
                Toast.makeText(this@AlbumDetailActivity, "上传成功 ${successPaths.size} 个", Toast.LENGTH_SHORT).show()
            }
            statusText.text = "共 ${currentAlbum?.imagePaths?.size ?: 0} 项"
        }
    }

    // ★ 保留正确扩展名（图片 / 视频）
    private fun createTempMediaFile(uri: Uri): File? {
        return try {
            val mime = contentResolver.getType(uri) ?: ""
            val displayName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
            val extFromName = displayName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
            val ext = extFromName ?: when {
                mime.startsWith("video/") -> mime.substringAfter("video/").takeIf { it.isNotEmpty() } ?: "mp4"
                mime.startsWith("image/") -> mime.substringAfter("image/").takeIf { it.isNotEmpty() } ?: "jpg"
                else -> "bin"
            }
            contentResolver.openInputStream(uri)?.use { input ->
                val f = File.createTempFile("upload_", ".$ext", cacheDir)
                f.outputStream().use { input.copyTo(it) }
                f
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建临时文件失败", e); null
        }
    }

    // ==================== 相册数据维护 ====================
    private fun addPathToAlbum(path: String) = addPathsToAlbum(listOf(path))

    /**
     * 增量添加路径：只请求缺失的日期，保存后交给 loadAlbumMedia 统一刷新排序
     */
    private fun addPathsToAlbum(paths: List<String>) {
        if (paths.isEmpty() || currentAlbum == null) return

        coroutineScope.launch {
            statusText.text = "更新相册..."
            try {
                val newPaths = paths.filter { !currentAlbum!!.imagePaths.contains(it) }
                if (newPaths.isEmpty()) {
                    statusText.text = "已存在，无需添加"
                    return@launch
                }

                // 只请求新增路径的日期（视频会返回 null，loadAlbumMedia 里会回退 lastModified）
                val newDateMap = withContext(Dispatchers.IO) {
                    fileServerService.getBatchDateTaken(currentServerUrl, newPaths)
                }
                dateCache.putAll(newDateMap)

                currentAlbum!!.imagePaths.addAll(newPaths)
                saveAlbum()

                // 交给 loadAlbumMedia 统一排序 + 分组，避免这里重复逻辑
                loadAlbumMedia()

                Toast.makeText(this@AlbumDetailActivity, "已添加 ${newPaths.size} 项", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "增量更新相册失败", e)
                statusText.text = "更新失败"
                Toast.makeText(this@AlbumDetailActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
        if (selectedItems.contains(item.path)) selectedItems.remove(item.path) else selectedItems.add(item.path)
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
        // 只把相册中的图片路径传给 ImageActivity（图片幻灯片）
        val imagePaths = mutableListOf<String>()
        for (g in adapter.currentList) {
            if (g is GalleryItem.ImageEntry && !g.image.isVideo) {
                imagePaths.add(g.image.path)
            }
        }
        val currentIndex = imagePaths.indexOf(item.path)
        if (currentIndex == -1) {
            // 理论上不会发生，兜底：单独预览
            imagePaths.add(item.path)
        }
        val idx = imagePaths.indexOf(item.path).coerceAtLeast(0)

        try {
            val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
            val url = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encoded"
            startActivity(Intent(this, ImageActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", url)
                putExtra("FILE_PATH", item.path)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", imageGalleryPath)
                putStringArrayListExtra("IMAGE_LIST", ArrayList(imagePaths))
                putExtra("CURRENT_INDEX", idx)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun previewVideo(item: FileSystemItem) {
        try {
            val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
            val streamUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/stream/$encoded"
            // ★ 使用相册专用播放器，不复用 VideoPlayerActivityV2
            startActivity(Intent(this, GalleryVideoPlayerActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", streamUrl)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}