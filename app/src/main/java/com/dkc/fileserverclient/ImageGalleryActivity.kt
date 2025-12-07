@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

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

    companion object {
        private const val TAG = "ImageGalleryActivity"

        // 图片文件扩展名
        private val IMAGE_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".JPG", ".JPEG", ".PNG", ".GIF", ".BMP", ".WEBP"
        )
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

        // 相机按钮（备用，不执行实际操作）
        cameraButton.setOnClickListener {
            // 暂时不做任何操作，可添加相机相关功能
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

        adapter = ImageGalleryAdapter(currentServerUrl, imageList,
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
                imageList.clear()
                imageList.addAll(allItems.filter { item ->
                    !item.isDirectory && isImageFile(item)
                })

                Log.d(TAG, "过滤后得到 ${imageList.size} 张图片")

                if (imageList.isEmpty()) {
                    statusText.text = "没有找到图片文件"
                } else {
                    statusText.text = "共找到 ${imageList.size} 张图片"
                    adapter.notifyDataSetChanged()

                    // 显示第一张图片的信息用于调试
                    if (imageList.isNotEmpty()) {
                        val firstImage = imageList[0]
                        Log.d(TAG, "第一张图片: ${firstImage.name}, 路径: ${firstImage.path}, 有缩略图: ${firstImage.hasThumbnail}")
                    }
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载图片异常", e)
            }
        }
    }

    private fun isImageFile(item: FileSystemItem): Boolean {
        return IMAGE_EXTENSIONS.any { item.name.endsWith(it, ignoreCase = true) }
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
        adapter.notifyDataSetChanged()
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
                        // 从列表中移除已删除的项
                        imageList.removeAll { it.path == itemPath }
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除图片失败: $itemPath", e)
                    failCount++
                }
            }

            // 更新UI
            selectedItems.clear()
            exitMultiSelectionMode()
            adapter.notifyDataSetChanged()

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

            statusText.text = resultMessage.toString()

            // 如果没有图片了，显示提示
            if (imageList.isEmpty()) {
                statusText.text = "没有找到图片文件"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        // 清理适配器资源
        adapter.dispose()
        Log.d(TAG, "onDestroy")
    }
}