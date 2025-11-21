@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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

    private val fileServerService by lazy { FileServerService(this) }
    private val fileList = mutableListOf<FileSystemItem>()
    private val selectedFiles = mutableListOf<File>()
    private val pathHistory = mutableListOf<String>()
    private var currentServerUrl = ""
    private var currentPath = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: FileListAdapter

    companion object {
        private const val PICK_FILES_REQUEST = 1001
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

        adapter = FileListAdapter(this, currentServerUrl, fileList) { item ->
            onFileItemClicked(item)
        }
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
    }

    private fun onFileItemClicked(item: FileSystemItem) {
        Log.d("FileListActivity", "项目点击: ${item.name}, 路径: ${item.path}, 是否为目录: ${item.isDirectory}")

        if (item.isDirectory) {
            if (item.name == "..") {
                navigateBack()
            } else {
                pathHistory.add(currentPath)
                // 使用后端返回的完整路径
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

                statusLabel.text = if (path.isEmpty()) {
                    "根目录 - ${items.size} 个项目"
                } else {
                    "当前路径: $path - ${items.size} 个项目"
                }

                // 调试日志
                Log.d("FileListActivity", "加载目录完成: path=$path, items=${items.size}")
                items.forEachIndexed { index, item ->
                    Log.d("FileListActivity", "项目 $index: ${item.name} (路径: ${item.path}, 类型: ${if (item.isDirectory) "目录" else "文件"})")
                }
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
            "当前路径: $currentPath"
        }
    }

    private fun navigateBack() {
        if (pathHistory.isNotEmpty()) {
            val previousPath = pathHistory.removeAt(pathHistory.size - 1)
            Log.d("FileListActivity", "返回导航: 从 $currentPath 到 $previousPath")
            loadCurrentDirectory(previousPath)
        } else {
            Log.d("FileListActivity", "返回导航: 历史为空，结束Activity")
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
                    showToast("上传成功: ${result.message}")
                    // 清空已选文件
                    selectedFiles.clear()
                    selectedFilesLabel.text = "未选择文件"
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

        statusLabel.text = "搜索 '${query}': 找到 ${filteredList.size} 个结果"
    }

    private fun previewFile(item: FileSystemItem) {
        try {
            val fileType = getFileType(item)
            // 构建预览URL - 确保路径正确编码
            val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            Log.d("FileListActivity", "预览文件: ${item.name}, 类型: $fileType, URL: $fileUrl")

            val intent = Intent(this, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", fileType)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("FileListActivity", "预览文件失败", e)
            showToast("预览失败: ${e.message}")
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
                // 多选
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    val uri = data.clipData!!.getItemAt(i).uri
                    uris.add(uri)
                }
            } else if (data?.data != null) {
                // 单选
                uris.add(data.data!!)
            }

            Log.d("FileListActivity", "选择了 ${uris.size} 个文件")

            coroutineScope.launch {
                statusLabel.text = "正在处理选中的文件..."
                val files = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> uriToFile(uri) }
                }

                selectedFiles.addAll(files)
                selectedFilesLabel.text = "已选择 ${selectedFiles.size} 个文件"
                uploadButton.isEnabled = selectedFiles.isNotEmpty()
                statusLabel.text = "文件选择完成"

                if (selectedFiles.isNotEmpty()) {
                    val fileNames = selectedFiles.joinToString(", ") { it.name }
                    showToast("已选择: $fileNames")
                    Log.d("FileListActivity", "成功转换 ${selectedFiles.size} 个文件: $fileNames")
                } else {
                    showToast("没有有效的文件被选择")
                }
            }
        } else if (requestCode == PICK_FILES_REQUEST) {
            Log.d("FileListActivity", "文件选择取消或失败")
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            Log.d("FileListActivity", "转换URI: $uri")

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

            Log.d("FileListActivity", "成功转换URI为文件: ${tempFile.absolutePath}, 大小: ${tempFile.length()} bytes, 文件名: $fileName")
            tempFile
        } catch (e: Exception) {
            Log.e("FileListActivity", "URI转换失败: ${e.message}", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String {
        var result = ""

        // 首先尝试从游标获取文件名
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    result = it.getString(displayNameIndex) ?: ""
                }
            }
        }

        // 如果从游标没有获取到，尝试从URI路径获取
        if (result.isEmpty()) {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                result = path.substringAfterLast('/')
            }
        }

        // 如果仍然为空，使用默认文件名
        if (result.isEmpty()) {
            result = "unknown_file_${System.currentTimeMillis()}"
        }

        // 确保文件名有扩展名
        if (!result.contains('.')) {
            // 尝试从MIME类型推断扩展名
            val mimeType = contentResolver.getType(uri)
            val extension = when {
                mimeType?.startsWith("image/") == true -> {
                    when (mimeType) {
                        "image/jpeg" -> ".jpg"
                        "image/png" -> ".png"
                        "image/gif" -> ".gif"
                        "image/webp" -> ".webp"
                        else -> ".jpg"
                    }
                }
                mimeType?.startsWith("video/") == true -> {
                    when (mimeType) {
                        "video/mp4" -> ".mp4"
                        "video/avi" -> ".avi"
                        "video/mkv" -> ".mkv"
                        else -> ".mp4"
                    }
                }
                mimeType?.startsWith("audio/") == true -> {
                    when (mimeType) {
                        "audio/mpeg" -> ".mp3"
                        "audio/wav" -> ".wav"
                        else -> ".mp3"
                    }
                }
                mimeType == "text/plain" -> ".txt"
                mimeType == "application/pdf" -> ".pdf"
                else -> ".dat"
            }
            result += extension
        }

        Log.d("FileListActivity", "获取到文件名: $result (MIME: ${contentResolver.getType(uri)})")
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
        // 清理临时文件
        selectedFiles.forEach { file ->
            if (file.exists() && file.name.startsWith("upload_")) {
                try {
                    file.delete()
                    Log.d("FileListActivity", "清理临时文件: ${file.name}")
                } catch (e: Exception) {
                    Log.e("FileListActivity", "清理临时文件失败: ${file.name}", e)
                }
            }
        }
    }

    // 内嵌的适配器类
    class FileListAdapter(
        private val context: Context,
        private val serverUrl: String,
        private val items: List<FileSystemItem>,
        private val onItemClick: (FileSystemItem) -> Unit
    ) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileIcon: TextView = view.findViewById(R.id.fileIcon)
            val fileName: TextView = view.findViewById(R.id.fileName)
            val fileInfo: TextView = view.findViewById(R.id.fileInfo)
            val fileSize: TextView = view.findViewById(R.id.fileSize)
            val previewButton: Button = view.findViewById(R.id.previewButton)
            val downloadButton: Button = view.findViewById(R.id.downloadButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.fileIcon.text = getFileIcon(item)
            holder.fileName.text = item.name

            if (item.isDirectory) {
                holder.fileInfo.text = "目录"
                holder.fileSize.visibility = View.GONE
            } else {
                holder.fileInfo.text = "${item.sizeFormatted} • ${formatDate(item.lastModified)}"
                holder.fileSize.text = item.sizeFormatted
                holder.fileSize.visibility = View.VISIBLE
            }

            val isPreviewable = isPreviewableFile(item)
            holder.previewButton.visibility = if (isPreviewable) View.VISIBLE else View.GONE

            holder.downloadButton.visibility = if (item.isDirectory) View.GONE else View.VISIBLE

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }

            holder.previewButton.setOnClickListener {
                showPreview(item, holder.itemView.context)
            }

            holder.downloadButton.setOnClickListener {
                downloadFile(item, holder.itemView.context)
            }
        }

        override fun getItemCount() = items.size

        private fun getFileIcon(item: FileSystemItem): String {
            return when {
                item.isDirectory -> if (item.name == "..") "⬆️" else "📁"
                item.isVideo -> "🎬"
                item.isAudio -> "🎵"
                item.extension in listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp") -> "🖼️"
                item.extension in listOf(".pdf") -> "📕"
                item.extension in listOf(".doc", ".docx") -> "📝"
                item.extension in listOf(".xls", ".xlsx") -> "📊"
                item.extension in listOf(".zip", ".rar", ".7z", ".tar", ".gz") -> "📦"
                item.extension in listOf(".exe", ".bat", ".cmd", ".msi") -> "⚙️"
                item.extension in listOf(".mp4", ".avi", ".mov", ".mkv", ".wmv", ".flv", ".webm") -> "🎬"
                item.extension in listOf(".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".wma") -> "🎵"
                else -> "📄"
            }
        }

        private fun isPreviewableFile(item: FileSystemItem): Boolean {
            if (item.isDirectory) return false

            return when {
                item.isVideo || item.isAudio -> true
                item.extension in listOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp") -> true
                item.extension in listOf(".txt", ".log", ".json", ".xml", ".csv", ".md",
                    ".html", ".htm", ".css", ".js", ".java", ".kt", ".py") -> true
                else -> false
            }
        }

        private fun showPreview(item: FileSystemItem, context: Context) {
            try {
                val fileType = getFileType(item)
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

                val intent = Intent(context, PreviewActivity::class.java).apply {
                    putExtra("FILE_NAME", item.name)
                    putExtra("FILE_URL", fileUrl)
                    putExtra("FILE_TYPE", fileType)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("FileListAdapter", "预览文件失败", e)
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

        private fun downloadFile(item: FileSystemItem, context: Context) {
            try {
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val downloadUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/download/$encodedPath"

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                context.startActivity(intent)

                Toast.makeText(context, "开始下载: ${item.name}", Toast.LENGTH_SHORT).show()
                Log.d("FileListAdapter", "下载文件: ${item.name}, URL: $downloadUrl")
            } catch (e: Exception) {
                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("FileListAdapter", "下载文件失败", e)
            }
        }

        private fun formatDate(dateString: String): String {
            return if (dateString.length > 10) {
                dateString.substring(0, 10)
            } else {
                dateString
            }
        }
    }
}