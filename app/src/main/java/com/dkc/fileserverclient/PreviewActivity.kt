package com.dkc.fileserverclient

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Animatable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.util.*

@UnstableApi
class PreviewActivity : AppCompatActivity(),
    LyricsManager.LyricsStateListener,
    LyricsManager.TimeProvider,
    LyricsManager.PlayStateProvider,
    ImagePreviewManager.ImageStateListener,
    AutoPlayManager.AutoPlayListener,
    MediaPlaybackListener,
    MediaProgressListener {

    // UI 组件
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var downloadButton: Button

    // 预览容器
    private lateinit var imageContainer: FrameLayout
    private lateinit var mediaContainer: FrameLayout
    private lateinit var textContainer: FrameLayout
    private lateinit var generalContainer: FrameLayout
    private lateinit var errorTextView: TextView

    // 图片预览组件
    private lateinit var imagePreview: ImageView
    private lateinit var imageLoadingProgress: ProgressBar

    // 媒体播放组件
    private lateinit var playerView: PlayerView
    private lateinit var audioCoverView: ImageView
    private lateinit var mediaLoadingProgress: ProgressBar
    private lateinit var mediaControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView

    // 歌词显示组件
    private lateinit var lyricsContainer: LinearLayout
    private lateinit var lyricsTitle: TextView
    private lateinit var currentLyricsLine: TextView
    private lateinit var nextLyricsLine: TextView
    private lateinit var lyricsSettingsButton: Button

    // 通用预览组件
    private lateinit var webViewPreview: WebView
    private lateinit var generalLoadingProgress: ProgressBar

    // 状态变量
    private var currentFileType = ""
    private var currentFileUrl = ""
    private var currentFileName = ""
    private var isLyricsVisible = false
    private var currentImageIndex = -1
    private var imageFileList = mutableListOf<FileSystemItem>()
    private var currentImageDirectoryPath = ""

    // 音频相关变量
    private var currentAudioIndex = -1
    private var audioTracks: List<AudioTrack> = emptyList()
    private var currentAudioTrack: AudioTrack? = null

    // 手势检测
    private lateinit var gestureDetector: GestureDetector
    private var isLongPressDetected = false

    // 应用状态标志
    private var isAppInBackground = false

    // 网络客户端和协程
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // 管理器实例
    private lateinit var lyricsManager: LyricsManager
    private lateinit var imageManager: ImagePreviewManager
    private lateinit var fullscreenManager: FullscreenManager
    private lateinit var autoPlayManager: AutoPlayManager

    // 统一的媒体播放控制器
    private lateinit var mediaPlaybackController: MediaPlaybackController

    // 歌词对话框
    private var lyricsDialog: AlertDialog? = null

    // 服务器信息
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""

    // 当前播放类型
    private var currentPlaybackType: PlaybackType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()
        setupIntentData()
        initManagers()
        setupGestureDetector()
        setupEventListeners()
        loadPreview()

        // 获取自动连播相关参数
        val autoPlayEnabled = intent.getBooleanExtra("AUTO_PLAY_ENABLED", false)
        val mediaFileList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST", FileSystemItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST")
        }

        currentAudioIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        // 获取当前文件类型
        currentFileType = intent.getStringExtra("FILE_TYPE") ?: "unknown"

        // 关键：获取是否从音乐库进入的标志
        val fromMusicLibrary = intent.getBooleanExtra("FROM_MUSIC_LIBRARY", false)

        // 关键修复：只在当前文件是音频时才设置自动播放
        if (currentFileType == "audio") {
            autoPlayManager.setupAutoPlay(
                enabled = autoPlayEnabled,
                fileList = mediaFileList,
                audioTracks = audioTracks,
                currentIndex = currentAudioIndex,
                serverUrl = currentServerUrl,
                directoryPath = currentDirectoryPath
            )

            // 重要：如果从音乐库进入，确保自动播放启用
            if (fromMusicLibrary && !autoPlayEnabled) {
                Log.d("PreviewActivity", "从音乐库进入，强制启用自动播放")
                autoPlayManager.setupAutoPlay(
                    enabled = true,
                    fileList = mediaFileList,
                    audioTracks = audioTracks,
                    currentIndex = currentAudioIndex,
                    serverUrl = currentServerUrl,
                    directoryPath = currentDirectoryPath
                )
            }
        } else if (currentFileType != "image") {
            // 非图片非音频文件使用原有的自动连播逻辑
            autoPlayManager.setupAutoPlay(
                enabled = autoPlayEnabled,
                fileList = mediaFileList,
                audioTracks = null,
                currentIndex = currentAudioIndex,
                serverUrl = currentServerUrl,
                directoryPath = currentDirectoryPath
            )
        }

        Log.d("PreviewActivity", "初始化完成: fileType=$currentFileType, autoPlayEnabled=${autoPlayManager.isAutoPlayEnabled()}, fromMusicLibrary=$fromMusicLibrary")
    }

    private fun initViews() {
        // 初始化所有视图组件
        titleBar = findViewById(R.id.titleBar)
        backButton = findViewById(R.id.backButton)
        fileNameTextView = findViewById(R.id.fileNameTextView)
        fileTypeTextView = findViewById(R.id.fileTypeTextView)
        downloadButton = findViewById(R.id.downloadButton)

        imageContainer = findViewById(R.id.imageContainer)
        mediaContainer = findViewById(R.id.mediaContainer)
        textContainer = findViewById(R.id.textContainer)
        generalContainer = findViewById(R.id.generalContainer)
        errorTextView = findViewById(R.id.errorTextView)

        imagePreview = findViewById(R.id.imagePreview)
        imageLoadingProgress = findViewById(R.id.imageLoadingProgress)

        playerView = findViewById(R.id.playerView)
        audioCoverView = findViewById(R.id.audioCoverView)
        mediaLoadingProgress = findViewById(R.id.mediaLoadingProgress)
        mediaControls = findViewById(R.id.mediaControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        fullscreenToggleButton = findViewById(R.id.fullscreenToggleButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)

        lyricsContainer = findViewById(R.id.lyricsContainer)
        lyricsTitle = findViewById(R.id.lyricsTitle)
        currentLyricsLine = findViewById(R.id.currentLyricsLine)
        nextLyricsLine = findViewById(R.id.nextLyricsLine)
        lyricsSettingsButton = findViewById(R.id.lyricsSettingsButton)

        webViewPreview = findViewById(R.id.webViewPreview)
        generalLoadingProgress = findViewById(R.id.generalLoadingProgress)

        // 初始化WebView
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webViewPreview.settings.javaScriptEnabled = true
        webViewPreview.settings.loadWithOverviewMode = true
        webViewPreview.settings.useWideViewPort = true
        webViewPreview.settings.builtInZoomControls = true
        webViewPreview.settings.displayZoomControls = false

        webViewPreview.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                generalLoadingProgress.visibility = View.GONE
            }
        }
    }

    private fun setupIntentData() {
        // 从Intent获取文件信息
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFileType = intent.getStringExtra("FILE_TYPE") ?: "unknown"
        currentAudioIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        // 从Intent获取AudioTrack数据
        currentAudioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("AUDIO_TRACK", AudioTrack::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("AUDIO_TRACK")
        }

        // 获取AudioTrack列表
        audioTracks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("AUDIO_TRACKS", AudioTrack::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("AUDIO_TRACKS") ?: emptyList()
        }

        fileNameTextView.text = currentFileName
        fileTypeTextView.text = when (currentFileType) {
            "image" -> "图片"
            "video" -> "视频"
            "audio" -> "音频"
            "text" -> "文本"
            else -> "文件"
        }
    }

    private fun initManagers() {
        // 初始化歌词管理器
        lyricsManager = LyricsManager(this, handler, coroutineScope)
        lyricsManager.setListener(this)

        // 初始化图片管理器
        imageManager = ImagePreviewManager(
            context = this,
            coroutineScope = coroutineScope,
            imageView = imagePreview,
            loadingProgress = imageLoadingProgress,
            httpClient = client
        )
        imageManager.setListener(this)

        // 初始化媒体播放控制器
        initMediaPlaybackController()

        // 初始化全屏管理器
        fullscreenManager = FullscreenManager(
            activity = this,
            titleBar = titleBar,
            fileTypeTextView = fileTypeTextView,
            fullscreenToggleButton = fullscreenToggleButton
        )

        // 初始化自动连播管理器
        autoPlayManager = AutoPlayManager(handler, coroutineScope)
        autoPlayManager.setAutoPlayListener(this)

        // 设置进度条监听
        setupSeekBar()
    }

    private fun initMediaPlaybackController() {
        // 根据文件类型创建对应的播放控制器
        currentPlaybackType = when (currentFileType) {
            "video" -> PlaybackType.VIDEO
            "audio" -> PlaybackType.AUDIO
            else -> null
        }

        currentPlaybackType?.let { playbackType ->
            // 关键修复：根据不同类型创建不同的播放控制器
            mediaPlaybackController = if (playbackType == PlaybackType.VIDEO) {
                // 视频播放器需要UI组件
                MediaPlaybackFactory.createController(
                    type = playbackType,
                    httpClient = client,
                    playerView = playerView,
                    videoLoadingProgress = mediaLoadingProgress,
                    playPauseButton = playPauseButton,
                    seekBar = seekBar,
                    currentTimeTextView = currentTimeTextView,
                    durationTextView = durationTextView,
                    uiHandler = handler
                )
            } else {
                // 音频播放器只需要httpClient
                MediaPlaybackFactory.createController(
                    type = playbackType,
                    httpClient = client
                )
            }

            // 初始化控制器
            mediaPlaybackController.initialize(this, handler)

            // 添加监听器
            mediaPlaybackController.addPlaybackListener(this)
            mediaPlaybackController.addProgressListener(this)

            Log.d("PreviewActivity", "初始化媒体播放控制器: $playbackType")
        }
    }

    private fun setupSeekBar() {
        seekBar.max = 1000

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = mediaPlaybackController.getDuration()
                    if (duration > 0) {
                        val newPosition = (duration * progress / 1000).toLong()
                        currentTimeTextView.text = formatTime(newPosition)
                        mediaPlaybackController.seekTo(newPosition)
                        Log.d("PreviewActivity", "用户拖动进度条到: $newPosition ms")
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // 暂时不需要特殊处理
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 暂时不需要特殊处理
            }
        })
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLongPressDetected) {
                    handleSingleTap()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLongPressDetected) {
                    handleDoubleTap()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress()
            }
        })

        gestureDetector.setIsLongpressEnabled(true)

        // 为媒体容器设置触摸监听
        mediaContainer.setOnTouchListener { _, event ->
            val handledByGesture = gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressDetected = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressDetected) {
                        isLongPressDetected = false
                    }
                }
            }
            true
        }

        // 为图片容器添加左右滑动手势检测
        imagePreview.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private val SWIPE_THRESHOLD = 100f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.x
                        val diffX = endX - startX

                        // 判断左右滑动
                        if (Math.abs(diffX) > SWIPE_THRESHOLD) {
                            if (diffX > 0) {
                                // 向右滑动 - 上一张图片
                                loadPreviousImage()
                            } else {
                                // 向左滑动 - 下一张图片
                                loadNextImage()
                            }
                            return true
                        }
                    }
                }
                return true
            }
        })
    }

    private fun handleSingleTap() {
        if (isLongPressDetected) {
            return
        }

        if (fullscreenManager.isFullscreen()) {
            toggleControlsVisibility()
        } else {
            if (currentFileType == "video" || currentFileType == "audio") {
                mediaPlaybackController.togglePlayback()
            }
        }
    }

    private fun handleDoubleTap() {
        if (isLongPressDetected) {
            return
        }

        if (currentFileType == "video" || currentFileType == "audio") {
            mediaPlaybackController.togglePlayback()
        }
    }

    private fun handleLongPress() {
        // 长按处理（如果需要）
        isLongPressDetected = true
    }

    private fun toggleControlsVisibility() {
        if (mediaControls.visibility == View.VISIBLE) {
            mediaControls.visibility = View.GONE
        } else {
            mediaControls.visibility = View.VISIBLE
        }
    }

    private fun setupEventListeners() {
        backButton.setOnClickListener {
            onBackPressed()
        }

        downloadButton.setOnClickListener {
            downloadFile()
        }

        playPauseButton.setOnClickListener {
            mediaPlaybackController.togglePlayback()
        }

        previousButton.setOnClickListener {
            if (autoPlayManager.isAudioMode()) {
                // 音频模式由控制器处理
                mediaPlaybackController.playPrevious()
            } else {
                // 视频模式使用AutoPlayManager
                autoPlayManager.playPreviousMedia()
            }
        }

        nextButton.setOnClickListener {
            if (autoPlayManager.isAudioMode()) {
                // 音频模式由控制器处理
                mediaPlaybackController.playNext()
            } else {
                // 视频模式使用AutoPlayManager
                autoPlayManager.playNextMedia()
            }
        }

        fullscreenToggleButton.setOnClickListener {
            toggleFullscreen()
        }

        lyricsSettingsButton.setOnClickListener {
            showLyricsSettingsDialog()
        }
    }

    private fun toggleFullscreen() {
        if (fullscreenManager.isFullscreen()) {
            fullscreenManager.exitFullscreen()
        } else {
            fullscreenManager.enterFullscreen()
        }
    }

    private fun loadPreview() {
        Log.d("PreviewActivity", "加载预览，文件类型: $currentFileType")

        when (currentFileType) {
            "image" -> loadImagePreview()
            "video", "audio" -> loadMediaPreview()
            "text" -> loadTextPreview()
            else -> loadGeneralPreview()
        }
    }

    private fun loadImagePreview() {
        Log.d("PreviewActivity", "加载图片预览")

        showContainer(imageContainer)
        fileTypeTextView.visibility = View.VISIBLE

        // 使用管理器加载图片
        imageManager.loadImage(currentFileUrl, currentFileName)

        // 加载当前目录下的图片列表（用于左右滑动切换）
        loadImageFileList()
    }

    private fun loadImageFileList() {
        coroutineScope.launch {
            try {
                // 获取当前图片所在的目录路径
                currentImageDirectoryPath = getImageDirectoryPath()

                // 获取该目录下的所有文件
                val allFiles = withContext(Dispatchers.IO) {
                    FileServerService(this@PreviewActivity)
                        .getFileList(currentServerUrl, currentImageDirectoryPath)
                }

                // 过滤出图片文件
                imageFileList.clear()
                imageFileList.addAll(allFiles.filter { item ->
                    !item.isDirectory && isImageFile(item)
                })

                // 查找当前图片在列表中的索引
                currentImageIndex = imageFileList.indexOfFirst { item ->
                    getFullImageUrl(item) == currentFileUrl
                }

                Log.d("PreviewActivity", "图片列表加载完成: 总数=${imageFileList.size}, 当前索引=$currentImageIndex")

                // 如果没有找到当前图片，可能是从不同路径进入，使用文件名匹配
                if (currentImageIndex == -1) {
                    currentImageIndex = imageFileList.indexOfFirst { item ->
                        item.name == currentFileName
                    }
                    Log.d("PreviewActivity", "使用文件名匹配: 索引=$currentImageIndex")
                }

            } catch (e: Exception) {
                Log.e("PreviewActivity", "加载图片列表失败", e)
            }
        }
    }

    private fun isImageFile(item: FileSystemItem): Boolean {
        val imageExtensions = listOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            "JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP"
        )
        return imageExtensions.any { item.extension.contains(it, true) }
    }

    private fun getFullImageUrl(item: FileSystemItem): String {
        val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
        return "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
    }

    private fun getImageDirectoryPath(): String {
        return try {
            // 从Intent中获取完整路径
            val intentPath = intent.getStringExtra("FILE_PATH")
            if (intentPath != null && intentPath.isNotEmpty()) {
                // 提取目录部分
                val file = java.io.File(intentPath)
                val parent = file.parent ?: ""
                Log.d("PreviewActivity", "从FILE_PATH获取目录: $parent")
                return parent
            }

            // 如果没有FILE_PATH，尝试从当前路径获取
            if (currentDirectoryPath.isNotEmpty()) {
                Log.d("PreviewActivity", "使用当前路径作为目录: $currentDirectoryPath")
                return currentDirectoryPath
            }

            // 默认返回空路径
            ""
        } catch (e: Exception) {
            Log.e("PreviewActivity", "获取图片目录失败", e)
            ""
        }
    }

    private fun loadPreviousImage() {
        if (imageFileList.isEmpty() || currentImageIndex <= 0) {
            Log.d("PreviewActivity", "已经是第一张图片")
            return
        }

        val prevIndex = currentImageIndex - 1
        if (prevIndex >= 0 && prevIndex < imageFileList.size) {
            val prevItem = imageFileList[prevIndex]
            loadImageByItem(prevItem, prevIndex)
        }
    }

    private fun loadNextImage() {
        if (imageFileList.isEmpty() || currentImageIndex >= imageFileList.size - 1) {
            Log.d("PreviewActivity", "已经是最后一张图片")
            return
        }

        val nextIndex = currentImageIndex + 1
        if (nextIndex < imageFileList.size) {
            val nextItem = imageFileList[nextIndex]
            loadImageByItem(nextItem, nextIndex)
        }
    }

    private fun loadImageByItem(item: FileSystemItem, index: Int) {
        try {
            val imageUrl = getFullImageUrl(item)

            // 更新当前文件信息
            currentFileName = item.name
            currentFileUrl = imageUrl
            currentImageIndex = index

            // 更新UI
            fileNameTextView.text = currentFileName

            // 显示加载进度
            imageLoadingProgress.visibility = View.VISIBLE

            // 加载新图片
            imageManager.loadImage(imageUrl, currentFileName)

            Log.d("PreviewActivity", "切换到图片: ${item.name}, 索引: $index")
        } catch (e: Exception) {
            Log.e("PreviewActivity", "切换图片失败", e)
        }
    }

    private fun loadMediaPreview() {
        showContainer(mediaContainer)
        fileTypeTextView.visibility = View.VISIBLE

        // 显示媒体控制栏
        mediaControls.visibility = View.VISIBLE

        // 根据文件类型设置UI
        if (currentFileType == "video") {
            playerView.visibility = View.VISIBLE
            audioCoverView.visibility = View.GONE
            lyricsContainer.visibility = View.GONE
        } else if (currentFileType == "audio") {
            playerView.visibility = View.GONE
            audioCoverView.visibility = View.VISIBLE
            lyricsContainer.visibility = View.VISIBLE

            // 重置歌词状态
            lyricsManager.clear()
            currentLyricsLine.text = "正在加载歌词..."
            nextLyricsLine.text = ""

            // 使用正确的音频轨道名称
            val audioTitle = currentAudioTrack?.name ?: currentFileName
            lyricsTitle.text = audioTitle

            // 立即加载歌词
            handler.post {
                Log.d("PreviewActivity", "音频预览：立即加载歌词")
                loadLyricsForCurrentSong()
            }
        }

        // 检查是否从音乐库进入（需要自动播放）
        val shouldAutoPlay = intent.getBooleanExtra("SHOULD_AUTO_PLAY", false)
        val fromMusicLibrary = intent.getBooleanExtra("FROM_MUSIC_LIBRARY", false)
        val immediatePlay = intent.getBooleanExtra("IMMEDIATE_PLAY", false)

        Log.d("PreviewActivity", "加载媒体预览: shouldAutoPlay=$shouldAutoPlay, fromMusicLibrary=$fromMusicLibrary, immediatePlay=$immediatePlay")

        // 创建媒体播放项
        val mediaItem = if (currentFileType == "audio" && currentAudioTrack != null) {
            // 使用现有的转换方法
            MediaPlaybackItem.fromAudioTrack(currentAudioTrack!!)
        } else {
            MediaPlaybackItem(
                id = "${currentFileType}_${System.currentTimeMillis()}",
                name = currentFileName,
                url = currentFileUrl,
                path = currentDirectoryPath,
                type = if (currentFileType == "video") PlaybackType.VIDEO else PlaybackType.AUDIO,
                duration = 0L,
                metadata = emptyMap()
            )
        }

        // 关键修复：根据是否有播放列表调用正确的play方法
        if (audioTracks.isNotEmpty() && currentFileType == "audio") {
            val playlist = audioTracks.map { MediaPlaybackItem.fromAudioTrack(it) }
            // 使用带播放列表的方法
            mediaPlaybackController.play(mediaItem, playlist, currentAudioIndex)
        } else {
            // 使用不带播放列表的方法（单文件播放）
            mediaPlaybackController.play(currentFileUrl, mediaItem)
        }

        // 如果是自动播放模式
        if ((shouldAutoPlay || fromMusicLibrary || immediatePlay) && currentFileType == "audio") {
            handler.postDelayed({
                mediaPlaybackController.resume()
            }, 150)
        }
    }

    private fun loadTextPreview() {
        // 启动新的文本预览Activity
        val intent = Intent(this, TextPreviewActivity::class.java).apply {
            putExtra("FILE_NAME", currentFileName)
            putExtra("FILE_URL", currentFileUrl)
        }
        startActivity(intent)
        finish()
    }

    private fun loadGeneralPreview() {
        showContainer(generalContainer)
        fileTypeTextView.visibility = View.VISIBLE

        webViewPreview.loadUrl(currentFileUrl)
    }

    private fun showContainer(container: View) {
        // 隐藏所有容器
        imageContainer.visibility = View.GONE
        mediaContainer.visibility = View.GONE
        textContainer.visibility = View.GONE
        generalContainer.visibility = View.GONE
        errorTextView.visibility = View.GONE

        // 显示指定的容器
        container.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        showContainer(errorTextView)
        errorTextView.text = message
    }

    private fun downloadFile() {
        Toast.makeText(this, "开始下载: $currentFileName", Toast.LENGTH_SHORT).show()
    }

    // ==================== 接口实现 ====================

    // AutoPlayManager.AutoPlayListener 接口实现
    override fun onLoadMediaFile(fileName: String, fileUrl: String, fileType: String, index: Int, filePath: String) {
        Log.d("PreviewActivity", "加载媒体文件: $fileName, 类型: $fileType, 索引: $index, 路径: $filePath")

        // 停止当前播放
        mediaPlaybackController.stop()

        // 更新当前文件信息
        currentFileName = fileName
        currentFileUrl = fileUrl
        currentFileType = fileType

        // 更新UI
        fileNameTextView.text = currentFileName
        fileTypeTextView.text = when (currentFileType) {
            "image" -> "图片"
            "video" -> "视频"
            "audio" -> "音频"
            "text" -> "文本"
            else -> "文件"
        }

        // 重新加载预览
        loadPreview()
    }

    override fun onLoadAudioTrack(track: AudioTrack, index: Int) {
        Log.d("PreviewActivity", "加载音频轨道: ${track.name}, 索引: $index")

        // 停止当前播放
        mediaPlaybackController.stop()

        // 更新当前音频轨道
        currentAudioTrack = track

        // 更新UI
        fileNameTextView.text = track.name
        fileTypeTextView.text = "音频"

        // 更新歌词标题
        lyricsTitle.text = track.name

        // 重置歌词显示
        currentLyricsLine.text = "正在加载歌词..."
        nextLyricsLine.text = ""

        // 加载歌词
        handler.postDelayed({
            loadLyricsForCurrentSong()
        }, 500)

        // 播放音频 - 根据是否有播放列表调用正确的play方法
        val mediaItem = MediaPlaybackItem.fromAudioTrack(track)
        if (audioTracks.isNotEmpty()) {
            val playlist = audioTracks.map { MediaPlaybackItem.fromAudioTrack(it) }
            mediaPlaybackController.play(mediaItem, playlist, index)
        } else {
            mediaPlaybackController.play(track.url ?: track.path, mediaItem)
        }
    }

    override fun onAutoPlayError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ImagePreviewManager.ImageStateListener
    override fun onImageLoadStart() {
        imageLoadingProgress.visibility = View.VISIBLE
    }

    override fun onImageLoadSuccess(isGif: Boolean) {
        imageLoadingProgress.visibility = View.GONE

        if (isGif) {
            // 确保GIF动画开始
            val drawable = imagePreview.drawable
            if (drawable is Animatable) {
                (drawable as Animatable).start()
            }
        }
    }

    override fun onImageLoadError(message: String) {
        imageLoadingProgress.visibility = View.GONE
        showError(message)
    }

    override fun onDoubleTap() {
        Log.d("PreviewActivity", "图片双击事件")
    }

    // LyricsManager.LyricsStateListener
    override fun onLyricsLoaded(data: LyricsData?, title: String?) {
        Log.d("PreviewActivity", "歌词加载成功: title=$title, 当前歌曲名=${currentAudioTrack?.name}, 歌词标题=${lyricsTitle.text}")

        lyricsManager.startLyricsUpdates()
        showToast("歌词加载成功")
    }

    override fun onLyricsUpdated(currentLine: String?, nextLine: String?) {
        currentLyricsLine.text = currentLine ?: ""
        nextLyricsLine.text = nextLine ?: ""
    }

    override fun onLyricsError(message: String) {
        Log.e("PreviewActivity", "歌词错误: $message")
        currentLyricsLine.text = message
        nextLyricsLine.text = ""
    }

    override fun onLyricsFileSelected(files: List<FileServerService.LyricsFileInfo>) {
        showLyricsFileSelectionDialog(files)
    }

    override fun onNoLyrics() {
        Log.d("PreviewActivity", "无歌词")
        currentLyricsLine.text = "此歌曲无歌词"
        nextLyricsLine.text = ""
        lyricsTitle.text = "无歌词"
    }

    // LyricsManager.TimeProvider
    override fun getCurrentTime(): Long {
        return mediaPlaybackController.getCurrentPosition()
    }

    // LyricsManager.PlayStateProvider
    override fun isPlaying(): Boolean {
        return mediaPlaybackController.isPlaying()
    }

    // MediaPlaybackListener 实现
    override fun onPlaybackStateChanged(status: MediaPlaybackStatus) {
        Log.d("PreviewActivity", "播放状态变化: ${status.state}")

        // 更新播放/暂停按钮
        val playPauseIcon = if (status.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        playPauseButton.setImageResource(playPauseIcon)

        // 更新文件名显示
        status.currentItem?.let { item ->
            if (fileNameTextView.text != item.name) {
                fileNameTextView.text = item.name
            }
        }

        // 更新歌词标题
        if (currentFileType == "audio") {
            status.currentItem?.let { item ->
                if (lyricsTitle.text != item.name) {
                    lyricsTitle.text = item.name
                    // 重新加载歌词
                    loadLyricsForCurrentSong()
                }
            }
        }

        // 更新进度显示
        if (status.duration > 0) {
            durationTextView.text = formatTime(status.duration)
        }

        // 缓冲状态处理
        if (status.state == PlaybackState.BUFFERING) {
            mediaLoadingProgress.visibility = View.VISIBLE
        } else {
            mediaLoadingProgress.visibility = View.GONE
        }
    }

    override fun onTrackChanged(item: MediaPlaybackItem, index: Int) {
        Log.d("PreviewActivity", "轨道变化: ${item.name}, 索引: $index")

        // 轨道变化时更新UI
        handler.post {
            fileNameTextView.text = item.name

            if (currentFileType == "audio") {
                currentAudioTrack = item.toAudioTrack()
                lyricsTitle.text = item.name

                // 重要：确保歌词容器可见
                lyricsContainer.visibility = View.VISIBLE
                isLyricsVisible = true

                // 重置当前显示的歌词行
                currentLyricsLine.text = "正在加载歌词..."
                nextLyricsLine.text = ""

                // 重新加载歌词
                loadLyricsForCurrentSong()
            }
        }
    }

    override fun onPlaybackError(error: String) {
        showError(error)
    }

    override fun onPlaybackEnded() {
        // 播放结束处理
        if (autoPlayManager.isVideoMode()) {
            // 视频模式：使用AutoPlayManager
            if (autoPlayManager.isAutoPlayEnabled()) {
                handler.postDelayed({
                    autoPlayManager.playNextMedia()
                }, 1000)
            }
        } else if (autoPlayManager.isAudioMode()) {
            // 音频模式：由控制器自动处理下一首
            Log.d("PreviewActivity", "音频播放结束，由控制器处理自动连播")
        }
    }

    override fun onMediaBuffering(isBuffering: Boolean) {
        // 缓冲状态处理
        if (isBuffering) {
            mediaLoadingProgress.visibility = View.VISIBLE
        } else {
            mediaLoadingProgress.visibility = View.GONE
        }
    }

    // MediaProgressListener 实现
    override fun onProgressUpdated(position: Long, duration: Long) {
        // 更新进度条
        if (duration > 0 && !isAppInBackground) {
            val progress = (position * 1000 / duration).toInt()
            handler.post {
                seekBar.progress = progress
                currentTimeTextView.text = formatTime(position)
                durationTextView.text = formatTime(duration)
            }
        }
    }

    override fun onBufferingProgress(percent: Int) {
        // 缓冲进度
        Log.d("PreviewActivity", "缓冲进度: $percent%")
    }

    // ==================== 歌词相关方法 ====================

    private fun loadLyricsForCurrentSong() {
        val songPath = getCurrentSongPath()
        Log.d("PreviewActivity", "加载歌词: 歌曲路径=$songPath, 文件名=$currentFileName, 服务器=$currentServerUrl")
        lyricsManager.loadLyrics(currentServerUrl, songPath, currentFileName)
    }

    private fun getCurrentSongPath(): String {
        Log.d("PreviewActivity", "getCurrentSongPath调用: autoPlayEnabled=${autoPlayManager.isAutoPlayEnabled()}")

        // 优先使用AudioTrack的路径
        currentAudioTrack?.let {
            Log.d("PreviewActivity", "使用AudioTrack路径: ${it.path}")
            return it.path
        }

        // 方法1：尝试从自动连播管理器获取完整路径
        if (autoPlayManager.isAutoPlayEnabled()) {
            val filePath = autoPlayManager.getCurrentFilePath()
            if (filePath.isNotEmpty()) {
                Log.d("PreviewActivity", "自动连播模式: 使用管理器路径=$filePath")
                return filePath
            }
        }

        // 方法2：从Intent中提取FILE_PATH（完整路径）
        val intentPath = intent.getStringExtra("FILE_PATH")
        if (intentPath != null && intentPath.isNotEmpty()) {
            Log.d("PreviewActivity", "使用Intent的FILE_PATH: $intentPath")
            return intentPath
        }

        // 方法3：如果FILE_PATH不存在，尝试使用FILE_NAME（可能是从文件列表直接点击的）
        Log.d("PreviewActivity", "使用当前文件名作为路径: $currentFileName")
        return currentFileName
    }

    private fun showLyricsFileSelectionDialog(files: List<FileServerService.LyricsFileInfo>) {
        val fileNames = files.map { it.name }.toMutableList()
        fileNames.add("无歌词（如纯音乐）")

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择歌词文件")
            .setItems(fileNames.toTypedArray()) { _, which ->
                if (which == fileNames.size - 1) {
                    // 选择了"无歌词"选项
                    markAsNoLyrics()
                } else {
                    val selectedFile = files[which]
                    coroutineScope.launch {
                        try {
                            val success = lyricsManager.saveLyricsMapping(
                                currentServerUrl,
                                getCurrentSongPath(),
                                selectedFile.path
                            )

                            if (success) {
                                // 重新加载歌词
                                lyricsManager.loadLyrics(currentServerUrl, getCurrentSongPath(), currentFileName)
                                showToast("歌词映射保存成功")
                            } else {
                                showToast("保存歌词映射失败")
                            }
                        } catch (e: Exception) {
                            showToast("保存歌词映射失败: ${e.message}")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
    }

    private fun showLyricsSettingsDialog() {
        val options = arrayOf("重新加载歌词", "选择歌词文件", "标记为无歌词", "隐藏歌词")

        val dialog = AlertDialog.Builder(this)
            .setTitle("歌词设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> lyricsManager.loadLyrics(currentServerUrl, getCurrentSongPath(), currentFileName)
                    1 -> showDirectoryLyricsFiles()
                    2 -> markAsNoLyrics()
                    3 -> hideLyrics()
                }
            }
            .setNegativeButton("取消", null)
            .create()

        dialog.show()
        lyricsDialog = dialog
    }

    private fun markAsNoLyrics() {
        coroutineScope.launch {
            try {
                val success = lyricsManager.markNoLyrics(currentServerUrl, getCurrentSongPath())

                if (success) {
                    // 更新UI显示无歌词
                    lyricsManager.clear()
                    currentLyricsLine.text = "此歌曲无歌词"
                    nextLyricsLine.text = ""
                    lyricsTitle.text = "无歌词"
                    showToast("已标记为无歌词")
                } else {
                    showToast("标记失败")
                }
            } catch (e: Exception) {
                showToast("标记失败: ${e.message}")
            }
        }
    }

    private fun showDirectoryLyricsFiles() {
        val songPath = getCurrentSongPath()
        val directory = java.io.File(songPath).parent ?: ""

        coroutineScope.launch {
            try {
                val lyricsFiles = lyricsManager.getLyricsFiles(currentServerUrl, directory)

                if (lyricsFiles.isNotEmpty()) {
                    showLyricsFileSelectionDialog(lyricsFiles)
                } else {
                    showToast("当前目录下没有找到歌词文件")
                }
            } catch (e: Exception) {
                showToast("获取歌词文件列表失败: ${e.message}")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && isAppInBackground) {
            // 应用从后台回到前台
            Log.d("PreviewActivity", "应用获得焦点，恢复歌词更新")
            isAppInBackground = false

            // 恢复歌词更新
            if (isLyricsVisible && lyricsManager.getLyricsData() != null) {
                handler.postDelayed({
                    lyricsManager.startLyricsUpdates()

                    // 强制更新一次
                    val currentTime = getCurrentTime()
                    lyricsManager.getLyricsData()?.let { data ->
                        val currentLine = findCurrentLyricsLine(data.lines, currentTime)
                        val nextLine = findNextLyricsLine(data.lines, currentTime)

                        currentLyricsLine.text = currentLine?.text ?: ""
                        nextLyricsLine.text = nextLine?.text ?: ""
                    }
                }, 200) // 延迟200ms确保UI完全恢复
            }
        } else if (!hasFocus && !isAppInBackground) {
            // 应用失去焦点，进入后台
            Log.d("PreviewActivity", "应用失去焦点，暂停歌词更新")
            isAppInBackground = true
            lyricsManager.stopLyricsUpdates()
        }
    }

    private fun hideLyrics() {
        lyricsContainer.visibility = View.GONE
        isLyricsVisible = false
        lyricsManager.stopLyricsUpdates()
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()

        // 让播放器自己处理暂停
        if (currentFileType == "video" || currentFileType == "audio") {
            mediaPlaybackController.onActivityPause()
        }

        // 只处理UI相关的暂停
        handler.removeCallbacksAndMessages(null)
        lyricsManager.stopLyricsUpdates()
        imageManager.clear()
        lyricsDialog?.dismiss()
        isLongPressDetected = false
        isAppInBackground = true

        Log.d("PreviewActivity", "onPause: Activity暂停")
    }

    override fun onResume() {
        super.onResume()

        // 恢复全屏状态
        if (fullscreenManager.isFullscreen()) {
            fullscreenManager.enterFullscreen()
        }

        // 让播放器自己处理恢复
        if (currentFileType == "video" || currentFileType == "audio") {
            mediaPlaybackController.onActivityResume()
        }

        // 恢复歌词更新
        if (isLyricsVisible && lyricsManager.getLyricsData() != null) {
            Log.d("PreviewActivity", "onResume: 恢复歌词更新")
            lyricsManager.startLyricsUpdates()
        }

        isAppInBackground = false
        Log.d("PreviewActivity", "onResume: Activity恢复")
    }

    // 添加辅助方法
    private fun findCurrentLyricsLine(lines: List<LyricsLine>, currentTime: Long): LyricsLine? {
        for (i in lines.indices.reversed()) {
            if (currentTime >= lines[i].time) {
                return lines[i]
            }
        }
        return null
    }

    private fun findNextLyricsLine(lines: List<LyricsLine>, currentTime: Long): LyricsLine? {
        for (i in lines.indices) {
            if (currentTime < lines[i].time) {
                return lines[i]
            }
        }
        return null
    }


    override fun onDestroy() {
        super.onDestroy()

        Log.d("PreviewActivity", "onDestroy: 开始销毁，文件类型: $currentFileType")

        // 让播放器自己决定如何处理销毁
        if (currentFileType == "video" || currentFileType == "audio") {
            // 对于音频播放器，它支持后台播放，所以传入true
            // 对于视频播放器，它会忽略keepAlive参数
            val keepAlive = currentFileType == "audio"
            mediaPlaybackController.release(keepAlive)
        }

        // 清理其他资源（这些与播放器无关）
        coroutineScope.cancel()
        handler.removeCallbacksAndMessages(null)
        imageManager.clear()
        lyricsManager.clear()
        lyricsDialog?.dismiss()

        Log.d("PreviewActivity", "onDestroy: Activity销毁完成")
    }

    // PreviewActivity.kt - 修改 onBackPressed() 方法
    override fun onBackPressed() {
        val resultIntent = Intent().apply {
            if (autoPlayManager.isAutoPlayEnabled()) {
                putExtra("ACTION", "EXIT_AUTO_PLAY")
            }
        }
        setResult(RESULT_OK, resultIntent)

        if (fullscreenManager.onBackPressed()) {
            return
        }

        // 重要：返回时保持音频播放
        if (currentFileType == "audio") {
            // 确保音频继续播放
            if (mediaPlaybackController.isPlaying()) {
                Log.d("PreviewActivity", "返回时保持音频播放")
            }
        }

        super.onBackPressed()
    }
}