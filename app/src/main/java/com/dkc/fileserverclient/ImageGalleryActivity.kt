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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 图片库 Activity，支持图片浏览、相册集管理、拍照上传、多选删除等。
 */
class ImageGalleryActivity : AppCompatActivity() {

    // ========== Views ==========
    private lateinit var recyclerView: RecyclerView          // 相册网格
    private lateinit var albumSetRecyclerView: RecyclerView  // 相册集列表
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var sortButton: ImageButton
    private lateinit var reindexButton: ImageButton         // 上传按钮（原重建功能替换）
    private lateinit var deleteSelectedButton: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectedCountText: TextView
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button
    private lateinit var tabAlbum: TextView
    private lateinit var tabAlbumSet: TextView
    private lateinit var addAlbumFab: FloatingActionButton

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

    // ========== 相册集相关 ==========
    private var currentTab = TAB_ALBUM
    private var albumList = mutableListOf<Album>()
    private lateinit var albumSetAdapter: AlbumSetAdapter  // 使用外部类
    private val gson = Gson()
    private val PREFS_ALBUMS = "albums_prefs"
    private val KEY_ALBUMS_JSON = "albums_json"

    // ========== 拍照相关 ==========
    private lateinit var currentPhotoPath: String
    private var currentPhotoFile: File? = null

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
        if (currentTab == TAB_ALBUM) {
            loadImages()
        } else {
            loadAlbumsFromPrefs()
            albumSetAdapter.notifyDataSetChanged()
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
        selectionToolbar = findViewById(R.id.selectionToolbar)
        selectedCountText = findViewById(R.id.selectedCountText)
        selectAllButton = findViewById(R.id.selectAllButton)
        cancelSelectionButton = findViewById(R.id.cancelSelectionButton)
        tabAlbum = findViewById(R.id.albumTab)
        tabAlbumSet = findViewById(R.id.albumSetTab)
        addAlbumFab = findViewById(R.id.addAlbumFab)

        titleText.text = "图片"

        // Tab 切换
        tabAlbum.setOnClickListener { switchTab(TAB_ALBUM) }
        tabAlbumSet.setOnClickListener { switchTab(TAB_ALBUM_SET) }

        // 返回 / 退出多选
        backButton.setOnClickListener {
            if (isMultiSelectionMode) exitMultiSelectionMode() else finish()
        }

        cameraButton.setOnClickListener { checkAndRequestPermissions() }
        sortButton.setOnClickListener { showSortDialog() }
        reindexButton.setOnClickListener { openFilePicker() }
        deleteSelectedButton.setOnClickListener { showDeleteConfirmation() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        cancelSelectionButton.setOnClickListener { exitMultiSelectionMode() }

        // 相册网格设置
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
            onImageClick = { imageItem ->
                if (isMultiSelectionMode) toggleItemSelection(imageItem)
                else previewImage(imageItem)
            },
            onImageLongClick = { imageItem ->
                if (!isMultiSelectionMode) enterMultiSelectionMode()
                toggleItemSelection(imageItem)
            }
        )
        recyclerView.adapter = adapter

        // 相册集列表设置（使用外部 AlbumSetAdapter）
        albumSetRecyclerView.layoutManager = LinearLayoutManager(this)
        albumSetAdapter = AlbumSetAdapter(
            serverUrl = currentServerUrl,  // 传入
            albums = albumList,
            onAlbumClick = { album ->
                val intent = Intent(this, AlbumDetailActivity::class.java).apply {
                    putExtra(EXTRA_ALBUM_ID, album.id)
                    putExtra(EXTRA_SERVER_URL, currentServerUrl)
                }
                startActivity(intent)
            },
            onAlbumLongClick = { album ->
                showDeleteAlbumDialog(album)
            }
        )
        albumSetRecyclerView.adapter = albumSetAdapter

        // 新建相册 FAB
        addAlbumFab.setOnClickListener { showCreateAlbumDialog() }
    }

    // ==================== Tab 切换 ====================
    private fun switchTab(tab: Int) {
        currentTab = tab
        if (tab == TAB_ALBUM) {
            // 切换到相册 Tab
            tabAlbum.background = resources.getDrawable(R.drawable.tab_background_selected, null)
            tabAlbum.setTextColor(resources.getColor(R.color.primary_color, null))
            tabAlbumSet.background = resources.getDrawable(R.drawable.tab_background, null)
            tabAlbumSet.setTextColor(resources.getColor(R.color.text_primary, null))

            recyclerView.visibility = View.VISIBLE
            albumSetRecyclerView.visibility = View.GONE
            addAlbumFab.visibility = View.GONE
            sortButton.visibility = View.VISIBLE
            reindexButton.visibility = View.VISIBLE
            cameraButton.visibility = View.VISIBLE      // 相册 Tab 显示相机
            if (isMultiSelectionMode) exitMultiSelectionMode()
            loadImages()
        } else {
            // 切换到相册集 Tab
            tabAlbumSet.background = resources.getDrawable(R.drawable.tab_background_selected, null)
            tabAlbumSet.setTextColor(resources.getColor(R.color.primary_color, null))
            tabAlbum.background = resources.getDrawable(R.drawable.tab_background, null)
            tabAlbum.setTextColor(resources.getColor(R.color.text_primary, null))

            recyclerView.visibility = View.GONE
            albumSetRecyclerView.visibility = View.VISIBLE
            addAlbumFab.visibility = View.VISIBLE
            sortButton.visibility = View.GONE
            reindexButton.visibility = View.GONE
            cameraButton.visibility = View.GONE           // 相册集 Tab 隐藏相机
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
            .setPositiveButton("删除") { _, _ ->
                deleteAlbum(album)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteAlbum(album: Album) {
        albumList.remove(album)
        saveAlbumsToPrefs()          // 保存到 SharedPreferences
        albumSetAdapter.notifyDataSetChanged()   // 刷新列表
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

    // ==================== 图片加载与排序 ====================
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

    private fun loadImages(sortBy: String = currentSortBy, sortOrder: String = currentSortOrder) {
        if (currentTab != TAB_ALBUM) return
        currentSortBy = sortBy
        currentSortOrder = sortOrder
        coroutineScope.launch {
            statusText.text = "加载中..."
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryPath, sortBy, sortOrder)
                }
                val images = items.filter { !it.isDirectory && it.isImage }

                if (sortBy == "dateTaken") {
                    val paths = images.map { it.path }
                    val dates = withContext(Dispatchers.IO) {
                        fileServerService.getBatchDateTaken(currentServerUrl, paths)
                    }
                    dateTakenMap.clear()
                    dateTakenMap.putAll(dates)
                    val grouped = groupByDateTaken(images)
                    adapter.submitList(grouped)
                } else {
                    val entries = images.map { GalleryItem.ImageEntry(it) }
                    adapter.submitList(entries)
                }
                statusText.text = if (images.isEmpty()) "无图片" else "共 ${images.size} 张"
            } catch (e: Exception) {
                statusText.text = "加载失败"
                Log.e(TAG, "loadImages error", e)
            }
        }
    }

    private fun groupByDateTaken(images: List<FileSystemItem>): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()
        var currentLabel: String? = null
        for (image in images) {
            val dateStr = dateTakenMap[image.path]
            val label = when {
                dateStr.isNullOrEmpty() -> "未知日期"
                else -> try {
                    val isoDate = dateStr.substringBefore("T")
                    val cal = Calendar.getInstance().apply {
                        time = dateFormat.parse(isoDate)!!
                    }
                    when {
                        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
                        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
                        else -> {
                            if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR))
                                SimpleDateFormat("M月d日", Locale.getDefault()).format(cal.time)
                            else
                                SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(cal.time)
                        }
                    }
                } catch (e: Exception) {
                    "未知日期"
                }
            }
            if (label != currentLabel) {
                currentLabel = label
                result.add(GalleryItem.DateHeader(label))
            }
            result.add(GalleryItem.ImageEntry(image))
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
        val message = if (selectedItems.size == 1) "确定删除选中的1张图片？" else "确定删除选中的${selectedItems.size}张图片？"
        AlertDialog.Builder(this)
            .setTitle("删除图片")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> deleteSelectedImages() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedImages() {
        coroutineScope.launch {
            statusText.text = "删除中..."
            var successCount = 0
            selectedItems.forEach { path ->
                try {
                    if (withContext(Dispatchers.IO) { fileServerService.deleteFile(currentServerUrl, path) }) successCount++
                } catch (e: Exception) { Log.e(TAG, "删除失败", e) }
            }
            loadImages()
            exitMultiSelectionMode()
            Toast.makeText(this@ImageGalleryActivity, "已删除 $successCount 张", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 预览图片 ====================
    private fun previewImage(item: FileSystemItem) {
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

    // ==================== 相机拍照 ====================
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                if (checkStoragePermissions()) takePhoto()
            }
        } else {
            val permissionsToRequest = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.CAMERA)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (permissionsToRequest.isEmpty()) takePhoto()
            else requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted && checkStoragePermissions()) takePhoto()
            else Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
        }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) takePhoto()
            else Toast.makeText(this, "需要相机和存储权限", Toast.LENGTH_SHORT).show()
        }

    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun takePhoto() {
        val photoFile = createImageFile()
        if (photoFile == null) {
            Toast.makeText(this, "无法创建图片文件", Toast.LENGTH_SHORT).show()
            return
        }
        currentPhotoPath = photoFile.absolutePath
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
                currentPhotoFile?.let { uploadPhotoToServer(it) }
            } else {
                currentPhotoFile?.delete()
                currentPhotoFile = null
            }
        }

    private fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "IMG_${timeStamp}"
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile(fileName, ".jpg", storageDir)
        } catch (e: Exception) {
            Log.e(TAG, "创建图片文件失败", e)
            null
        }
    }

    private fun uploadPhotoToServer(photoFile: File) {
        if (!photoFile.exists()) return
        coroutineScope.launch {
            statusText.text = "上传中..."
            try {
                val result = withContext(Dispatchers.IO) {
                    fileServerService.uploadFiles(currentServerUrl, listOf(photoFile to photoFile.name), imageGalleryPath)
                }
                if (result.success) {
                    Toast.makeText(this@ImageGalleryActivity, "上传成功", Toast.LENGTH_SHORT).show()
                    loadImages()
                } else {
                    Toast.makeText(this@ImageGalleryActivity, "上传失败: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ImageGalleryActivity, "上传异常", Toast.LENGTH_SHORT).show()
            }
            statusText.text = if (adapter.itemCount == 0) "无图片" else "共 ${adapter.itemCount} 张"
        }
    }

    // ==================== 从本地选择图片上传 ====================
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
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        filePickerLauncher.launch(intent)
    }

    private fun uploadSelectedFiles(uris: List<Uri>) {
        coroutineScope.launch {
            statusText.text = "准备上传 (${uris.size} 张)..."
            var successCount = 0
            val tempFiles = mutableListOf<File>()
            try {
                for (uri in uris) {
                    val tempFile = createTempImageFile(uri) ?: continue
                    tempFiles.add(tempFile)
                    val result = withContext(Dispatchers.IO) {
                        fileServerService.uploadFiles(
                            currentServerUrl,
                            listOf(tempFile to tempFile.name),
                            imageGalleryPath
                        )
                    }
                    if (result.success) successCount++
                }
                Toast.makeText(this@ImageGalleryActivity, "上传成功 $successCount / ${uris.size} 张", Toast.LENGTH_LONG).show()
                if (successCount > 0) loadImages()
            } catch (e: Exception) {
                Toast.makeText(this@ImageGalleryActivity, "上传异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                tempFiles.forEach { if (it.exists()) it.delete() }
                statusText.text = if (adapter.itemCount == 0) "无图片" else "共 ${adapter.itemCount} 张"
            }
        }
    }

    private fun createTempImageFile(uri: Uri): File? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val tempFile = File.createTempFile("upload_", ".jpg", cacheDir)
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                tempFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建临时文件失败", e)
            null
        }
    }

    // ==================== 生命周期 ====================
    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}