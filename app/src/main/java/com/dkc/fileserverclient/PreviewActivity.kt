package com.dkc.fileserverclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.util.*

@UnstableApi
class PreviewActivity : AppCompatActivity(),
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

    private lateinit var mediaContainer: FrameLayout
    private lateinit var generalContainer: FrameLayout
    private lateinit var errorTextView: TextView

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

    // 视频相关变量
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

        // 获取自动连播相关参数（仅用于视频）
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

        // 视频自动连播设置
        if (currentFileType == "video") {
            autoPlayManager.setupAutoPlay(
                enabled = autoPlayEnabled,
                fileList = mediaFileList,
                audioTracks = null,
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

        mediaContainer = findViewById(R.id.mediaContainer)
        generalContainer = findViewById(R.id.generalContainer)
        errorTextView = findViewById(R.id.errorTextView)

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
            "video" -> "视频"
            else -> "文件"
        }
    }

    private fun initManagers() {
        // 视频播放控制器
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
            gestureDetector.onTouchEvent(event)
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
            "image", "audio", "text" -> {
                showError("不支持在此界面预览${when (currentFileType) {
                    "image" -> "图片"
                    "audio" -> "音频"
                    else -> "文本"
                }}，请从文件列表直接打开")
            }
            "video" -> loadVideoPreview()
            else -> loadGeneralPreview()
        }
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

    private fun loadGeneralPreview() {
        showContainer(generalContainer)
        fileTypeTextView.visibility = View.VISIBLE
        webViewPreview.loadUrl(currentFileUrl)
    }

    private fun showContainer(container: View) {
        mediaContainer.visibility = View.GONE
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
            "video" -> "视频"
            else -> "文件"
        }
        loadPreview()
    }

    override fun onLoadAudioTrack(track: AudioTrack, index: Int) {
        // 空实现，音频逻辑已完全移除
    }

    override fun onAutoPlayError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
            mediaPlaybackController.release(false)
        }
        coroutineScope.cancel()
        handler.removeCallbacksAndMessages(null)
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