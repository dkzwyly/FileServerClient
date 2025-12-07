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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageGalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var deleteSelectedButton: ImageButton
    private lateinit var selectionToolbar: View
    private lateinit var selectedCountText: TextView
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button

    private val fileServerService by lazy { FileServerService(this) }
    private val imageList = mutableListOf<FileSystemItem>()
    private lateinit var adapter: ImageGalleryAdapter
    private var currentServerUrl = ""
    private var isMultiSelectionMode = false
    private val selectedItems = mutableSetOf<String>() // 存储选中图片的路径
    private var isAllSelected = false

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val imageGalleryPath = "data/图片"  // 根据日志，图片目录在这里

    // 相机相关变量
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
        Log.d(TAG, "onResume")
        // 确保从其他页面返回时数据是最新的
        refreshImagesIfNeeded()
    }

    private fun refreshImagesIfNeeded() {
        // 这里可以根据需要添加刷新逻辑
        // 例如，可以设置一个标志位来判断是否需要刷新
        // 目前先注释掉，避免频繁刷新
        // loadImages()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.galleryRecyclerView)
        statusText = findViewById(R.id.galleryStatusText)
        titleText = findViewById(R.id.galleryTitleText)
        backButton = findViewById(R.id.backButton)
        cameraButton = findViewById(R.id.cameraButton)
        deleteSelectedButton = findViewById(R.id.deleteSelectedButton)
        selectionToolbar = findViewById(R.id.selectionToolbar)
        selectedCountText = findViewById(R.id.selectedCountText)
        selectAllButton = findViewById(R.id.selectAllButton)
        cancelSelectionButton = findViewById(R.id.cancelSelectionButton)

        titleText.text = "图片库"

        // 设置返回按钮
        backButton.setOnClickListener {
            if (isMultiSelectionMode) {
                exitMultiSelectionMode()
            } else {
                finish()
            }
        }

        // 相机按钮 - 拍照并上传
        cameraButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        // 多选删除按钮
        deleteSelectedButton.setOnClickListener {
            showDeleteConfirmation()
        }

        // 全选按钮
        selectAllButton.setOnClickListener {
            toggleSelectAll()
        }

        // 取消选择按钮
        cancelSelectionButton.setOnClickListener {
            exitMultiSelectionMode()
        }

        // 使用网格布局，每行3个图片
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        adapter = ImageGalleryAdapter(currentServerUrl,
            isMultiSelectionMode = { isMultiSelectionMode },
            isItemSelected = { itemPath -> selectedItems.contains(itemPath) },
            onImageClick = { imageItem ->
                if (isMultiSelectionMode) {
                    // 多选模式下，点击切换选中状态
                    toggleItemSelection(imageItem)
                } else {
                    // 普通模式下，预览图片
                    previewImage(imageItem)
                }
            },
            onImageLongClick = { imageItem ->
                // 长按进入多选模式
                if (!isMultiSelectionMode) {
                    enterMultiSelectionMode()
                }
                // 选中长按的项
                toggleItemSelection(imageItem)
            }
        )
        recyclerView.adapter = adapter
    }

    private fun loadImages() {
        coroutineScope.launch {
            statusText.text = "正在加载图片..."

            try {
                Log.d(TAG, "开始加载图片目录: $imageGalleryPath")

                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryPath)
                }

                Log.d(TAG, "获取到 ${allItems.size} 个项目")

                // 过滤出图片文件
                val newImageList = allItems.filter { item ->
                    !item.isDirectory && item.isImage
                }

                Log.d(TAG, "过滤后得到 ${newImageList.size} 张图片")

                // 使用DiffUtil更新列表，避免UI闪烁
                adapter.submitList(newImageList)

                if (newImageList.isEmpty()) {
                    statusText.text = "没有找到图片文件"
                } else {
                    statusText.text = "共找到 ${newImageList.size} 张图片"

                    // 显示第一张图片的信息用于调试
                    if (newImageList.isNotEmpty()) {
                        val firstImage = newImageList[0]
                        Log.d(TAG, "第一张图片: ${firstImage.name}, 路径: ${firstImage.path}, 有缩略图: ${firstImage.hasThumbnail}")
                    }
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载图片异常", e)
            }
        }
    }

    @UnstableApi private fun previewImage(imageItem: FileSystemItem) {
        try {
            val encodedPath = java.net.URLEncoder.encode(imageItem.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            Log.d(TAG, "预览图片: ${imageItem.name}, URL: $fileUrl")

            val intent = Intent(this, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", imageItem.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", "image")
                putExtra("FILE_PATH", imageItem.path) // 添加完整路径
                // 图片不设置自动连播
                putExtra("AUTO_PLAY_ENABLED", false)
                putExtra("SERVER_URL", currentServerUrl)
                // 对于图片库，当前路径就是图片目录
                putExtra("CURRENT_PATH", imageGalleryPath)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "预览图片失败", e)
        }
    }

    // ==================== 相机拍照上传功能 ====================

    // 简化权限检查 - 一次性检查所有所需权限
    private fun checkAndRequestPermissions() {
        // Android 13+ 需要单独请求媒体权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 请求相机权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                // 已经有相机权限，检查是否有文件访问权限
                if (checkStoragePermissions()) {
                    takePhoto()
                }
            }
        } else {
            // Android 12 及以下，一次性请求所有权限
            val permissionsToRequest = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.CAMERA)
            }

            // 检查存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (permissionsToRequest.isEmpty()) {
                // 已有所有权限，直接拍照
                takePhoto()
            } else {
                // 请求缺失的权限
                requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    // Android 13+ 相机权限请求
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // 相机权限已授予，检查存储权限
            if (checkStoragePermissions()) {
                takePhoto()
            }
        } else {
            Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 12 及以下的多权限请求
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            // 所有权限已授予，拍照
            takePhoto()
        } else {
            Toast.makeText(this, "需要相机和存储权限才能拍照", Toast.LENGTH_SHORT).show()
        }
    }

    // 检查存储权限
    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 不需要额外的存储权限来访问应用私有目录
            true
        } else {
            // Android 12 及以下，检查存储权限
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun takePhoto() {
        // 创建图片文件
        val photoFile = createImageFile()
        if (photoFile == null) {
            Toast.makeText(this, "无法创建图片文件", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存文件路径，用于后续上传
        currentPhotoPath = photoFile.absolutePath
        currentPhotoFile = photoFile

        // 创建FileProvider的Uri
        val photoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider", // 需要在AndroidManifest.xml中配置FileProvider
            photoFile
        )

        // 启动相机Intent
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        // 确保有可用的相机应用
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            takePictureLauncher.launch(takePictureIntent)
        } else {
            Toast.makeText(this, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File? {
        try {
            // 创建文件名
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "IMG_${timeStamp}"

            // 获取存储目录
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)

            // 创建文件
            val imageFile = File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
            )

            return imageFile
        } catch (e: Exception) {
            Log.e(TAG, "创建图片文件失败", e)
            return null
        }
    }

    // 使用Activity Result API处理相机结果
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 拍照成功，上传图片
            currentPhotoFile?.let { photoFile ->
                uploadPhotoToServer(photoFile)
            }
        } else {
            // 用户取消了拍照
            currentPhotoFile?.delete() // 删除临时文件
            currentPhotoFile = null
        }
    }

    private fun uploadPhotoToServer(photoFile: File) {
        if (!photoFile.exists()) {
            Toast.makeText(this, "照片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            statusText.text = "正在上传照片..."

            try {
                Log.d(TAG, "开始上传照片: ${photoFile.name} 到目录: $imageGalleryPath")

                // 使用现有的uploadFiles方法上传单个文件
                val uploadResult = withContext(Dispatchers.IO) {
                    // 创建Pair列表，包含文件和原始文件名
                    val files = listOf(Pair(photoFile, photoFile.name))
                    fileServerService.uploadFiles(currentServerUrl, files, imageGalleryPath)
                }

                if (uploadResult.success) {
                    statusText.text = "照片上传成功"
                    Toast.makeText(this@ImageGalleryActivity, "照片上传成功", Toast.LENGTH_SHORT).show()

                    // 完全重新加载图片列表
                    loadImages()

                } else {
                    statusText.text = "照片上传失败: ${uploadResult.message}"
                    Toast.makeText(this@ImageGalleryActivity, "照片上传失败: ${uploadResult.message}", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                statusText.text = "上传异常: ${e.message}"
                Log.e(TAG, "上传照片异常", e)
                Toast.makeText(this@ImageGalleryActivity, "上传异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes.toDouble() / 1024)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
            else -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }

    // 进入多选模式
    private fun enterMultiSelectionMode() {
        isMultiSelectionMode = true
        selectionToolbar.visibility = View.VISIBLE
        deleteSelectedButton.visibility = View.VISIBLE
        deleteSelectedButton.isEnabled = false
        selectedItems.clear()
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    // 退出多选模式
    private fun exitMultiSelectionMode() {
        isMultiSelectionMode = false
        selectionToolbar.visibility = View.GONE
        deleteSelectedButton.visibility = View.GONE
        selectedItems.clear()
        isAllSelected = false
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    // 切换项的选择状态
    private fun toggleItemSelection(imageItem: FileSystemItem) {
        val itemPath = imageItem.path
        if (selectedItems.contains(itemPath)) {
            selectedItems.remove(itemPath)
        } else {
            selectedItems.add(itemPath)
        }
        updateSelectionUI()

        // 找到当前列表中对应的位置并通知更新
        val currentList = adapter.currentList
        val position = currentList.indexOfFirst { it.path == itemPath }
        if (position != -1) {
            adapter.notifyItemChanged(position, "selection")
        }
    }

    // 全选/取消全选
    private fun toggleSelectAll() {
        if (isAllSelected) {
            // 取消全选
            selectedItems.clear()
            isAllSelected = false
        } else {
            // 全选
            selectedItems.clear()
            selectedItems.addAll(imageList.map { it.path })
            isAllSelected = true
        }
        updateSelectionUI()
        adapter.notifyDataSetChanged()
    }

    // 更新选择UI
    private fun updateSelectionUI() {
        selectedCountText.text = "已选择 ${selectedItems.size} 项"
        deleteSelectedButton.isEnabled = selectedItems.isNotEmpty()
        selectAllButton.text = if (isAllSelected) "取消全选" else "全选"
    }

    // 显示删除确认对话框
    private fun showDeleteConfirmation() {
        if (selectedItems.isEmpty()) return

        val message = if (selectedItems.size == 1) {
            "确定要删除选中的1张图片吗？"
        } else {
            "确定要删除选中的${selectedItems.size}张图片吗？"
        }

        AlertDialog.Builder(this)
            .setTitle("删除图片")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedImages()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 删除选中的图片
    private fun deleteSelectedImages() {
        if (selectedItems.isEmpty()) return

        coroutineScope.launch {
            statusText.text = "正在删除 ${selectedItems.size} 张图片..."

            var successCount = 0
            var failCount = 0

            // 逐个删除选中的图片
            for (itemPath in selectedItems) {
                try {
                    val success = withContext(Dispatchers.IO) {
                        fileServerService.deleteFile(currentServerUrl, itemPath)
                    }

                    if (success) {
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除图片失败: $itemPath", e)
                    failCount++
                }
            }

            // 重新加载图片列表
            loadImages()

            // 退出多选模式
            exitMultiSelectionMode()

            // 显示结果
            val resultMessage = StringBuilder()
            resultMessage.append("删除完成：")
            if (successCount > 0) {
                resultMessage.append("成功删除 $successCount 张图片")
            }
            if (failCount > 0) {
                if (successCount > 0) resultMessage.append("，")
                resultMessage.append("$failCount 张删除失败")
            }

            Toast.makeText(this@ImageGalleryActivity, resultMessage.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        // 清理适配器资源
        adapter.dispose()
        Log.d(TAG, "onDestroy")
    }
}
