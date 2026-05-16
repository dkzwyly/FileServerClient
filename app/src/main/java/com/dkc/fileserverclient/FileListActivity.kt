@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import android.os.Handler
import android.os.Looper

class FileListActivity : AppCompatActivity() {

    private val thumbnailPollingHandler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var pollingAttempts = 0
    private val MAX_POLLING_ATTEMPTS = 5
    private val POLLING_INTERVAL = 1000L

    private lateinit var toolbar: MaterialToolbar
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
    private val selectedFiles = mutableListOf<Pair<File, String>>()
    private val pathHistory = mutableListOf<String>()
    private var currentServerUrl = ""
    private var currentPath = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: FileListAdapter

    // Activity Result Launchers
    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            handleFileSelection(it.data)
        }
    }

    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            handlePreviewResult(result.data)
        }
    }

    // 自动连播相关变量
    private var autoPlayEnabled = false
    private var currentPlayingIndex = -1
    private var mediaFileList = mutableListOf<FileSystemItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }

        initViews()
        setupToolbar()
        setupBackPressedHandler()
        loadCurrentDirectory("")
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
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

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "文件列表"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_file_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleBackNavigation()
                true
            }
            R.id.menu_refresh -> {
                loadCurrentDirectory(currentPath)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBackPressedHandler() {
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun handleBackNavigation() {
        if (pathHistory.isNotEmpty()) {
            navigateBack()
        } else {
            finish()
        }
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
        // 在Toolbar上显示当前路径（可选，通过副标题）
        supportActionBar?.subtitle = if (path.isEmpty()) "根目录" else path

        if (isPolling) {
            statusLabel.text = "正在刷新列表并检查缩略图... (${pollingAttempts}/${MAX_POLLING_ATTEMPTS})"
        }

        coroutineScope.launch {
            statusLabel.text = "正在加载文件列表..."
            // 禁用刷新菜单项
            invalidateOptionsMenu()

            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, path)
                }

                fileList.clear()
                fileList.addAll(items)
                adapter.notifyDataSetChanged()

                fileCountText.text = "${items.size} 个项目"

                if (!isPolling) {
                    statusLabel.text = if (path.isEmpty()) {
                        "根目录 - ${items.size} 个项目"
                    } else {
                        "当前路径: $path - ${items.size} 个项目"
                    }
                }

                resetAutoPlay()

                Log.d("FileListActivity", "加载目录完成: path=$path, items=${items.size}")
            } catch (e: Exception) {
                statusLabel.text = "加载失败: ${e.message}"
                showToast("加载文件列表失败")
                Log.e("FileListActivity", "加载目录异常: ${e.message}", e)
            } finally {
                // 恢复刷新菜单项
                invalidateOptionsMenu()
            }
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
        pickFilesLauncher.launch(Intent.createChooser(intent, "选择文件"))
    }

    private fun uploadFiles() {
        if (selectedFiles.isEmpty()) {
            showToast("请先选择要上传的文件")
            return
        }

        val uploadedFileNames = selectedFiles.map { it.second }

        coroutineScope.launch {
            uploadButton.isEnabled = false
            statusLabel.text = "正在上传 ${selectedFiles.size} 个文件..."

            try {
                Log.d("FileListActivity", "开始上传 ${selectedFiles.size} 个文件到路径: $currentPath")

                val result = withContext(Dispatchers.IO) {
                    fileServerService.uploadFiles(currentServerUrl, selectedFiles, currentPath)
                }

                if (result.success) {
                    showToast("上传成功，正在刷新缩略图...")

                    selectedFiles.clear()
                    uploadStatusCard.visibility = View.GONE
                    uploadButton.isEnabled = false

                    loadCurrentDirectory(currentPath)
                    startThumbnailPolling(uploadedFileNames)

                } else {
                    showToast("上传失败: ${result.message}")
                    uploadButton.isEnabled = true
                }
            } catch (e: Exception) {
                showToast("上传异常: ${e.message}")
                Log.e("FileListActivity", "上传异常", e)
                uploadButton.isEnabled = true
            } finally {
                statusLabel.text = "上传完成"
            }
        }
    }

    private fun startThumbnailPolling(uploadedFileNames: List<String>) {
        pollingAttempts = 0
        isPolling = true

        val pollingRunnable = object : Runnable {
            override fun run() {
                if (!isPolling || pollingAttempts >= MAX_POLLING_ATTEMPTS) {
                    isPolling = false
                    showToast("缩略图刷新完成")
                    return
                }

                pollingAttempts++
                loadCurrentDirectory(currentPath)

                statusLabel.text = "正在检查缩略图... (${pollingAttempts}/${MAX_POLLING_ATTEMPTS})"

                if (pollingAttempts < MAX_POLLING_ATTEMPTS) {
                    thumbnailPollingHandler.postDelayed(this, POLLING_INTERVAL)
                } else {
                    statusLabel.text = "缩略图刷新完成"
                    isPolling = false
                }
            }
        }

        thumbnailPollingHandler.postDelayed(pollingRunnable, 500)
    }

    private fun searchFiles() {
        val query = searchEditText.text.toString().trim()
        if (query.isEmpty()) {
            loadCurrentDirectory(currentPath)
            return
        }

        val filteredList = fileList.filter {
            it.name.contains(query, true) ||
                    (it.extension.contains(query, true))
        }

        val tempList = mutableListOf<FileSystemItem>()
        tempList.addAll(filteredList)

        fileList.clear()
        fileList.addAll(tempList)
        adapter.notifyDataSetChanged()

        fileCountText.text = "${filteredList.size} 个搜索结果"
        statusLabel.text = "搜索 '${query}': 找到 ${filteredList.size} 个结果"
        resetAutoPlay()
    }

    private fun previewFile(item: FileSystemItem) {
        try {
            val fileType = getFileType(item)

            // ========== 音频直接跳转 AudioPlayerActivity ==========
            if (fileType == "audio") {
                // 构建当前目录下所有音频文件列表（用于自动连播）
                val allAudioItems = if (autoPlayEnabled && mediaFileList.isNotEmpty()) {
                    mediaFileList.filter { !it.isDirectory && getFileType(it) == "audio" }
                } else {
                    fileList.filter { !it.isDirectory && getFileType(it) == "audio" }
                }

                val audioTracks = allAudioItems.map { AudioTrack.fromFileSystemItem(it, currentServerUrl) }
                val currentTrack = AudioTrack.fromFileSystemItem(item, currentServerUrl)
                val currentIndex = audioTracks.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)

                val intent = Intent(this, AudioPlayerActivity::class.java).apply {
                    putExtra("AUDIO_TRACK", currentTrack)
                    putExtra("AUDIO_TRACKS", ArrayList(audioTracks))
                    putExtra("CURRENT_INDEX", currentIndex)
                    putExtra("SERVER_URL", currentServerUrl)
                    putExtra("FILE_PATH", item.path)
                    putExtra("FILE_NAME", item.name)
                    putExtra("FILE_TYPE", "audio")
                    // 可传递播放模式，默认为列表循环
                    putExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, PlaylistDetailActivity.MODE_LIST)
                }
                startActivity(intent)
                return
            }

            // ========== 视频/图片/其他类型继续使用 PreviewActivity ==========
            if (fileType == "video") {
                setupAutoPlay(item)
            } else if (fileType == "image") {
                resetAutoPlay()
            }

            val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            val intent = Intent(this, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", fileType)
                putExtra("FILE_PATH", item.path)
                putExtra("AUTO_PLAY_ENABLED", autoPlayEnabled)
                putExtra("MEDIA_FILE_LIST", ArrayList(mediaFileList))
                putExtra("CURRENT_INDEX", currentPlayingIndex)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", currentPath)
            }
            previewLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("FileListActivity", "预览文件失败", e)
            showToast("预览失败: ${e.message}")
        }
    }

    private fun setupAutoPlay(selectedItem: FileSystemItem) {
        mediaFileList.clear()
        mediaFileList.addAll(fileList.filter { item ->
            !item.isDirectory && (getFileType(item) == "video" || getFileType(item) == "audio")
        })

        if (mediaFileList.isNotEmpty()) {
            currentPlayingIndex = mediaFileList.indexOfFirst { it.path == selectedItem.path }
            if (currentPlayingIndex == -1) {
                mediaFileList.add(0, selectedItem)
                currentPlayingIndex = 0
            }
            autoPlayEnabled = true
        } else {
            autoPlayEnabled = false
            currentPlayingIndex = -1
        }
    }

    private fun resetAutoPlay() {
        autoPlayEnabled = false
        currentPlayingIndex = -1
        mediaFileList.clear()
    }

    private fun playNextMedia() {
        if (mediaFileList.isEmpty() || currentPlayingIndex == -1) return

        val nextIndex = currentPlayingIndex + 1
        if (nextIndex < mediaFileList.size) {
            val nextItem = mediaFileList[nextIndex]
            currentPlayingIndex = nextIndex
            previewFile(nextItem)
        } else {
            showToast("已经是最后一个文件")
            resetAutoPlay()
        }
    }

    private fun playPreviousMedia() {
        if (mediaFileList.isEmpty() || currentPlayingIndex == -1) return

        val prevIndex = currentPlayingIndex - 1
        if (prevIndex >= 0) {
            val prevItem = mediaFileList[prevIndex]
            currentPlayingIndex = prevIndex
            previewFile(prevItem)
        } else {
            showToast("已经是第一个文件")
        }
    }

    private fun getFileType(item: FileSystemItem): String {
        return when {
            item.isVideo -> "video"
            item.isAudio -> "audio"
            item.extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> "image"
            item.extension in listOf("txt", "log", "json", "xml", "csv", "md",
                "html", "htm", "css", "js", "java", "kt", "py") -> "text"
            else -> "general"
        }
    }

    private fun handleFileSelection(data: Intent?) {
        data?.let { intent ->
            selectedFiles.clear()

            val uris = mutableListOf<Uri>()
            if (intent.clipData != null) {
                val count = intent.clipData!!.itemCount
                for (i in 0 until count) {
                    val uri = intent.clipData!!.getItemAt(i).uri
                    uris.add(uri)
                }
            } else if (intent.data != null) {
                uris.add(intent.data!!)
            }

            coroutineScope.launch {
                statusLabel.text = "正在处理选中的文件..."
                val filesWithNames = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val originalName = getFileName(uri)
                        val tempFile = uriToFile(uri)
                        tempFile?.let { Pair(it, originalName) }
                    }
                }

                selectedFiles.addAll(filesWithNames)
                if (selectedFiles.isNotEmpty()) {
                    selectedFilesLabel.text = "已选择 ${selectedFiles.size} 个文件"
                    uploadStatusCard.visibility = View.VISIBLE
                    uploadButton.isEnabled = true
                    val originalNames = selectedFiles.joinToString(", ") { it.second }
                    showToast("已选择: $originalNames")
                } else {
                    uploadStatusCard.visibility = View.GONE
                    showToast("没有有效的文件被选择")
                }
                statusLabel.text = "文件选择完成"
            }
        }
    }

    private fun handlePreviewResult(data: Intent?) {
        data?.let { intent ->
            when (intent.getStringExtra("ACTION")) {
                "PLAY_NEXT" -> playNextMedia()
                "PLAY_PREVIOUS" -> playPreviousMedia()
                "REFRESH_LIST" -> loadCurrentDirectory(currentPath)
                "EXIT_AUTO_PLAY" -> {
                    resetAutoPlay()
                    showToast("已退出自动连播")
                }
            }
        }
    }

    private fun uriToFile(uri: Uri): File? {
        return try {
            val contentResolver = applicationContext.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val fileName = getFileName(uri)
            val tempFile = File.createTempFile("upload_", "_$fileName", cacheDir)
            val outputStream = tempFile.outputStream()
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
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
                    loadCurrentDirectory(currentPath)
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
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    result = cursor.getString(displayNameIndex) ?: ""
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        thumbnailPollingHandler.removeCallbacksAndMessages(null)
        coroutineScope.cancel()

        selectedFiles.forEach { (file, _) ->
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