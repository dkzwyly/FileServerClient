@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class TextLibraryDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton

    private val fileServerService by lazy { FileServerService(this) }
    private val itemList = mutableListOf<FileSystemItem>()
    private lateinit var adapter: TextLibraryDetailAdapter
    private var currentServerUrl = ""
    private var currentFolderPath = ""
    private var folderName = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val TAG = "TextLibraryDetailActivity"

        // 文本文件扩展名
        private val TEXT_EXTENSIONS = listOf(
            ".txt", ".log", ".json", ".xml", ".csv", ".md",
            ".html", ".htm", ".css", ".js", ".java", ".kt", ".py",
            ".cpp", ".c", ".h", ".php", ".rb", ".go", ".rs",
            ".doc", ".docx", ".pdf", ".ppt", ".pptx", ".xls", ".xlsx"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_library_detail)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentFolderPath = intent.getStringExtra("FOLDER_PATH") ?: ""
        folderName = intent.getStringExtra("FOLDER_NAME") ?: ""

        if (currentServerUrl.isEmpty() || currentFolderPath.isEmpty()) {
            finish()
            return
        }

        initViews()
        loadContent()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.textLibraryDetailRecyclerView)
        statusText = findViewById(R.id.textLibraryDetailStatusText)
        titleText = findViewById(R.id.textLibraryDetailTitleText)
        backButton = findViewById(R.id.backButton)

        titleText.text = folderName

        // 设置返回按钮
        backButton.setOnClickListener {
            finish()
        }

        // 使用网格布局，每行3个
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        adapter = TextLibraryDetailAdapter(currentServerUrl, itemList) { item ->
            onItemClicked(item)
        }
        recyclerView.adapter = adapter
    }

    private fun loadContent() {
        coroutineScope.launch {
            statusText.text = "正在加载内容..."

            try {
                Log.d(TAG, "开始加载目录: $currentFolderPath")

                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, currentFolderPath)
                }

                Log.d(TAG, "获取到 ${allItems.size} 个项目")

                // 过滤出文件夹和文本文件，同时过滤掉".."文件夹
                itemList.clear()
                itemList.addAll(allItems.filter { item ->
                    (item.isDirectory || isTextFile(item)) && item.name != ".."
                })

                Log.d(TAG, "过滤后得到 ${itemList.size} 个项目")

                if (itemList.isEmpty()) {
                    statusText.text = "没有找到内容"
                } else {
                    statusText.text = "共找到 ${itemList.size} 个项目"
                    adapter.notifyDataSetChanged()
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载内容异常", e)
            }
        }
    }

    private fun isTextFile(item: FileSystemItem): Boolean {
        return TEXT_EXTENSIONS.any { item.name.endsWith(it, ignoreCase = true) }
    }

    private fun onItemClicked(item: FileSystemItem) {
        if (item.isDirectory) {
            // 递归进入子目录
            val intent = Intent(this, TextLibraryDetailActivity::class.java).apply {
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("FOLDER_PATH", item.path)
                putExtra("FOLDER_NAME", item.name)
            }
            startActivity(intent)
        } else {
            // 预览文本文件
            previewTextFile(item)
        }
    }

    private fun previewTextFile(textItem: FileSystemItem) {
        try {
            val encodedPath = java.net.URLEncoder.encode(textItem.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            Log.d(TAG, "预览文本: ${textItem.name}, URL: $fileUrl")

            val intent = Intent(this, TextPreviewActivity::class.java).apply {
                putExtra("FILE_NAME", textItem.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_PATH", textItem.path)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "预览文本失败", e)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        Log.d(TAG, "onDestroy")
    }
}