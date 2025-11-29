@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class ImageGalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton

    private val fileServerService by lazy { FileServerService(this) }
    private val imageList = mutableListOf<FileSystemItem>()
    private lateinit var adapter: ImageGalleryAdapter
    private var currentServerUrl = ""

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

        titleText.text = "图片库"

        // 设置返回按钮
        backButton.setOnClickListener {
            finish()
        }

        // 使用网格布局，每行3个图片
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        adapter = ImageGalleryAdapter(currentServerUrl, imageList) { imageItem ->
            previewImage(imageItem)
        }
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
                putExtra("FILE_PATH", imageItem.path)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "预览图片失败", e)
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