package com.dkc.fileserverclient

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    private val imageGalleryRoot = "data/图片"
    private val fileServerService by lazy { FileServerService(this) }
    private lateinit var adapter: ImageGalleryAdapter
    private var currentServerUrl = ""
    private var albumId = ""
    private var currentAlbum: Album? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val imageGalleryPath = "data/图片"

    // 多选移除相关
    private var isMultiSelectionMode = false
    private val selectedItems = mutableSetOf<String>()

    private val gson = Gson()
    private val PREFS_ALBUMS = "albums_prefs"
    private val KEY_ALBUMS_JSON = "albums_json"

    companion object {
        private const val TAG = "AlbumDetailActivity"
        private const val REQUEST_IMAGE_PICKER = 200
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

        addImageButton.setOnClickListener { showAddImageOptions() }

        removeSelectedButton.setOnClickListener { removeSelectedImages() }

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
                if (isMultiSelectionMode) toggleItemSelection(item)
                else previewImage(item)
            },
            onImageLongClick = { item ->
                if (!isMultiSelectionMode) enterMultiSelectionMode()
                toggleItemSelection(item)
            }
        )
        recyclerView.adapter = adapter
    }

    // 从本地存储加载当前相册
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
        loadAlbumImages()
    }

    // 根据本地路径列表加载图片信息（需要获取文件大小、修改时间等，从服务器获取单个文件信息）
    private fun loadAlbumImages() {
        val paths = currentAlbum?.imagePaths ?: emptyList()
        if (paths.isEmpty()) {
            statusText.text = "相册为空，点击右上角添加"
            adapter.submitList(emptyList())
            return
        }
        coroutineScope.launch {
            statusText.text = "加载中..."
            try {
                // 1. 获取图片根目录下所有文件（通常相册中的图片都来自该目录）
                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryRoot, "name", "asc")
                }
                val allImageMap = allItems
                    .filter { !it.isDirectory && it.isImage }
                    .associateBy { it.path }

                // 2. 筛选出相册中包含的图片（保持 paths 顺序以便后续排序）
                val validItems = paths.mapNotNull { allImageMap[it] }
                if (validItems.isEmpty()) {
                    statusText.text = "相册中的图片已失效"
                    adapter.submitList(emptyList())
                    return@launch
                }

                // 3. 批量获取拍摄日期
                val dateMap = withContext(Dispatchers.IO) {
                    fileServerService.getBatchDateTaken(currentServerUrl, paths)
                }

                // 4. 按拍摄日期降序排序（空日期排末尾）
                val sortedItems = validItems.sortedWith(compareByDescending { item ->
                    val date = dateMap[item.path] ?: ""
                    if (date.isBlank()) "0000" else date
                })

                // 5. 分组生成带日期头的列表
                val grouped = groupByDateTaken(sortedItems, dateMap)
                adapter.submitList(grouped)
                statusText.text = "共 ${sortedItems.size} 张"
            } catch (e: Exception) {
                statusText.text = "加载失败"
                Log.e(TAG, "loadAlbumImages error", e)
            }
        }
    }

    /**
     * 按拍摄日期分组，生成 GalleryItem 列表
     * @param images 已按日期排好序的图片列表
     * @param dateMap 路径 → 拍摄日期字符串 映射
     */
    private fun groupByDateTaken(
        images: List<FileSystemItem>,
        dateMap: Map<String, String?>
    ): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()
        var currentLabel: String? = null
        for (image in images) {
            val dateStr = dateMap[image.path]
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
                } catch (e: Exception) { "未知日期" }
            }
            if (label != currentLabel) {
                currentLabel = label
                result.add(GalleryItem.DateHeader(label))
            }
            result.add(GalleryItem.ImageEntry(image))
        }
        return result
    }

    // ==================== 添加图片选项 ====================
    private fun showAddImageOptions() {
        AlertDialog.Builder(this)
            .setTitle("添加图片")
            .setItems(arrayOf("从相册选择", "从本地添加", "拍照")) { _, which ->
                when (which) {
                    0 -> pickFromServerImages()
                    1 -> pickFromLocal()
                    2 -> takePhotoAndAdd()
                }
            }
            .show()
    }

    // 从服务器已有图片中选择并添加
    private fun pickFromServerImages() {
        // 启动 ImagePickerActivity 并获取返回结果
        val intent = Intent(this, ImagePickerActivity::class.java).apply {
            putExtra(ImageGalleryActivity.EXTRA_SERVER_URL, currentServerUrl)
        }
        startActivityForResult(intent, REQUEST_IMAGE_PICKER)
    }

    @Deprecated("Use ActivityResultContracts")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_PICKER && resultCode == Activity.RESULT_OK) {
            val selectedPaths = data?.getStringArrayListExtra(ImagePickerActivity.EXTRA_SELECTED_PATHS) ?: return
            addPathsToAlbum(selectedPaths)
        }
    }

    // 从本地相册选择上传
    private val localPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uris = if (data.clipData != null) {
                (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri }
            } else if (data.data != null) {
                listOf(data.data!!)
            } else emptyList()
            uploadAndAddToAlbum(uris)
        }
    }

    private fun pickFromLocal() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        localPickerLauncher.launch(intent)
    }

    // 拍照上传并添加
    private var currentPhotoFile: File? = null
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            currentPhotoFile?.let { uploadAndAddSingle(it) }
        } else {
            currentPhotoFile?.delete()
            currentPhotoFile = null
        }
    }

    private fun takePhotoAndAdd() {
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
        takePictureLauncher.launch(intent)
    }

    private fun createImageFile(): File? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "IMG_${timeStamp}"
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return try {
            File.createTempFile(fileName, ".jpg", storageDir)
        } catch (e: Exception) {
            null
        }
    }

    // 上传单个文件并添加到相册
    private fun uploadAndAddSingle(file: File) {
        coroutineScope.launch {
            statusText.text = "上传中..."
            try {
                val result = withContext(Dispatchers.IO) {
                    fileServerService.uploadFiles(currentServerUrl, listOf(file to file.name), imageGalleryPath)
                }
                if (result.success) {
                    val serverPath = "$imageGalleryPath/${file.name}"
                    addPathToAlbum(serverPath)
                    Toast.makeText(this@AlbumDetailActivity, "上传成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AlbumDetailActivity, "上传失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AlbumDetailActivity, "上传异常", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 上传多个本地文件
    private fun uploadAndAddToAlbum(uris: List<Uri>) {
        coroutineScope.launch {
            statusText.text = "上传中..."
            val successPaths = mutableListOf<String>()
            for (uri in uris) {
                val tempFile = createTempImageFile(uri) ?: continue
                try {
                    val result = withContext(Dispatchers.IO) {
                        fileServerService.uploadFiles(currentServerUrl, listOf(tempFile to tempFile.name), imageGalleryPath)
                    }
                    if (result.success) {
                        successPaths.add("$imageGalleryPath/${tempFile.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "upload error", e)
                } finally {
                    tempFile.delete()
                }
            }
            if (successPaths.isNotEmpty()) {
                addPathsToAlbum(successPaths)
                Toast.makeText(this@AlbumDetailActivity, "上传成功 ${successPaths.size} 张", Toast.LENGTH_SHORT).show()
            }
            statusText.text = "共 ${currentAlbum?.imagePaths?.size ?: 0} 张"
        }
    }

    private fun createTempImageFile(uri: Uri): File? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val tempFile = File.createTempFile("upload_", ".jpg", cacheDir)
                tempFile.outputStream().use { output -> input.copyTo(output) }
                tempFile
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 修改本地相册数据 ====================
    private fun addPathToAlbum(path: String) {
        currentAlbum?.imagePaths?.add(path)
        saveAlbum()
        loadAlbumImages()
    }

    private fun addPathsToAlbum(paths: List<String>) {
        currentAlbum?.imagePaths?.addAll(paths)
        saveAlbum()
        loadAlbumImages()
    }

    private fun removePathsFromAlbum(paths: Collection<String>) {
        currentAlbum?.imagePaths?.removeAll(paths)
        saveAlbum()
        loadAlbumImages()
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

    private fun removeSelectedImages() {
        AlertDialog.Builder(this)
            .setTitle("移除图片")
            .setMessage("确定从本相册移除选中的 ${selectedItems.size} 张图片吗？\n（不会删除服务器文件）")
            .setPositiveButton("移除") { _, _ ->
                removePathsFromAlbum(selectedItems)
                exitMultiSelectionMode()
                Toast.makeText(this, "已移除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun previewImage(item: FileSystemItem) {
        // 构建当前相册的图片路径列表（从 adapter 中提取）
        val imagePaths = mutableListOf<String>()
        for (galleryItem in adapter.currentList) {
            if (galleryItem is GalleryItem.ImageEntry) {
                imagePaths.add(galleryItem.image.path)
            }
        }
        val currentIndex = imagePaths.indexOf(item.path)
        if (currentIndex == -1) return

        try {
            val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
            val url = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encoded"
            startActivity(Intent(this, ImageActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", url)
                putExtra("FILE_PATH", item.path)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", imageGalleryPath) // 保留用于降级
                putStringArrayListExtra("IMAGE_LIST", ArrayList(imagePaths))
                putExtra("CURRENT_INDEX", currentIndex)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}