@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File

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
    private val selectedFiles = mutableListOf<Uri>()
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

        // 初始化 RecyclerView
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
    }

    private fun onFileItemClicked(item: FileSystemItem) {
        if (item.isDirectory) {
            if (item.name == "..") {
                navigateBack()
            } else {
                pathHistory.add(currentPath)
                loadCurrentDirectory(item.path)
            }
        } else {
            // 处理文件点击（下载或预览）
            downloadFile(item)
        }
    }

    private fun loadCurrentDirectory(path: String = "") {
        currentPath = path
        updatePathLabel()

        coroutineScope.launch {
            statusLabel.text = "正在加载文件列表..."

            val items = withContext(Dispatchers.IO) {
                fileServerService.getFileList(currentServerUrl, path)
            }

            fileList.clear()
            fileList.addAll(items)
            adapter.notifyDataSetChanged()

            statusLabel.text = if (path.isEmpty()) {
                "根目录 - 找到 ${items.size} 个项"
            } else {
                "当前路径: $path - 找到 ${items.size} 个项"
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

            val files = selectedFiles.mapNotNull { uri ->
                val file = File(uri.path ?: "")
                if (file.exists()) file else null
            }

            val result = withContext(Dispatchers.IO) {
                fileServerService.uploadFiles(currentServerUrl, files, currentPath)
            }

            if (result.success) {
                showToast(result.message)
                selectedFiles.clear()
                selectedFilesLabel.text = "未选择文件"
                uploadButton.isEnabled = false
                loadCurrentDirectory(currentPath) // 刷新列表
            } else {
                showToast("上传失败: ${result.message}")
            }

            uploadButton.isEnabled = true
        }
    }

    private fun searchFiles() {
        val query = searchEditText.text.toString().trim()
        // 简化实现：客户端过滤
        val filteredList = if (query.isEmpty()) {
            fileList
        } else {
            fileList.filter { it.name.contains(query, true) }
        }

        fileList.clear()
        fileList.addAll(filteredList)
        adapter.notifyDataSetChanged()

        statusLabel.text = if (query.isEmpty()) {
            "显示所有文件 - ${filteredList.size} 个项"
        } else {
            "搜索完成: 找到 ${filteredList.size} 个匹配项"
        }
    }

    private fun downloadFile(item: FileSystemItem) {
        val downloadUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/download/${item.path}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        startActivity(intent)
        showToast("正在下载: ${item.name}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FILES_REQUEST && resultCode == Activity.RESULT_OK) {
            selectedFiles.clear()

            if (data?.clipData != null) {
                // 多选
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    val uri = data.clipData!!.getItemAt(i).uri
                    selectedFiles.add(uri)
                }
            } else if (data?.data != null) {
                // 单选
                selectedFiles.add(data.data!!)
            }

            selectedFilesLabel.text = "已选择 ${selectedFiles.size} 个文件"
            uploadButton.isEnabled = selectedFiles.isNotEmpty()
        }
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
    }

    // 内嵌的适配器类 - 完整修改版本
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

            // 设置文件图标
            holder.fileIcon.text = getFileIcon(item)

            // 设置文件名 - 使用 displayName 确保不为空
            holder.fileName.text = item.displayName

            // 设置文件信息
            holder.fileInfo.text = if (item.isDirectory) {
                "目录 • ${formatDate(item.lastModified)}"
            } else {
                "${item.sizeFormatted} • ${formatDate(item.lastModified)}"
            }

            // 设置文件大小
            if (item.isDirectory) {
                holder.fileSize.visibility = View.GONE
            } else {
                holder.fileSize.text = item.sizeFormatted
                holder.fileSize.visibility = View.VISIBLE
            }

            // 预览按钮可见性
            val isPreviewable = isPreviewableFile(item)
            holder.previewButton.visibility = if (isPreviewable) View.VISIBLE else View.GONE

            // 下载按钮可见性 - 只有文件才显示下载按钮
            holder.downloadButton.visibility = if (item.isDirectory) View.GONE else View.VISIBLE

            // 点击事件
            holder.itemView.setOnClickListener {
                onItemClick(item)
            }

            holder.downloadButton.setOnClickListener {
                onItemClick(item)
            }

            holder.previewButton.setOnClickListener {
                showPreview(item, holder.itemView.context)
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
            val fileType = getFileType(item)
            val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${item.path}"

            val intent = Intent(context, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", item.displayName)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", fileType)
            }
            context.startActivity(intent)
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

        private fun formatDate(dateString: String): String {
            // 简化日期格式化，实际应用中需要解析日期字符串
            return if (dateString.length > 10) {
                dateString.substring(0, 10) // 只显示日期部分
            } else {
                dateString
            }
        }
    }
}