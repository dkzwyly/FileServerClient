@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class FileListActivity : AppCompatActivity() {

    private lateinit var pathLabel: TextView
    private lateinit var refreshButton: Button
    private lateinit var backButton: Button
    private lateinit var selectFilesButton: Button
    private lateinit var uploadButton: Button
    private lateinit var selectedFilesLabel: TextView
    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var filesRecyclerView: RecyclerView
    private lateinit var statusLabel: TextView
    private lateinit var uploadStatusCard: CardView
    private lateinit var fileCountText: TextView

    private val fileServerService by lazy { FileServerService(this) }
    private val fileList = mutableListOf<FileSystemItem>()
    private val selectedFiles = mutableListOf<File>()
    private val pathHistory = mutableListOf<String>()
    private var currentServerUrl = ""
    private var currentPath = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: FileListAdapter

    // 自动连播相关变量
    private var autoPlayEnabled = false
    private var currentPlayingIndex = -1
    private var mediaFileList = mutableListOf<FileSystemItem>()

    companion object {
        private const val PICK_FILES_REQUEST = 1001
        private const val REQUEST_PREVIEW = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }

        initViews()
        loadCurrentDirectory("")
    }

    private fun initViews() {
        pathLabel = findViewById(R.id.pathLabel)
        refreshButton = findViewById(R.id.refreshButton)
        backButton = findViewById(R.id.backButton)
        selectFilesButton = findViewById(R.id.selectFilesButton)
        uploadButton = findViewById(R.id.uploadButton)
        selectedFilesLabel = findViewById(R.id.selectedFilesLabel)
        searchEditText = findViewById(R.id.searchEditText)
        searchButton = findViewById(R.id.searchButton)
        filesRecyclerView = findViewById(R.id.filesRecyclerView)
        statusLabel = findViewById(R.id.statusLabel)
        uploadStatusCard = findViewById(R.id.uploadStatusCard)
        fileCountText = findViewById(R.id.fileCountText)

        adapter = FileListAdapter(this, currentServerUrl, fileList,
            onItemClick = { item ->
                onFileItemClicked(item)
            },
            onDeleteClick = { item ->
                showDeleteConfirmation(item)
            }
        )
        filesRecyclerView.layoutManager = LinearLayoutManager(this)
        filesRecyclerView.adapter = adapter

        refreshButton.setOnClickListener {
            loadCurrentDirectory(currentPath)
        }

        backButton.setOnClickListener {
            onBackPressed()
        }

        selectFilesButton.setOnClickListener {
            selectFiles()
        }

        uploadButton.setOnClickListener {
            uploadFiles()
        }

        searchButton.setOnClickListener {
            searchFiles()
        }

        uploadButton.isEnabled = false
        selectedFilesLabel.text = "未选择文件"
        uploadStatusCard.visibility = View.GONE
    }

    private fun onFileItemClicked(item: FileSystemItem) {
        Log.d("FileListActivity", "项目点击: ${item.name}, 路径: ${item.path}, 是否为目录: ${item.isDirectory}")

        if (item.isDirectory) {
            if (item.name == "..") {
                navigateBack()
            } else {
                pathHistory.add(currentPath)
                loadCurrentDirectory(item.path)
            }
        } else {
            previewFile(item)
        }
    }

    private fun loadCurrentDirectory(path: String = "") {
        currentPath = path
        updatePathLabel()

        coroutineScope.launch {
            statusLabel.text = "正在加载文件列表..."
            refreshButton.isEnabled = false

            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, path)
                }

                fileList.clear()
                fileList.addAll(items)
                adapter.notifyDataSetChanged()

                // 更新文件计数
                fileCountText.text = "${items.size} 个项目"

                statusLabel.text = if (path.isEmpty()) {
                    "根目录 - ${items.size} 个项目"
                } else {
                    "当前路径: $path - ${items.size} 个项目"
                }

                // 重置自动连播状态
                resetAutoPlay()

                Log.d("FileListActivity", "加载目录完成: path=$path, items=${items.size}")
            } catch (e: Exception) {
                statusLabel.text = "加载失败: ${e.message}"
                showToast("加载文件列表失败")
                Log.e("FileListActivity", "加载目录异常: ${e.message}", e)
            } finally {
                refreshButton.isEnabled = true
            }
        }
    }

    private fun updatePathLabel() {
        pathLabel.text = if (currentPath.isEmpty()) {
            "根目录"
        } else {
            // 简化路径显示，只显示最后两级目录
            val pathSegments = currentPath.split("/").filter { it.isNotEmpty() }
            val displayPath = if (pathSegments.size > 2) {
                ".../${pathSegments.takeLast(2).joinToString("/")}"
            } else {
                currentPath
            }
            displayPath
        }
    }

    private fun navigateBack() {
        if (pathHistory.isNotEmpty()) {
            val previousPath = pathHistory.removeAt(pathHistory.size - 1)
            Log.d("FileListActivity", "返回导航: 从 $currentPath 到 $previousPath")
            loadCurrentDirectory(previousPath)
        } else {
            finish()
        }
    }

    private fun selectFiles() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "选择文件"), PICK_FILES_REQUEST)
    }

    private fun uploadFiles() {
        if (selectedFiles.isEmpty()) {
            showToast("请先选择要上传的文件")
            return
        }

        coroutineScope.launch {
            uploadButton.isEnabled = false
            statusLabel.text = "正在上传 ${selectedFiles.size} 个文件..."

            try {
                Log.d("FileListActivity", "开始上传 ${selectedFiles.size} 个文件到路径: $currentPath")

                val result = withContext(Dispatchers.IO) {
                    fileServerService.uploadFiles(currentServerUrl, selectedFiles, currentPath)
                }

                if (result.success) {
                    showToast("上传成功")
                    // 清空已选文件并隐藏上传状态栏
                    selectedFiles.clear()
                    uploadStatusCard.visibility = View.GONE
                    uploadButton.isEnabled = false
                    // 刷新文件列表
                    loadCurrentDirectory(currentPath)
                } else {
                    showToast("上传失败: ${result.message}")
                }
            } catch (e: Exception) {
                showToast("上传异常: ${e.message}")
                Log.e("FileListActivity", "上传异常", e)
            } finally {
                uploadButton.isEnabled = true
                statusLabel.text = "上传完成"
            }
        }
    }

    private fun searchFiles() {
        val query = searchEditText.text.toString().trim()
        if (query.isEmpty()) {
            loadCurrentDirectory(currentPath)
            return
        }

        // 客户端过滤
        val filteredList = fileList.filter {
            it.name.contains(query, true) ||
                    (it.extension.contains(query, true))
        }

        val tempList = mutableListOf<FileSystemItem>()
        tempList.addAll(filteredList)

        fileList.clear()
        fileList.addAll(tempList)
        adapter.notifyDataSetChanged()

        // 更新文件计数
        fileCountText.text = "${filteredList.size} 个搜索结果"

        statusLabel.text = "搜索 '${query}': 找到 ${filteredList.size} 个结果"

        // 重置自动连播状态
        resetAutoPlay()
    }

    private fun previewFile(item: FileSystemItem) {
        try {
            val fileType = getFileType(item)

            // 如果是媒体文件，设置自动连播
            if (fileType == "video" || fileType == "audio") {
                setupAutoPlay(item)
            }

            val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            Log.d("FileListActivity", "预览文件: ${item.name}, 类型: $fileType, URL: $fileUrl")

            val intent = Intent(this, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", fileType)
                putExtra("AUTO_PLAY_ENABLED", autoPlayEnabled)
                putExtra("MEDIA_FILE_LIST", ArrayList(mediaFileList))
                putExtra("CURRENT_INDEX", currentPlayingIndex)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", currentPath)
            }
            startActivityForResult(intent, REQUEST_PREVIEW)
        } catch (e: Exception) {
            Log.e("FileListActivity", "预览文件失败", e)
            showToast("预览失败: ${e.message}")
        }
    }

    // 设置自动连播
    private fun setupAutoPlay(selectedItem: FileSystemItem) {
        // 获取当前目录下的所有媒体文件
        mediaFileList.clear()
        mediaFileList.addAll(fileList.filter { item ->
            !item.isDirectory && (getFileType(item) == "video" || getFileType(item) == "audio")
        })

        if (mediaFileList.isNotEmpty()) {
            // 找到当前点击的文件在列表中的位置
            currentPlayingIndex = mediaFileList.indexOfFirst { it.path == selectedItem.path }
            if (currentPlayingIndex == -1) {
                // 如果没找到，添加到列表开始
                mediaFileList.add(0, selectedItem)
                currentPlayingIndex = 0
            }
            autoPlayEnabled = true

            Log.d("AutoPlay", "自动连播设置: 共 ${mediaFileList.size} 个媒体文件, 当前索引: $currentPlayingIndex")
        } else {
            autoPlayEnabled = false
            currentPlayingIndex = -1
        }
    }

    // 重置自动连播状态
    private fun resetAutoPlay() {
        autoPlayEnabled = false
        currentPlayingIndex = -1
        mediaFileList.clear()
    }

    // 播放下一个媒体文件
    private fun playNextMedia() {
        if (mediaFileList.isEmpty() || currentPlayingIndex == -1) {
            return
        }

        val nextIndex = currentPlayingIndex + 1
        if (nextIndex < mediaFileList.size) {
            val nextItem = mediaFileList[nextIndex]
            currentPlayingIndex = nextIndex
            previewFile(nextItem)

            Log.d("AutoPlay", "自动播放下一个: ${nextItem.name}, 索引: $nextIndex")
        } else {
            // 已经是最后一个文件
            showToast("已经是最后一个文件")
            resetAutoPlay()
        }
    }

    // 播放上一个媒体文件
    private fun playPreviousMedia() {
        if (mediaFileList.isEmpty() || currentPlayingIndex == -1) {
            return
        }

        val prevIndex = currentPlayingIndex - 1
        if (prevIndex >= 0) {
            val prevItem = mediaFileList[prevIndex]
            currentPlayingIndex = prevIndex
            previewFile(prevItem)

            Log.d("AutoPlay", "自动播放上一个: ${prevItem.name}, 索引: $prevIndex")
        } else {
            // 已经是第一个文件
            showToast("已经是第一个文件")
        }
    }

    private fun getFileType(item: FileSystemItem): String {
        return when {
            item.isVideo -> "video"
            item.isAudio -> "audio"
            item.extension in listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp") -> "image"
            item.extension in listOf(".txt", ".log", ".json", ".xml", ".csv", ".md",
                ".html", ".htm", ".css", ".js", ".java", ".kt", ".py") -> "text"
            else -> "general"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FILES_REQUEST && resultCode == Activity.RESULT_OK) {
            selectedFiles.clear()

            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    val uri = data.clipData!!.getItemAt(i).uri
                    uris.add(uri)
                }
            } else if (data?.data != null) {
                uris.add(data.data!!)
            }

            Log.d("FileListActivity", "选择了 ${uris.size} 个文件")

            coroutineScope.launch {
                statusLabel.text = "正在处理选中的文件..."
                val files = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> uriToFile(uri) }
                }

                selectedFiles.addAll(files)
                if (selectedFiles.isNotEmpty()) {
                    selectedFilesLabel.text = "已选择 ${selectedFiles.size} 个文件"
                    uploadStatusCard.visibility = View.VISIBLE
                    uploadButton.isEnabled = true

                    val fileNames = selectedFiles.joinToString(", ") { it.name }
                    showToast("已选择: $fileNames")
                    Log.d("FileListActivity", "成功转换 ${selectedFiles.size} 个文件: $fileNames")
                } else {
                    uploadStatusCard.visibility = View.GONE
                    showToast("没有有效的文件被选择")
                }
                statusLabel.text = "文件选择完成"
            }
        } else if (requestCode == REQUEST_PREVIEW && resultCode == Activity.RESULT_OK) {
            // 处理从PreviewActivity返回的结果
            handlePreviewResult(data)
        }
    }

    // 处理预览结果
    private fun handlePreviewResult(data: Intent?) {
        data?.let { intent ->
            when (intent.getStringExtra("ACTION")) {
                "PLAY_NEXT" -> {
                    // 播放下一个
                    playNextMedia()
                }
                "PLAY_PREVIOUS" -> {
                    // 播放上一个
                    playPreviousMedia()
                }
                "REFRESH_LIST" -> {
                    // 刷新文件列表
                    loadCurrentDirectory(currentPath)
                }
                "EXIT_AUTO_PLAY" -> {
                    // 退出自动连播
                    resetAutoPlay()
                    showToast("已退出自动连播")
                }
            }
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            val contentResolver = applicationContext.contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("FileListActivity", "无法打开输入流: $uri")
                return null
            }

            val fileName = getFileName(uri)
            val tempFile = File.createTempFile("upload_", "_$fileName", cacheDir)
            val outputStream: OutputStream = tempFile.outputStream()

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.d("FileListActivity", "成功转换URI为文件: ${tempFile.absolutePath}, 大小: ${tempFile.length()} bytes")
            tempFile
        } catch (e: Exception) {
            Log.e("FileListActivity", "URI转换失败: ${e.message}", e)
            null
        }
    }

    private fun showDeleteConfirmation(item: FileSystemItem) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定要删除文件 \"${item.displayName}\" 吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteFile(item)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteFile(item: FileSystemItem) {
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fileServerService.deleteFile(currentServerUrl, item.path)
                }

                if (result) {
                    showToast("文件删除成功")
                    loadCurrentDirectory(currentPath) // 刷新列表

                    // 如果删除的是当前播放的媒体文件，更新自动连播状态
                    if (autoPlayEnabled && mediaFileList.any { it.path == item.path }) {
                        mediaFileList.removeAll { it.path == item.path }
                        if (mediaFileList.isEmpty()) {
                            resetAutoPlay()
                        } else if (currentPlayingIndex >= mediaFileList.size) {
                            currentPlayingIndex = mediaFileList.size - 1
                        }
                    }
                } else {
                    showToast("删除失败")
                }
            } catch (e: Exception) {
                showToast("删除异常: ${e.message}")
                Log.e("FileListActivity", "删除文件失败", e)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var result = ""

        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    result = it.getString(displayNameIndex) ?: ""
                }
            }
        }

        if (result.isEmpty()) {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                result = path.substringAfterLast('/')
            }
        }

        if (result.isEmpty()) {
            result = "unknown_file_${System.currentTimeMillis()}"
        }

        if (!result.contains('.')) {
            val mimeType = contentResolver.getType(uri)
            val extension = when {
                mimeType?.startsWith("image/") == true -> ".jpg"
                mimeType?.startsWith("video/") == true -> ".mp4"
                mimeType?.startsWith("audio/") == true -> ".mp3"
                mimeType == "text/plain" -> ".txt"
                mimeType == "application/pdf" -> ".pdf"
                else -> ".dat"
            }
            result += extension
        }

        return result
    }

    override fun onBackPressed() {
        if (pathHistory.isNotEmpty()) {
            navigateBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        selectedFiles.forEach { file ->
            if (file.exists() && file.name.startsWith("upload_")) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.e("FileListActivity", "清理临时文件失败: ${file.name}", e)
                }
            }
        }
    }
}