package com.dkc.fileserverclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.load
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.*
import java.io.File
import java.util.*

@UnstableApi
class PreviewActivity : AppCompatActivity(),
    ImagePreviewManager.ImageStateListener,
    AutoPlayManager.AutoPlayListener,
    MediaPlaybackListener,
    MediaProgressListener {

    // 权限请求码
    private val PERMISSION_REQUEST_RECORD_AUDIO = 100

    // UI 组件
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var downloadButton: Button

    private lateinit var imageContainer: FrameLayout
    private lateinit var mediaContainer: FrameLayout
    private lateinit var textContainer: FrameLayout
    private lateinit var generalContainer: FrameLayout
    private lateinit var errorTextView: TextView

    // 图片预览组件
    private lateinit var imagePreview: ImageView
    private lateinit var imageLoadingProgress: ProgressBar

    // 视频播放组件
    private lateinit var playerView: PlayerView
    private lateinit var mediaLoadingProgress: ProgressBar
    private lateinit var mediaControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView

    // 通用预览组件
    private lateinit var webViewPreview: WebView
    private lateinit var generalLoadingProgress: ProgressBar

    // 状态变量
    private var currentFileType = ""
    private var currentFileUrl = ""
    private var currentFileName = ""
    private var currentImageIndex = -1
    private var imageFileList = mutableListOf<FileSystemItem>()
    private var currentImageDirectoryPath = ""

    // 视频相关变量
    private var videoFileList: ArrayList<FileSystemItem>? = null
    private var currentVideoIndex = -1

    // 手势检测
    private lateinit var gestureDetector: GestureDetector
    private var isLongPressDetected = false
    private var originalSpeed: Float = 1.0f

    // 应用状态标志
    private var isAppInBackground = false

    // 网络客户端和协程
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // 管理器实例
    private lateinit var imageManager: ImagePreviewManager
    private lateinit var fullscreenManager: FullscreenManager
    private lateinit var autoPlayManager: AutoPlayManager

    // 视频播放控制器
    private lateinit var mediaPlaybackController: MediaPlaybackController

    // 服务器信息
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""

    // 手势控制管理器
    private lateinit var gestureControlManager: GestureControlManager

    // 手势控制UI组件
    private lateinit var controlOverlay: TextView
    private lateinit var controlIcon: ImageView
    private lateinit var controlContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        checkAndRequestRecordAudioPermission()
        initViews()
        setupIntentData()
        initManagers()
        setupGestureControlManager()
        setupGestureDetector()
        setupEventListeners()
        loadPreview()

        // 获取自动连播相关参数（仅用于非音频文件）
        val autoPlayEnabled = intent.getBooleanExtra("AUTO_PLAY_ENABLED", false)
        val mediaFileList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST", FileSystemItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST")
        }

        currentVideoIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        currentFileType = intent.getStringExtra("FILE_TYPE") ?: "unknown"

        // 图片和视频自动连播设置（音频已分离）
        if (currentFileType != "image") {
            autoPlayManager.setupAutoPlay(
                enabled = autoPlayEnabled,
                fileList = mediaFileList,
                audioTracks = null,  // 不再使用音频列表
                currentIndex = currentVideoIndex,
                serverUrl = currentServerUrl,
                directoryPath = currentDirectoryPath
            )
        }
    }

    private fun initViews() {
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
        mediaLoadingProgress = findViewById(R.id.mediaLoadingProgress)
        mediaControls = findViewById(R.id.mediaControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        fullscreenToggleButton = findViewById(R.id.fullscreenToggleButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)

        webViewPreview = findViewById(R.id.webViewPreview)
        generalLoadingProgress = findViewById(R.id.generalLoadingProgress)

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

    private fun setupGestureControlManager() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        controlOverlay = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        controlIcon = ImageView(this)

        controlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE

            val iconParams = LinearLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.CENTER
                setMargins(0, 0, 0, 8)
            }
            val textParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }

            addView(controlIcon, iconParams)
            addView(controlOverlay, textParams)
        }

        mediaContainer.addView(controlContainer)

        val displayWidth = resources.displayMetrics.widthPixels
        val regionWidth = displayWidth / 3

        gestureControlManager = GestureControlManager(
            activity = this,
            handler = handler,
            audioManager = audioManager,
            controlOverlay = controlOverlay,
            controlIcon = controlIcon,
            controlContainer = controlContainer,
            regionWidth = regionWidth
        )

        gestureControlManager.setGestureListener(object : GestureControlManager.GestureListener {
            override fun onProgressControl(deltaX: Float, displayWidth: Int) {
                val duration = mediaPlaybackController.getDuration()
                if (duration > 0) {
                    val deltaProgress = (deltaX / displayWidth) * duration * 0.5f
                    val currentPosition = mediaPlaybackController.getCurrentPosition()
                    var newPosition = currentPosition + deltaProgress.toLong()
                    if (newPosition < 0) newPosition = 0
                    if (newPosition > duration) newPosition = duration
                    mediaPlaybackController.seekTo(newPosition)

                    gestureControlManager.showControlOverlay(
                        "进度: ${formatTime(newPosition)} / ${formatTime(duration)}",
                        android.R.drawable.ic_media_play
                    )

                    val progress = if (duration > 0) (newPosition * 1000 / duration).toInt() else 0
                    seekBar.progress = progress
                    currentTimeTextView.text = formatTime(newPosition)
                    durationTextView.text = formatTime(duration)
                }
            }

            override fun onControlOverlayShow(text: String, iconRes: Int) {
                controlContainer.bringToFront()
            }

            override fun onSeekBarProgressUpdate(position: Long, duration: Long) {
                seekBar.progress = (position * 1000 / duration).toInt()
                currentTimeTextView.text = formatTime(position)
                durationTextView.text = formatTime(duration)
            }
        })
    }

    private fun checkAndRequestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_RECORD_AUDIO)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 仅用于视频，若权限被拒绝无影响
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFileType = intent.getStringExtra("FILE_TYPE") ?: "unknown"
        currentVideoIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        fileNameTextView.text = currentFileName
        fileTypeTextView.text = when (currentFileType) {
            "image" -> "图片"
            "video" -> "视频"
            "audio" -> "音频"  // 但实际会跳转
            "text" -> "文本"
            else -> "文件"
        }
    }

    private fun initManagers() {
        imageManager = ImagePreviewManager(
            context = this,
            coroutineScope = coroutineScope,
            imageView = imagePreview,
            loadingProgress = imageLoadingProgress,
            httpClient = client
        )
        imageManager.setListener(this)

        // 只创建视频播放控制器
        mediaPlaybackController = MediaPlaybackFactory.createController(
            type = PlaybackType.VIDEO,
            httpClient = client,
            playerView = playerView,
            videoLoadingProgress = mediaLoadingProgress,
            playPauseButton = playPauseButton,
            seekBar = seekBar,
            currentTimeTextView = currentTimeTextView,
            durationTextView = durationTextView,
            uiHandler = handler
        )
        mediaPlaybackController.initialize(this, handler)
        mediaPlaybackController.addPlaybackListener(this)
        mediaPlaybackController.addProgressListener(this)

        fullscreenManager = FullscreenManager(
            activity = this,
            titleBar = titleBar,
            fileTypeTextView = fileTypeTextView,
            fullscreenToggleButton = fullscreenToggleButton
        )

        autoPlayManager = AutoPlayManager(handler, coroutineScope)
        autoPlayManager.setAutoPlayListener(this)

        setupSeekBar()
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
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (currentFileType == "video") {
                    toggleControlsVisibility()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentFileType == "video") {
                    mediaPlaybackController.togglePlayback()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                handleLongPress()
            }
        })

        mediaContainer.setOnTouchListener { view, event ->
            val handledByGesture = gestureDetector.onTouchEvent(event)
            gestureControlManager.handleTouchEvent(event, view.width)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressDetected = false
                    gestureControlManager.setupAudioManager()
                    originalSpeed = mediaPlaybackController.getPlaybackSpeed()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressDetected) {
                        mediaPlaybackController.setPlaybackSpeed(originalSpeed)
                        isLongPressDetected = false
                    }
                }
            }
            true
        }

        // 图片滑动切换
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
                        if (Math.abs(diffX) > SWIPE_THRESHOLD) {
                            if (diffX > 0) {
                                loadPreviousImage()
                            } else {
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

    private fun handleLongPress() {
        isLongPressDetected = true
        if (currentFileType == "video") {
            val currentSpeed = mediaPlaybackController.getPlaybackSpeed()
            if (currentSpeed < 2.0f) {
                mediaPlaybackController.setPlaybackSpeed(2.0f)
            }
        }
    }

    private fun toggleControlsVisibility() {
        if (mediaControls.visibility == View.VISIBLE) {
            mediaControls.visibility = View.GONE
            controlContainer.visibility = View.GONE
        } else {
            mediaControls.visibility = View.VISIBLE
            mediaControls.bringToFront()
        }
    }

    private fun setupEventListeners() {
        backButton.setOnClickListener { onBackPressed() }
        downloadButton.setOnClickListener { downloadFile() }
        playPauseButton.setOnClickListener { mediaPlaybackController.togglePlayback() }

        previousButton.setOnClickListener {
            autoPlayManager.playPreviousMedia()
        }
        nextButton.setOnClickListener {
            autoPlayManager.playNextMedia()
        }

        fullscreenToggleButton.setOnClickListener { toggleFullscreen() }
    }

    private fun toggleFullscreen() {
        if (fullscreenManager.isFullscreen()) {
            fullscreenManager.exitFullscreen()
        } else {
            fullscreenManager.enterFullscreen()
        }
    }

    private fun loadPreview() {
        when (currentFileType) {
            "image" -> loadImagePreview()
            "audio" -> {
                // 音频跳转至独立界面
                val intent = Intent(this, AudioPlayerActivity::class.java).apply {
                    putExtra("FILE_NAME", currentFileName)
                    putExtra("FILE_URL", currentFileUrl)
                    putExtra("FILE_TYPE", "audio")
                    putExtra("FILE_PATH", intent.getStringExtra("FILE_PATH"))
                    // 音频轨道数据若存在则传递
                    val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("AUDIO_TRACK", AudioTrack::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra("AUDIO_TRACK")
                    }
                    putExtra("AUDIO_TRACK", audioTrack)
                    val audioTracks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra("AUDIO_TRACKS", AudioTrack::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra("AUDIO_TRACKS")
                    }
                    putExtra("AUDIO_TRACKS", audioTracks)
                    putExtra("CURRENT_INDEX", intent.getIntExtra("CURRENT_INDEX", 0))
                    putExtra("SERVER_URL", currentServerUrl)
                }
                startActivity(intent)
                finish()
            }
            "video" -> loadVideoPreview()
            "text" -> loadTextPreview()
            else -> loadGeneralPreview()
        }
    }

    private fun loadImagePreview() {
        val intent = Intent(this, ImageActivity::class.java).apply {
            putExtra("FILE_NAME", currentFileName)
            putExtra("FILE_URL", currentFileUrl)
            putExtra("FILE_TYPE", "image")
            putExtra("FILE_PATH", intent.getStringExtra("FILE_PATH"))
            putExtra("SERVER_URL", currentServerUrl)
            putExtra("CURRENT_PATH", currentDirectoryPath)
        }
        startActivity(intent)
        finish()
    }

    private fun loadVideoPreview() {
        showContainer(mediaContainer)
        fileTypeTextView.visibility = View.VISIBLE
        mediaControls.visibility = View.VISIBLE

        playerView.visibility = View.VISIBLE

        val mediaItem = MediaPlaybackItem(
            id = "video_${System.currentTimeMillis()}",
            name = currentFileName,
            url = currentFileUrl,
            path = currentDirectoryPath,
            type = PlaybackType.VIDEO,
            duration = 0L,
            metadata = emptyMap()
        )

        mediaPlaybackController.play(currentFileUrl, mediaItem)
    }

    private fun loadTextPreview() {
        val intent = Intent(this, TextPreviewActivity::class.java).apply {
            putExtra("FILE_NAME", currentFileName)
            putExtra("FILE_URL", currentFileUrl)
            putExtra("FILE_PATH", intent.getStringExtra("FILE_PATH"))
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
        imageContainer.visibility = View.GONE
        mediaContainer.visibility = View.GONE
        textContainer.visibility = View.GONE
        generalContainer.visibility = View.GONE
        errorTextView.visibility = View.GONE
        container.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        showContainer(errorTextView)
        errorTextView.text = message
    }

    private fun downloadFile() {
        Toast.makeText(this, "开始下载: $currentFileName", Toast.LENGTH_SHORT).show()
    }

    // AutoPlayManager.AutoPlayListener
    override fun onLoadMediaFile(fileName: String, fileUrl: String, fileType: String, index: Int, filePath: String) {
        mediaPlaybackController.stop()
        currentFileName = fileName
        currentFileUrl = fileUrl
        currentFileType = fileType
        fileNameTextView.text = currentFileName
        fileTypeTextView.text = when (currentFileType) {
            "image" -> "图片"
            "video" -> "视频"
            else -> "文件"
        }
        loadPreview()
    }

    override fun onLoadAudioTrack(track: AudioTrack, index: Int) {
        // 不再使用
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
        // 图片双击事件
    }

    // MediaPlaybackListener
    override fun onPlaybackStateChanged(status: MediaPlaybackStatus) {
        handler.post {
            playPauseButton.setImageResource(
                if (status.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            if (status.duration > 0) {
                durationTextView.text = formatTime(status.duration)
                val progress = (status.position * 1000 / status.duration).toInt()
                seekBar.progress = progress
                currentTimeTextView.text = formatTime(status.position)
            }

            mediaLoadingProgress.visibility = if (status.state == PlaybackState.BUFFERING) View.VISIBLE else View.GONE
        }
    }

    override fun onTrackChanged(item: MediaPlaybackItem, index: Int) {
        // 视频切换处理
        currentFileName = item.name
        currentFileUrl = item.url
        handler.post {
            fileNameTextView.text = item.name
        }
    }

    override fun onPlaybackError(error: String) {
        showError(error)
    }

    override fun onPlaybackEnded() {
        if (currentFileType == "video" && autoPlayManager.isAutoPlayEnabled()) {
            handler.postDelayed({
                autoPlayManager.playNextMedia()
            }, 1000)
        }
    }

    override fun onMediaBuffering(isBuffering: Boolean) {
        mediaLoadingProgress.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    // MediaProgressListener
    override fun onProgressUpdated(position: Long, duration: Long) {
        if (duration > 0 && !isAppInBackground) {
            handler.post {
                seekBar.progress = (position * 1000 / duration).toInt()
                currentTimeTextView.text = formatTime(position)
                durationTextView.text = formatTime(duration)
            }
        }
    }

    override fun onBufferingProgress(percent: Int) {
        // 缓冲进度
    }
// ========== 图片左右滑动相关方法 ==========

    private fun loadPreviousImage() {
        if (imageFileList.isEmpty() || currentImageIndex <= 0) {
            Log.d("PreviewActivity", "已经是第一张图片")
            return
        }
        val prevIndex = currentImageIndex - 1
        if (prevIndex in 0 until imageFileList.size) {
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
        if (nextIndex in 0 until imageFileList.size) {
            val nextItem = imageFileList[nextIndex]
            loadImageByItem(nextItem, nextIndex)
        }
    }

    private fun loadImageByItem(item: FileSystemItem, index: Int) {
        try {
            val imageUrl = buildImageUrl(item)
            currentFileName = item.name
            currentFileUrl = imageUrl
            currentImageIndex = index

            fileNameTextView.text = currentFileName
            imageLoadingProgress.visibility = View.VISIBLE
            imageManager.loadImage(imageUrl, currentFileName)
        } catch (e: Exception) {
            Log.e("PreviewActivity", "切换图片失败", e)
        }
    }

    private fun buildImageUrl(item: FileSystemItem): String {
        val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
        return "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
    }

    private fun getImageDirectoryPath(): String {
        // 优先从 Intent 的 FILE_PATH 提取父目录
        val filePath = intent.getStringExtra("FILE_PATH")
        if (!filePath.isNullOrEmpty()) {
            val parent = File(filePath).parent
            if (!parent.isNullOrEmpty()) return parent
        }
        // 其次使用 currentDirectoryPath
        if (currentDirectoryPath.isNotEmpty()) return currentDirectoryPath
        return ""
    }

    private fun isImageFile(item: FileSystemItem): Boolean {
        val imageExtensions = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP")
        return imageExtensions.any { item.name.endsWith(it) }
    }

    // 在加载图片预览时（如果还没调用），需要事先加载图片列表
    private fun loadImageFileListIfNeeded() {
        if (imageFileList.isNotEmpty()) return
        coroutineScope.launch {
            try {
                val dirPath = getImageDirectoryPath()
                if (dirPath.isEmpty()) return@launch
                val allFiles = withContext(Dispatchers.IO) {
                    FileServerService(this@PreviewActivity).getFileList(currentServerUrl, dirPath)
                }
                imageFileList.clear()
                imageFileList.addAll(allFiles.filter { !it.isDirectory && isImageFile(it) })
                currentImageIndex = imageFileList.indexOfFirst { buildImageUrl(it) == currentFileUrl }
                if (currentImageIndex == -1) {
                    currentImageIndex = imageFileList.indexOfFirst { it.name == currentFileName }
                }
                Log.d("PreviewActivity", "图片列表加载: ${imageFileList.size}张, 当前索引$currentImageIndex")
            } catch (e: Exception) {
                Log.e("PreviewActivity", "加载图片列表失败", e)
            }
        }
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
        if (currentFileType == "video") {
            mediaPlaybackController.onActivityPause()
        }
        handler.removeCallbacksAndMessages(null)
        isAppInBackground = true
    }

    override fun onResume() {
        super.onResume()
        if (fullscreenManager.isFullscreen()) {
            fullscreenManager.enterFullscreen()
        }
        if (currentFileType == "video") {
            mediaPlaybackController.onActivityResume()
        }
        isAppInBackground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        gestureControlManager.clear()
        if (currentFileType == "video") {
            mediaPlaybackController.release(false)  // 视频不需要后台
        }
        coroutineScope.cancel()
        handler.removeCallbacksAndMessages(null)
        imageManager.clear()
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