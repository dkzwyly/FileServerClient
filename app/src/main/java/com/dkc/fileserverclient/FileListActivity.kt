@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.app.Activity
import android.content.Intent
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import java.io.File
import android.os.Handler
import android.os.Looper

class FileListActivity : AppCompatActivity() {

    // ===== 缩略图轮询 =====
    private val thumbnailPollingHandler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var pollingAttempts = 0
    private val MAX_POLLING_ATTEMPTS = 5
    private val POLLING_INTERVAL = 1000L

    // ===== 视图控件 =====
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
    private lateinit var createFolderButton: FloatingActionButton
    private lateinit var selectModeButton: Button  // 新增

    // ===== 新增：选择模式相关 =====
    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<FileSystemItem>()
    private lateinit var selectionActionBar: LinearLayout
    private lateinit var selectedCountText: TextView
    private lateinit var actionRename: ImageButton
    private lateinit var actionMove: ImageButton
    private lateinit var actionCopy: ImageButton
    private lateinit var actionDelete: ImageButton
    private lateinit var actionCancelSelection: ImageButton

    // ===== 其他成员 =====
    private val fileServerService by lazy { FileServerService(this) }
    private val fileList = mutableListOf<FileSystemItem>()
    private val selectedFiles = mutableListOf<Pair<File, String>>()
    private val pathHistory = mutableListOf<String>()
    private var currentServerUrl = ""
    private var currentPath = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: FileListAdapter

    // 自动连播相关
    private var autoPlayEnabled = false
    private var currentPlayingIndex = -1
    private var mediaFileList = mutableListOf<FileSystemItem>()

    // ===== Activity 生命周期 =====
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
        createFolderButton = findViewById(R.id.createFolderButton)
        selectModeButton = findViewById(R.id.selectModeButton)  // 新增

        // ===== 新增：操作栏 =====
        selectionActionBar = findViewById(R.id.selectionActionBar)
        selectedCountText = findViewById(R.id.selectedCountText)
        actionRename = findViewById(R.id.actionRename)
        actionMove = findViewById(R.id.actionMove)
        actionCopy = findViewById(R.id.actionCopy)
        actionDelete = findViewById(R.id.actionDelete)
        actionCancelSelection = findViewById(R.id.actionCancelSelection)

        // ===== Adapter =====
        adapter = FileListAdapter(
            context = this,
            serverUrl = currentServerUrl,
            onItemClick = { item ->
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onFileItemClicked(item)
                }
            },
            onDeleteClick = { item -> showDeleteConfirmation(item) },
            onDirectoryLongPress = { item ->   // 目录长按
                if (!isSelectionMode) enterSelectionMode(item)
            },
            onItemLongPress = { item ->        // 文件长按
                if (!isSelectionMode) enterSelectionMode(item)
            },
            onItemToggle = { item -> toggleSelection(item) }
        )
        filesRecyclerView.layoutManager = LinearLayoutManager(this)
        filesRecyclerView.itemAnimator = null
        filesRecyclerView.adapter = adapter

        // ===== 按钮事件 =====
        selectFilesButton.setOnClickListener { selectFiles() }
        uploadButton.setOnClickListener { uploadFiles() }
        searchButton.setOnClickListener { searchFiles() }
        createFolderButton.setOnClickListener { showCreateFolderDialog() }
        selectModeButton.setOnClickListener {
            if (isSelectionMode) exitSelectionMode() else enterSelectionMode()
        }

        // ===== 操作栏按钮 =====
        actionRename.setOnClickListener { renameSelected() }
        actionMove.setOnClickListener { moveSelected() }
        actionCopy.setOnClickListener { copySelected() }
        actionDelete.setOnClickListener { deleteSelected() }
        actionCancelSelection.setOnClickListener { exitSelectionMode() }

        uploadButton.isEnabled = false
        selectedFilesLabel.text = "未选择文件"
        uploadStatusCard.visibility = View.GONE
        selectionActionBar.visibility = View.GONE
    }

    // ===== 选择模式管理 =====
    private fun enterSelectionMode(initialItem: FileSystemItem? = null) {
        isSelectionMode = true
        selectedItems.clear()
        if (initialItem != null) {
            selectedItems.add(initialItem)
        }
        updateSelectionUI()
        adapter.setSelectionMode(true, selectedItems)
        selectFilesButton.visibility = View.GONE
        uploadStatusCard.visibility = View.GONE
        selectionActionBar.visibility = View.VISIBLE
        selectModeButton.text = "取消"
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        updateSelectionUI()
        adapter.setSelectionMode(false, emptySet())
        selectFilesButton.visibility = View.VISIBLE
        selectionActionBar.visibility = View.GONE
        selectModeButton.text = "选择"
        supportActionBar?.subtitle = if (currentPath.isEmpty()) "根目录" else currentPath
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(item: FileSystemItem) {
        if (!isSelectionMode) return
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
        } else {
            selectedItems.add(item)
        }
        updateSelectionUI()
        adapter.setSelectionMode(true, selectedItems)  // 刷新选中状态
        adapter.notifyItemChanged(fileList.indexOf(item))
    }

    private fun updateSelectionUI() {
        val count = selectedItems.size
        selectedCountText.text = "已选 $count 项"
        actionRename.isEnabled = count == 1
        actionMove.isEnabled = count > 0
        actionCopy.isEnabled = count > 0
        actionDelete.isEnabled = count > 0
        supportActionBar?.title = if (count == 0) "选择文件" else "已选 $count 项"
    }

    // ===== 批量操作 =====
    private fun deleteSelected() {
        if (selectedItems.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定要删除选中的 ${selectedItems.size} 个项目吗？")
            .setPositiveButton("删除") { _, _ ->
                coroutineScope.launch {
                    statusLabel.text = "正在删除..."
                    var allSuccess = true
                    for (item in selectedItems) {
                        val result = if (item.isDirectory) {
                            fileServerService.deleteDirectory(currentServerUrl, item.path)
                        } else {
                            fileServerService.deleteFile(currentServerUrl, item.path)
                        }
                        if (!result) allSuccess = false
                    }
                    exitSelectionMode()
                    loadCurrentDirectory(currentPath)
                    showToast(if (allSuccess) "删除完成" else "部分删除失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameSelected() {
        if (selectedItems.size != 1) return
        val item = selectedItems.first()
        val editText = EditText(this).apply {
            setText(item.name)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty()) {
                    showToast("名称不能为空")
                    return@setPositiveButton
                }
                coroutineScope.launch {
                    statusLabel.text = "正在重命名..."
                    val success = fileServerService.renameItem(currentServerUrl, item.path, newName)
                    if (success) {
                        exitSelectionMode()
                        loadCurrentDirectory(currentPath)
                        showToast("重命名成功")
                    } else {
                        showToast("重命名失败，请检查名称是否重复")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveSelected() {
        showTargetPathDialog("移动") { targetPath ->
            performMoveOrCopy(targetPath, isMove = true)
        }
    }

    private fun copySelected() {
        showTargetPathDialog("复制") { targetPath ->
            performMoveOrCopy(targetPath, isMove = false)
        }
    }

    private fun showTargetPathDialog(action: String, onConfirm: (String) -> Unit) {
        val editText = EditText(this).apply {
            hint = "输入目标路径（如 data/影视/第一季）"
            setText(currentPath)
        }
        AlertDialog.Builder(this)
            .setTitle("$action 到")
            .setView(editText)
            .setPositiveButton(action) { _, _ ->
                val target = editText.text.toString().trim()
                if (target.isEmpty()) {
                    showToast("目标路径不能为空")
                    return@setPositiveButton
                }
                onConfirm(target)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performMoveOrCopy(targetPath: String, isMove: Boolean) {
        if (selectedItems.isEmpty()) return
        coroutineScope.launch {
            statusLabel.text = "正在${if (isMove) "移动" else "复制"}..."
            var allSuccess = true
            for (item in selectedItems) {
                val dest = if (item.isDirectory) {
                    targetPath
                } else {
                    "$targetPath/${item.name}".trimStart('/')
                }
                val success = if (isMove) {
                    fileServerService.moveItem(currentServerUrl, item.path, dest)
                } else {
                    fileServerService.copyItem(currentServerUrl, item.path, dest)
                }
                if (!success) allSuccess = false
            }
            exitSelectionMode()
            loadCurrentDirectory(currentPath)
            showToast(if (allSuccess) "${if (isMove) "移动" else "复制"}完成" else "部分操作失败")
        }
    }

    // ===== 原有方法（保留） =====
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
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    handleBackNavigation()
                }
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
        supportActionBar?.subtitle = if (path.isEmpty()) "根目录" else path
        coroutineScope.launch {
            statusLabel.text = "正在加载文件列表..."
            invalidateOptionsMenu()
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, path)
                }
                fileList.clear()
                fileList.addAll(items)
                adapter.submitList(ArrayList(fileList))
                fileCountText.text = "${items.size} 个项目"
                statusLabel.text = if (path.isEmpty()) "根目录 - ${items.size} 个项目" else "当前路径: $path - ${items.size} 个项目"
                resetAutoPlay()
                if (isSelectionMode) exitSelectionMode()
            } catch (e: Exception) {
                statusLabel.text = "加载失败: ${e.message}"
                showToast("加载文件列表失败")
            } finally {
                invalidateOptionsMenu()
            }
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

    // ===== 文件选择与上传 =====
    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            handleFileSelection(it.data)
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

    // ===== 搜索 =====
    private fun searchFiles() {
        val query = searchEditText.text.toString().trim()
        if (query.isEmpty()) {
            loadCurrentDirectory(currentPath)
            return
        }
        coroutineScope.launch {
            val filtered = withContext(Dispatchers.IO) {
                fileList.filter { it.name.contains(query, true) || it.extension.contains(query, true) }
            }
            fileList.clear()
            fileList.addAll(filtered)
            adapter.submitList(ArrayList(fileList))
            fileCountText.text = "${filtered.size} 个搜索结果"
            statusLabel.text = "搜索 '${query}': 找到 ${filtered.size} 个结果"
            resetAutoPlay()
        }
    }

    // ===== 预览文件 =====
    private fun previewFile(item: FileSystemItem) {
        try {
            val fileType = FileTypeUtils.getFileType(item)
            if (fileType == "audio") {
                val allAudioItems = if (autoPlayEnabled && mediaFileList.isNotEmpty()) {
                    mediaFileList.filter { !it.isDirectory && FileTypeUtils.getFileType(it) == "audio" }
                } else {
                    fileList.filter { !it.isDirectory && FileTypeUtils.getFileType(it) == "audio" }
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
                    putExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, PlaylistDetailActivity.MODE_LIST)
                }
                startActivity(intent)
                return
            }
            if (fileType == "image") {
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
                val intent = Intent(this, ImageActivity::class.java).apply {
                    putExtra("FILE_NAME", item.name)
                    putExtra("FILE_URL", fileUrl)
                    putExtra("FILE_TYPE", "image")
                    putExtra("FILE_PATH", item.path)
                    putExtra("SERVER_URL", currentServerUrl)
                    putExtra("CURRENT_PATH", currentPath)
                    putExtra("SORT_BY", "name")
                    putExtra("SORT_ORDER", "asc")
                }
                startActivity(intent)
                return
            }
            if (fileType == "text") {
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
                val intent = Intent(this, TextPreviewActivity::class.java).apply {
                    putExtra("FILE_NAME", item.name)
                    putExtra("FILE_URL", fileUrl)
                    putExtra("FILE_PATH", item.path)
                }
                startActivity(intent)
                return
            }
            if (fileType == "video") {
                setupAutoPlay(item)
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("FILE_NAME", item.name)
                    putExtra("FILE_URL", fileUrl)
                    putExtra("FILE_TYPE", "video")
                    putExtra("FILE_PATH", item.path)
                    putExtra("AUTO_PLAY_ENABLED", autoPlayEnabled)
                    putExtra("MEDIA_FILE_LIST", ArrayList(mediaFileList))
                    putExtra("CURRENT_INDEX", currentPlayingIndex)
                    putExtra("SERVER_URL", currentServerUrl)
                    putExtra("CURRENT_PATH", currentPath)
                }
                startActivity(intent)
                return
            }
            // 通用
            val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
            val intent = Intent(this, GeneralPreviewActivity::class.java).apply {
                putExtra("FILE_NAME", item.name)
                putExtra("FILE_URL", fileUrl)
                putExtra("FILE_TYPE", fileType)
                putExtra("FILE_PATH", item.path)
                putExtra("SERVER_URL", currentServerUrl)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("FileListActivity", "预览失败", e)
            showToast("预览失败: ${e.message}")
        }
    }

    // ===== 自动连播 =====
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

    private fun getFileType(item: FileSystemItem): String {
        val ext = item.extension.removePrefix(".")
        return when {
            item.isVideo -> "video"
            item.isAudio -> "audio"
            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> "image"
            ext in listOf("txt", "log", "json", "xml", "csv", "md", "html", "htm", "css", "js", "java", "kt", "py") -> "text"
            else -> "general"
        }
    }

    // ===== 文件选择处理 =====
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
            Log.e("FileListActivity", "URI转换失败", e)
            null
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

    // ===== 删除单个文件 =====
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
            }
        }
    }

    // ===== 创建文件夹 =====
    private fun showCreateFolderDialog() {
        val editText = EditText(this).apply { hint = "请输入新文件夹名称" }
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val folderName = editText.text.toString().trim()
                if (folderName.isEmpty()) {
                    showToast("文件夹名称不能为空")
                    return@setPositiveButton
                }
                createFolder(folderName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createFolder(folderName: String) {
        val fullPath = if (currentPath.isEmpty()) folderName else "$currentPath/$folderName"
        coroutineScope.launch {
            statusLabel.text = "正在创建文件夹..."
            val success = withContext(Dispatchers.IO) {
                fileServerService.createDirectory(currentServerUrl, fullPath)
            }
            if (success) {
                showToast("文件夹创建成功")
                loadCurrentDirectory(currentPath)
            } else {
                showToast("创建失败，请检查权限或名称是否重复")
                statusLabel.text = "创建失败"
            }
        }
    }

    // ===== 工具方法 =====
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
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }
}