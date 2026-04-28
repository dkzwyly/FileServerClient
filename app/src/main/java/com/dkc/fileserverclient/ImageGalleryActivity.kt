@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ImageGalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var sortButton: ImageButton
    private lateinit var reindexButton: ImageButton
    private lateinit var deleteSelectedButton: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectedCountText: TextView
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button

    private val fileServerService by lazy { FileServerService(this) }
    private lateinit var adapter: ImageGalleryAdapter
    private var currentServerUrl = ""
    private var isMultiSelectionMode = false
    private val selectedItems = mutableSetOf<String>()
    private var isAllSelected = false

    private var currentSortBy = "dateTaken"
    private var currentSortOrder = "asc"

    // 日期映射缓存（仅 dateTaken 排序时使用）
    private val dateTakenMap = mutableMapOf<String, String?>()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val imageGalleryPath = "data/图片"

    private lateinit var currentPhotoPath: String
    private var currentPhotoFile: File? = null

    companion object {
        private const val TAG = "ImageGalleryActivity"
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
        loadImages()
    }

    override fun onResume() {
        super.onResume()
        loadImages()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.galleryRecyclerView)
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

        titleText.text = "图片库"

        backButton.setOnClickListener {
            if (isMultiSelectionMode) exitMultiSelectionMode() else finish()
        }

        cameraButton.setOnClickListener { checkAndRequestPermissions() }
        sortButton.setOnClickListener { showSortDialog() }
        reindexButton.setOnClickListener { startReindex() }
        deleteSelectedButton.setOnClickListener { showDeleteConfirmation() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        cancelSelectionButton.setOnClickListener { exitMultiSelectionMode() }

        val gridLayoutManager = GridLayoutManager(this, 4)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    // 日期头部占满 3 列
                    0 -> 4   // VIEW_TYPE_HEADER = 0
                    else -> 1 // 图片占 1 列
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
    }

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
                    // 1. 获取所有图片的拍摄日期
                    val paths = images.map { it.path }
                    val dates = withContext(Dispatchers.IO) {
                        fileServerService.getBatchDateTaken(currentServerUrl, paths)
                    }
                    dateTakenMap.clear()
                    dateTakenMap.putAll(dates)

                    // 2. 分组
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

    /**
     * 将图片按拍摄日期分组，生成带日期头的 GalleryItem 列表
     */
    private fun groupByDateTaken(images: List<FileSystemItem>): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()

        var currentLabel: String? = null
        for (image in images) {
            val dateStr = dateTakenMap[image.path]
            val label = if (dateStr.isNullOrEmpty()) {
                "未知日期"
            } else {
                try {
                    val isoDate = dateStr!!.substringBefore("T")
                    val cal = Calendar.getInstance().apply {
                        time = dateFormat.parse(isoDate)!!
                    }
                    when {
                        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
                        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
                        else -> {
                            if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
                                SimpleDateFormat("M月d日", Locale.getDefault()).format(cal.time)
                            } else {
                                SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(cal.time)
                            }
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

    // ---------- 原有方法（相机、多选、删除等）保持不变 ----------
    private fun startReindex() {
        reindexButton.isEnabled = false
        Toast.makeText(this, "请求重建元数据...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            try {
                val success = fileServerService.reindexPhotoMetadata(currentServerUrl)
                if (success) {
                    Toast.makeText(this@ImageGalleryActivity, "重建任务已启动，稍后刷新", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ImageGalleryActivity, "重建请求失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ImageGalleryActivity, "异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                delay(5000)
                reindexButton.isEnabled = true
            }
        }
    }

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
            })
        } catch (e: Exception) {
            Toast.makeText(this, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 相机权限及拍照（完全不变） ====================
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

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted && checkStoragePermissions()) takePhoto()
        else Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
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

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

    // ==================== 多选模式（完全不变） ====================
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
        // 查找在适配器中的位置并通知更新
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

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}