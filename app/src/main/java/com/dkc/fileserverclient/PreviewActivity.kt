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
    VideoPlayerManager.PlayerStateListener,
    GestureControlManager.GestureListener,
    AutoPlayManager.AutoPlayListener {

    // UI 组件
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var downloadButton: Button

    // 预览容器
    private lateinit var imageContainer: FrameLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var textContainer: FrameLayout
    private lateinit var generalContainer: FrameLayout
    private lateinit var errorTextView: TextView

    // 图片预览组件
    private lateinit var imagePreview: ImageView
    private lateinit var imageLoadingProgress: ProgressBar

    // 视频预览组件
    private lateinit var playerView: PlayerView
    private lateinit var videoLoadingProgress: ProgressBar
    private lateinit var videoControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView
    private lateinit var speedIndicator: TextView

    // 歌词显示组件
    private lateinit var lyricsContainer: LinearLayout
    private lateinit var lyricsTitle: TextView
    private lateinit var currentLyricsLine: TextView
    private lateinit var nextLyricsLine: TextView
    private lateinit var lyricsSettingsButton: Button



    // 通用预览组件
    private lateinit var webViewPreview: WebView
    private lateinit var generalLoadingProgress: ProgressBar

    // 控制提示视图
    private lateinit var controlOverlay: TextView
    private lateinit var controlIcon: ImageView
    private lateinit var controlContainer: LinearLayout

    // 状态变量（添加以下变量）
    private var currentFileType = ""
    private var currentFileUrl = ""
    private var currentFileName = ""
    private var isLyricsVisible = false
    private var currentImageIndex = -1
    private var imageFileList = mutableListOf<FileSystemItem>()
    private var currentImageDirectoryPath = ""

    // 手势检测
    private lateinit var gestureDetector: GestureDetector
    private var isLongPressDetected = false

    private val doubleTapHandler = Handler(Looper.getMainLooper())

    // 网络客户端
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // 管理器实例
    private lateinit var lyricsManager: LyricsManager
    private lateinit var imageManager: ImagePreviewManager
    private lateinit var videoPlayerManager: VideoPlayerManager
    private lateinit var gestureControlManager: GestureControlManager
    private lateinit var fullscreenManager: FullscreenManager
    private lateinit var autoPlayManager: AutoPlayManager

    // 音频管理
    private lateinit var audioManager: AudioManager

    // 歌词对话框
    private var lyricsDialog: AlertDialog? = null

    // 服务器信息
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()
        initManagers()
        setupGestureDetector()
        setupIntentData()
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
        val currentMediaIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        // 获取当前文件类型
        currentFileType = intent.getStringExtra("FILE_TYPE") ?: "unknown"

        // 只在当前文件不是图片时才设置自动连播
        if (currentFileType != "image") {
            autoPlayManager.setupAutoPlay(
                autoPlayEnabled,
                mediaFileList,
                currentMediaIndex,
                currentServerUrl,
                currentDirectoryPath
            )
        } else {
            // 如果是图片，禁用自动连播
            autoPlayManager.setupAutoPlay(
                false,
                null,
                -1,
                currentServerUrl,
                currentDirectoryPath
            )
        }

        Log.d("PreviewActivity", "初始化完成: fileType=$currentFileType, autoPlayEnabled=${autoPlayManager.isAutoPlayEnabled()}")
    }

    private fun initViews() {
        // 初始化所有视图组件
        titleBar = findViewById(R.id.titleBar)
        backButton = findViewById(R.id.backButton)
        fileNameTextView = findViewById(R.id.fileNameTextView)
        fileTypeTextView = findViewById(R.id.fileTypeTextView)
        downloadButton = findViewById(R.id.downloadButton)

        imageContainer = findViewById(R.id.imageContainer)
        videoContainer = findViewById(R.id.videoContainer)
        textContainer = findViewById(R.id.textContainer)
        generalContainer = findViewById(R.id.generalContainer)
        errorTextView = findViewById(R.id.errorTextView)

        imagePreview = findViewById(R.id.imagePreview)
        imageLoadingProgress = findViewById(R.id.imageLoadingProgress)

        playerView = findViewById(R.id.playerView)
        videoLoadingProgress = findViewById(R.id.videoLoadingProgress)
        videoControls = findViewById(R.id.videoControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        fullscreenToggleButton = findViewById(R.id.fullscreenToggleButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)
        speedIndicator = findViewById(R.id.speedIndicator)

        lyricsContainer = findViewById(R.id.lyricsContainer)
        lyricsTitle = findViewById(R.id.lyricsTitle)
        currentLyricsLine = findViewById(R.id.currentLyricsLine)
        nextLyricsLine = findViewById(R.id.nextLyricsLine)
        lyricsSettingsButton = findViewById(R.id.lyricsSettingsButton)

        webViewPreview = findViewById(R.id.webViewPreview)
        generalLoadingProgress = findViewById(R.id.generalLoadingProgress)

        controlOverlay = findViewById(R.id.controlOverlay)
        controlIcon = findViewById(R.id.controlIcon)
        controlContainer = findViewById(R.id.controlContainer)

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

    private fun initManagers() {
        // 初始化歌词管理器
        lyricsManager = LyricsManager(this, handler, coroutineScope)
        lyricsManager.setListener(this)

        // 初始化图片管理器 - 简化版本，移除手势处理
        imageManager = ImagePreviewManager(
            context = this,
            coroutineScope = coroutineScope,
            imageView = imagePreview,
            loadingProgress = imageLoadingProgress,
            httpClient = client
        )
        imageManager.setListener(this)
        // 移除tapHandler设置

        // 初始化视频播放管理器
        videoPlayerManager = VideoPlayerManager(
            context = this,
            client = client,
            playerView = playerView,
            videoLoadingProgress = videoLoadingProgress,
            playPauseButton = playPauseButton,
            seekBar = seekBar,
            currentTimeTextView = currentTimeTextView,
            durationTextView = durationTextView,
            speedIndicator = speedIndicator,
            handler = handler
        )
        videoPlayerManager.setPlayerStateListener(this)
        videoPlayerManager.initializePlayer()

        // 初始化手势控制管理器
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val displayMetrics = resources.displayMetrics
        val regionWidth = displayMetrics.widthPixels / 3

        gestureControlManager = GestureControlManager(
            activity = this,
            handler = handler,
            audioManager = audioManager,
            controlOverlay = controlOverlay,
            controlIcon = controlIcon,
            controlContainer = controlContainer,
            regionWidth = regionWidth
        )
        gestureControlManager.setGestureListener(this)
        gestureControlManager.setupAudioManager()

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

    private fun setupSeekBar() {
        seekBar.max = 1000

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = videoPlayerManager.getDuration()
                    if (duration > 0) {
                        val newPosition = (duration * progress / 1000).toLong()
                        videoPlayerManager.seekTo(newPosition)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                videoPlayerManager.stopProgressUpdates()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                videoPlayerManager.startProgressUpdates()
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
                    handleDoubleTapVideo()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress()
            }
        })

        gestureDetector.setIsLongpressEnabled(true)

        // 只为视频播放器设置触摸监听
        playerView.setOnTouchListener { _, event ->
            val handledByGesture = gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressDetected = false
                    gestureControlManager.handleTouchEvent(event, resources.displayMetrics.widthPixels)
                }
                MotionEvent.ACTION_MOVE -> {
                    gestureControlManager.handleTouchEvent(event, resources.displayMetrics.widthPixels)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressDetected) {
                        videoPlayerManager.enableFastForward(false)
                        isLongPressDetected = false
                    }
                    gestureControlManager.handleTouchEvent(event, resources.displayMetrics.widthPixels)
                }
            }
            true
        }

        // 为图片容器添加左右滑动手势检测
        imagePreview.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private val SWIPE_THRESHOLD = 100f // 滑动阈值

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
            videoPlayerManager.togglePlayback()
        }
    }

    private fun handleDoubleTapVideo() {
        if (isLongPressDetected) {
            return
        }

        videoPlayerManager.togglePlayback()
    }

    private fun handleLongPress() {
        isLongPressDetected = true
        videoPlayerManager.enableFastForward(true)
    }

    private fun toggleControlsVisibility() {
        if (videoControls.visibility == View.VISIBLE) {
            videoControls.visibility = View.GONE
        } else {
            videoControls.visibility = View.VISIBLE
        }
    }

    private fun showControlsPersistent() {
        videoControls.visibility = View.VISIBLE
    }

    private fun setupIntentData() {
        // 从Intent获取文件信息
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFileType = intent.getStringExtra("FILE_TYPE") ?: "unknown"

        fileNameTextView.text = currentFileName
        fileTypeTextView.text = when (currentFileType) {
            "image" -> "图片"
            "video" -> "视频"
            "text" -> "文本"
            "audio" -> "音频"
            else -> "文件"
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
            videoPlayerManager.togglePlayback()
        }

        previousButton.setOnClickListener {
            if (autoPlayManager.isAutoPlayEnabled()) {
                autoPlayManager.playPreviousMedia()
            }
        }

        nextButton.setOnClickListener {
            if (autoPlayManager.isAutoPlayEnabled()) {
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
            "video" -> loadVideoPreview()
            "audio" -> loadAudioPreview()
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

    // 加载当前目录下的图片列表
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

    // 辅助方法：判断是否为图片文件
    private fun isImageFile(item: FileSystemItem): Boolean {
        val imageExtensions = listOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            "JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP"
        )
        return imageExtensions.any { item.extension.contains(it, true) }
    }

    // 辅助方法：获取图片完整URL
    private fun getFullImageUrl(item: FileSystemItem): String {
        val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
        return "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
    }

    // 辅助方法：获取当前图片的目录路径
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

    // 加载上一张图片
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

    // 加载下一张图片
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

    // 加载指定图片
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

    private fun loadVideoPreview() {
        showContainer(videoContainer)
        fileTypeTextView.visibility = View.VISIBLE

        playerView.visibility = View.VISIBLE
        videoControls.visibility = View.VISIBLE

        videoPlayerManager.loadVideo(currentFileUrl)
    }

    private fun loadAudioPreview() {
        showContainer(videoContainer)
        fileTypeTextView.visibility = View.VISIBLE

        playerView.visibility = View.GONE
        videoControls.visibility = View.VISIBLE

        // 重置歌词状态
        lyricsManager.clear()

        currentLyricsLine.text = "正在加载歌词..."
        nextLyricsLine.text = ""
        lyricsTitle.text = "歌词"

        // 显示歌词容器
        lyricsContainer.visibility = View.VISIBLE
        isLyricsVisible = true

        // 关键修复：立即加载歌词，不要等待播放器就绪
        handler.post {
            Log.d("PreviewActivity", "音频预览：立即加载歌词")
            loadLyricsForCurrentSong()
        }

        videoPlayerManager.loadAudio(currentFileUrl)
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
        videoContainer.visibility = View.GONE
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
        Log.d("PreviewActivity", "歌词加载成功: title=$title")
        lyricsManager.startLyricsUpdates()
        title?.let { lyricsTitle.text = it }
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
        return videoPlayerManager.getCurrentPosition()
    }

    // LyricsManager.PlayStateProvider
    override fun isPlaying(): Boolean {
        return videoPlayerManager.isPlayerPlaying()
    }

    // VideoPlayerManager.PlayerStateListener
    override fun onPlayerStateChanged(isPlaying: Boolean) {
        // 播放状态变化处理
    }

    override fun onPlaybackEnded() {
        if (fullscreenManager.isFullscreen()) {
            showControlsPersistent()
        }

        // 自动连播：播放结束后播放下一个
        if (autoPlayManager.isAutoPlayEnabled()) {
            handler.postDelayed({
                autoPlayManager.playNextMedia()
            }, 1000)
        }
    }

    override fun onPlayerError(error: String) {
        videoLoadingProgress.visibility = View.GONE
        showError(error)

        // 自动连播：播放错误时也尝试播放下一个
        if (autoPlayManager.isAutoPlayEnabled()) {
            handler.postDelayed({
                autoPlayManager.playNextMedia()
            }, 2000)
        }
    }

    override fun onBuffering(isBuffering: Boolean) {
        // 缓冲状态处理
    }

    override fun onDurationUpdated(duration: Long) {
        // 时长更新处理
    }

    override fun onPlayerReady() {
        // 如果是音频文件，再次确保歌词显示
        if (currentFileType == "audio") {
            Log.d("PreviewActivity", "播放器准备就绪，确保歌词显示")
            lyricsContainer.visibility = View.VISIBLE
            isLyricsVisible = true

            // 如果歌词还没加载，再次加载
            if (currentLyricsLine.text == "正在加载歌词...") {
                handler.post {
                    Log.d("PreviewActivity", "播放器就绪后重新加载歌词")
                    loadLyricsForCurrentSong()
                }
            }
        }
    }

    // GestureControlManager.GestureListener
    override fun onProgressControl(deltaX: Float, displayWidth: Int) {
        val duration = videoPlayerManager.getDuration()
        if (duration > 0) {
            val progressChange = (deltaX / displayWidth) * duration * gestureControlManager.progressSensitivity
            var newPosition = videoPlayerManager.getCurrentPosition() + progressChange.toLong()
            newPosition = newPosition.coerceIn(0, duration)

            videoPlayerManager.seekTo(newPosition)
        }
    }

    override fun onControlOverlayShow(text: String, iconRes: Int) {
        // 控制覆盖层显示
    }

    override fun onSeekBarProgressUpdate(position: Long, duration: Long) {
        if (duration > 0) {
            val progress = (position * 1000 / duration).toInt()
            seekBar.progress = progress
            currentTimeTextView.text = formatTime(position)
        }
    }

    // AutoPlayManager.AutoPlayListener
    override fun onLoadMediaFile(fileName: String, fileUrl: String, fileType: String, index: Int, filePath: String) {
        Log.d("PreviewActivity", "加载媒体文件: $fileName, 类型: $fileType, 索引: $index, 路径: $filePath")

        // 如果是图片类型，不执行自动连播逻辑
        if (fileType == "image") {
            Log.d("PreviewActivity", "图片类型，跳过自动连播逻辑")
            return
        }

        // 停止当前播放（只针对音视频）
        videoPlayerManager.stopProgressUpdates()
        videoPlayerManager.releasePlayer()

        // 重置歌词状态
        lyricsManager.clear()

        currentLyricsLine.text = "正在加载歌词..."
        nextLyricsLine.text = ""
        lyricsTitle.text = "歌词"

        // 更新当前文件信息
        currentFileName = fileName
        currentFileUrl = fileUrl
        currentFileType = fileType

        // 更新UI
        fileNameTextView.text = currentFileName
        fileTypeTextView.text = when (currentFileType) {
            "image" -> "图片"
            "video" -> "视频"
            "text" -> "文本"
            "audio" -> "音频"
            else -> "文件"
        }

        // 重新初始化播放器
        videoPlayerManager.initializePlayer()

        // 重新加载预览
        loadPreview()

        // 如果是音频文件，立即加载歌词
        if (currentFileType == "audio") {
            handler.postDelayed({
                Log.d("PreviewActivity", "自动连播延迟加载歌词")
                loadLyricsForCurrentSong()
            }, 500)
        }

        // 显示提示
        showToast("正在播放: $fileName (${index + 1}/${autoPlayManager.getMediaListSize()})")
    }

    override fun onAutoPlayError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ==================== 歌词相关方法 ====================

    private fun loadLyricsForCurrentSong() {
        val songPath = getCurrentSongPath()
        Log.d("PreviewActivity", "加载歌词: 歌曲路径=$songPath, 文件名=$currentFileName, 服务器=$currentServerUrl")
        lyricsManager.loadLyrics(currentServerUrl, songPath, currentFileName)
    }

    private fun getCurrentSongPath(): String {
        Log.d("PreviewActivity", "getCurrentSongPath调用: autoPlayEnabled=${autoPlayManager.isAutoPlayEnabled()}")

        // 方法1：尝试从自动连播管理器获取完整路径（新增的方法）
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
        videoPlayerManager.pause()
        videoPlayerManager.stopProgressUpdates()
        lyricsManager.stopLyricsUpdates()
        lyricsManager.clear()
        imageManager.clear()
        gestureControlManager.clear()
        isLongPressDetected = false
        doubleTapHandler.removeCallbacksAndMessages(null)
        lyricsDialog?.dismiss()
    }

    override fun onResume() {
        super.onResume()
        if (fullscreenManager.isFullscreen()) {
            fullscreenManager.enterFullscreen()
        }
        videoPlayerManager.startProgressUpdates()
        lyricsManager.startLyricsUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        videoPlayerManager.releasePlayer()
        lyricsManager.clear()
        imageManager.clear()
        gestureControlManager.clear()
        doubleTapHandler.removeCallbacksAndMessages(null)
        lyricsDialog?.dismiss()
    }

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

        super.onBackPressed()
    }
}