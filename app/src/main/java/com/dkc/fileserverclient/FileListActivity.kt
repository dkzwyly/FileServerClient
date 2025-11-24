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
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import coil.clear


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
    }

    private fun previewFile(item: FileSystemItem) {
        try {
            val fileType = getFileType(item)
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

    // 适配器类
    class FileListAdapter(
        private val context: Context,
        private val serverUrl: String,
        private val items: List<FileSystemItem>,
        private val onItemClick: (FileSystemItem) -> Unit,
        private val onDeleteClick: (FileSystemItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // 使用统一的ImageLoader实例，避免重复创建
        private val unsafeImageLoader by lazy {
            ImageLoader.Builder(context)
                .okHttpClient(createUnsafeOkHttpClient())
                .build()
        }

        // 视图类型常量
        companion object {
            private const val TYPE_DIRECTORY = 0
            private const val TYPE_FILE = 1
        }

        abstract class BaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            abstract fun bind(item: FileSystemItem, position: Int)
        }

        class DirectoryViewHolder(view: View) : BaseViewHolder(view) {
            val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
            val fileName: TextView = view.findViewById(R.id.fileName)
            val fileInfo: TextView = view.findViewById(R.id.fileInfo)
            val fileSize: TextView = view.findViewById(R.id.fileSize)
            val previewButton: ImageButton = view.findViewById(R.id.previewButton)
            val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

            override fun bind(item: FileSystemItem, position: Int) {
                // 文件夹处理 - 强制使用文件夹图标，不加载任何缩略图
                fileName.text = item.displayName
                fileInfo.text = "目录"
                fileSize.visibility = View.GONE

                // 关键修复：立即设置文件夹图标，不进行任何异步操作
                fileIcon.setImageResource(R.drawable.ic_folder)

                // 文件夹不应该有任何操作按钮
                previewButton.visibility = View.GONE
                downloadButton.visibility = View.GONE
                deleteButton.visibility = View.GONE

                // 文件夹点击事件 - 直接导航
                itemView.setOnClickListener {
                    Log.d("AdapterDebug", "文件夹点击: ${item.name}")
                    // 注意：这里需要外部处理点击事件
                }

                // 确保按钮没有点击事件
                previewButton.setOnClickListener(null)
                downloadButton.setOnClickListener(null)
                deleteButton.setOnClickListener(null)
            }
        }

        class FileViewHolder(
            view: View,
            private val serverUrl: String,
            private val imageLoader: ImageLoader,
            private val onItemClick: (FileSystemItem) -> Unit,
            private val onDeleteClick: (FileSystemItem) -> Unit
        ) : BaseViewHolder(view) {
            val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
            val fileName: TextView = view.findViewById(R.id.fileName)
            val fileInfo: TextView = view.findViewById(R.id.fileInfo)
            val fileSize: TextView = view.findViewById(R.id.fileSize)
            val previewButton: ImageButton = view.findViewById(R.id.previewButton)
            val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

            // 添加标识来跟踪当前加载的路径
            var currentLoadPath: String? = null

            override fun bind(item: FileSystemItem, position: Int) {
                // 重置当前状态
                currentLoadPath = null

                // 文件处理
                fileName.text = item.displayName
                fileInfo.text = "${item.sizeFormatted} • ${formatDate(item.lastModified)}"
                fileSize.text = item.sizeFormatted
                fileSize.visibility = View.VISIBLE

                // 设置按钮可见性
                val isPreviewable = isPreviewableFile(item)
                previewButton.visibility = if (isPreviewable) View.VISIBLE else View.GONE
                downloadButton.visibility = View.VISIBLE
                deleteButton.visibility = View.VISIBLE

                // 设置当前状态
                currentLoadPath = item.path

                // 处理图片文件缩略图
                if (item.isImage) {
                    // 只有图片文件才加载缩略图
                    loadImageThumbnail(item, position)
                } else {
                    // 非图片文件使用文件类型图标 - 立即设置，不异步
                    fileIcon.setImageResource(getFileIconRes(item))
                }

                // 设置按钮图标
                previewButton.setImageResource(R.drawable.ic_preview)
                downloadButton.setImageResource(R.drawable.ic_download)
                deleteButton.setImageResource(R.drawable.ic_delete)

                // 文件点击事件 - 图片直接预览，其他文件导航
                itemView.setOnClickListener {
                    if (item.isImage) {
                        showPreview(item, itemView.context)
                    } else {
                        onItemClick(item)
                    }
                }

                // 预览按钮点击事件
                previewButton.setOnClickListener {
                    showPreview(item, itemView.context)
                }

                // 下载按钮点击事件
                downloadButton.setOnClickListener {
                    downloadFile(item, itemView.context)
                }

                // 删除按钮点击事件
                deleteButton.setOnClickListener {
                    onDeleteClick(item)
                }
            }

            private fun getFileIconRes(item: FileSystemItem): Int {
                return when {
                    item.isVideo -> R.drawable.ic_video
                    item.isAudio -> R.drawable.ic_audio
                    item.extension in listOf(".pdf") -> R.drawable.ic_pdf
                    item.extension in listOf(".doc", ".docx") -> R.drawable.ic_document
                    item.extension in listOf(".xls", ".xlsx") -> R.drawable.ic_spreadsheet
                    item.extension in listOf(".zip", ".rar", ".7z", ".tar", ".gz") -> R.drawable.ic_archive
                    else -> R.drawable.ic_file
                }
            }

            private fun isPreviewableFile(item: FileSystemItem): Boolean {
                return when {
                    item.isVideo || item.isAudio -> true
                    item.extension in listOf(".txt", ".log", ".json", ".xml", ".csv", ".md",
                        ".html", ".htm", ".css", ".js", ".java", ".kt", ".py") -> true
                    else -> false
                }
            }

            private fun showPreview(item: FileSystemItem, context: Context) {
                try {
                    val fileType = getFileType(item)
                    val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${java.net.URLEncoder.encode(item.path, "UTF-8")}"

                    Log.d("Preview", "预览文件: ${item.name}, 类型: $fileType")

                    val intent = Intent(context, PreviewActivity::class.java).apply {
                        putExtra("FILE_NAME", item.name)
                        putExtra("FILE_URL", fileUrl)
                        putExtra("FILE_TYPE", fileType)
                        putExtra("FILE_PATH", item.path)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("Preview", "预览文件失败: ${e.message}", e)
                    Toast.makeText(context, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            private fun getFileType(item: FileSystemItem): String {
                return when {
                    item.isVideo -> "video"
                    item.isAudio -> "audio"
                    item.isImage -> "image"
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
                } catch (e: Exception) {
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            private fun formatDate(dateString: String): String {
                return if (dateString.length > 10) {
                    dateString.substring(0, 10)
                } else {
                    dateString
                }
            }

            private fun loadImageThumbnail(item: FileSystemItem, position: Int) {
                try {
                    // 构建缩略图URL
                    val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                    val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"

                    Log.d("ThumbnailDebug", "开始加载缩略图: ${item.name}, URL: $thumbnailUrl")

                    // 使用 Coil 加载缩略图
                    val request = ImageRequest.Builder(itemView.context)
                        .data(thumbnailUrl)
                        .target { drawable ->
                            // 检查当前加载路径是否匹配
                            if (currentLoadPath == item.path) {
                                fileIcon.setImageDrawable(drawable)
                                Log.d("ThumbnailDebug", "缩略图加载成功: ${item.name}")
                            } else {
                                Log.d("ThumbnailDebug", "缩略图加载完成但路径不匹配: ${item.name}")
                            }
                        }
                        .placeholder(R.drawable.ic_image)
                        .error(R.drawable.ic_image)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()

                    // 执行请求
                    imageLoader.enqueue(request)

                } catch (e: Exception) {
                    Log.e("ThumbnailDebug", "缩略图加载异常: ${e.message}", e)
                    // 只在当前加载路径匹配时设置错误图标
                    if (currentLoadPath == item.path) {
                        fileIcon.setImageResource(R.drawable.ic_image)
                    }
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position].isDirectory) TYPE_DIRECTORY else TYPE_FILE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_list, parent, false)

            return when (viewType) {
                TYPE_DIRECTORY -> DirectoryViewHolder(view)
                TYPE_FILE -> FileViewHolder(view, serverUrl, unsafeImageLoader, onItemClick, onDeleteClick)
                else -> throw IllegalArgumentException("未知的视图类型: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]

            when (holder) {
                is DirectoryViewHolder -> {
                    holder.bind(item, position)
                    // 设置文件夹点击事件
                    holder.itemView.setOnClickListener { onItemClick(item) }
                }
                is FileViewHolder -> holder.bind(item, position)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            when (holder) {
                is FileViewHolder -> {
                    // 重置文件视图的状态
                    holder.fileIcon.setImageDrawable(null)
                    holder.currentLoadPath = null
                }
                is DirectoryViewHolder -> {
                    // 重置文件夹视图的状态
                    holder.fileIcon.setImageDrawable(null)
                }
            }
        }

        private fun createUnsafeOkHttpClient(): OkHttpClient {
            try {
                // 创建信任所有证书的 TrustManager
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        // 信任所有客户端证书
                    }

                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        // 信任所有服务器证书
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> {
                        return arrayOf()
                    }
                })

                // 创建 SSLContext 使用信任所有证书的 TrustManager
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())

                // 创建不验证主机名的 HostnameVerifier
                val hostnameVerifier = HostnameVerifier { _, _ -> true }

                return OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                    .hostnameVerifier(hostnameVerifier)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}