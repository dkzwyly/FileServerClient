package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.util.*

/**
 * 基于官方 Media3/ExoPlayer 的视频播放器
 * 直接持有 ExoPlayer，无任何自定义封装层
 * 纯净版：无录音权限请求，无频谱，无多余功能
 */
class VideoPlayerActivityV2 : AppCompatActivity(), Player.Listener {

    // ==================== UI 组件 ====================
    private lateinit var playerView: PlayerView
    private lateinit var mediaLoadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var downloadButton: Button
    private lateinit var subtitleButton: ImageButton
    private lateinit var mediaControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView

    // 手势控制覆盖层
    private lateinit var controlOverlay: TextView
    private lateinit var controlIcon: ImageView
    private lateinit var controlContainer: LinearLayout

    // ==================== 核心组件 ====================
    private var exoPlayer: ExoPlayer? = null
    private lateinit var fullscreenManager: FullscreenManager
    private lateinit var gestureControlManager: GestureControlManager
    private lateinit var autoPlayManager: AutoPlayManager

    private val fileServerService by lazy { FileServerService(this) }
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()

    // ==================== 播放状态 ====================
    private var currentFileUrl = ""
    private var currentFileName = ""
    private var currentFilePath = ""
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""
    private var currentVideoIndex = -1
    private var currentSubtitlePath: String? = null
    private var isAppInBackground = false

    // ==================== 手势相关 ====================
    private lateinit var gestureDetector: GestureDetector
    private var isLongPressDetected = false
    private var originalSpeed = 1.0f

    // ==================== 进度更新 ====================
    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            val position = player.currentPosition
            val duration = player.duration
            if (duration > 0) {
                seekBar.progress = (position * 1000 / duration).toInt()
                currentTimeTextView.text = formatTime(position)
                durationTextView.text = formatTime(duration)
            }
            handler.postDelayed(this, 1000)
        }
    }

    // ==================== 生命周期 ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        // 注册返回事件处理器（兼容手势返回和按钮返回）
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 如果全屏模式，先退出全屏
                    if (fullscreenManager.onBackPressed()) return

                    // 退出时通知自动连播状态（如有需要）
                    val resultIntent = Intent().apply {
                        if (autoPlayManager.isAutoPlayEnabled()) putExtra("ACTION", "EXIT_AUTO_PLAY")
                    }
                    setResult(RESULT_OK, resultIntent)
                    // 执行真正的返回操作
                    finish()
                }
            }
        )

        initViews()
        setupIntentData()
        initManagers()
        setupGestureControlManager()
        setupGestureDetector()
        setupEventListeners()
        initializePlayer()
        loadVideo()
    }

    override fun onResume() {
        super.onResume()
        if (fullscreenManager.isFullscreen()) fullscreenManager.enterFullscreen()
        handler.post(progressUpdater)
        if (isAppInBackground) {
            exoPlayer?.playWhenReady = true
            isAppInBackground = false
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(progressUpdater)
        exoPlayer?.pause()
        isAppInBackground = true
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
        coroutineScope.cancel()
    }

    // ==================== 初始化 ====================
    private fun initViews() {
        playerView = findViewById(R.id.playerView)
        mediaLoadingProgress = findViewById(R.id.mediaLoadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        titleBar = findViewById(R.id.titleBar)
        backButton = findViewById(R.id.backButton)
        fileNameTextView = findViewById(R.id.fileNameTextView)
        fileTypeTextView = findViewById(R.id.fileTypeTextView)
        downloadButton = findViewById(R.id.downloadButton)
        subtitleButton = findViewById(R.id.subtitleButton)
        mediaControls = findViewById(R.id.mediaControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        fullscreenToggleButton = findViewById(R.id.fullscreenToggleButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)

        playerView.useController = false
        playerView.setBackgroundColor(Color.BLACK)

        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = exoPlayer?.duration ?: 0
                    if (duration > 0) {
                        val newPosition = (duration * progress / 1000).toLong()
                        exoPlayer?.seekTo(newPosition)
                        currentTimeTextView.text = formatTime(newPosition)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知视频"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFilePath = intent.getStringExtra("FILE_PATH") ?: ""
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""
        currentVideoIndex = intent.getIntExtra("CURRENT_INDEX", -1)

        fileNameTextView.text = currentFileName
        fileTypeTextView.text = "视频"
    }

    private fun initManagers() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        fullscreenManager = FullscreenManager(
            activity = this,
            titleBar = titleBar,
            fileTypeTextView = fileTypeTextView,
            fullscreenToggleButton = fullscreenToggleButton
        )

        autoPlayManager = AutoPlayManager(handler, coroutineScope)
        autoPlayManager.setAutoPlayListener(object : AutoPlayManager.AutoPlayListener {
            override fun onLoadMediaFile(fileName: String, fileUrl: String, fileType: String, index: Int, filePath: String) {
                currentFileName = fileName
                currentFileUrl = fileUrl
                currentFilePath = filePath
                fileNameTextView.text = currentFileName
                loadVideo()
            }
            override fun onLoadAudioTrack(track: AudioTrack, index: Int) {}
            override fun onAutoPlayError(message: String) {
                Toast.makeText(this@VideoPlayerActivityV2, message, Toast.LENGTH_SHORT).show()
            }
        })

        controlOverlay = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }
        controlIcon = ImageView(this)
        controlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(controlIcon, LinearLayout.LayoutParams(48, 48).apply { gravity = Gravity.CENTER })
            addView(controlOverlay, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        findViewById<FrameLayout>(R.id.mediaContainer).addView(controlContainer)
    }

    private fun setupGestureControlManager() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
                val duration = exoPlayer?.duration ?: 0
                if (duration > 0) {
                    val deltaProgress = (deltaX / displayWidth) * duration * 0.5f
                    val currentPosition = exoPlayer?.currentPosition ?: 0
                    var newPosition = currentPosition + deltaProgress.toLong()
                    newPosition = newPosition.coerceIn(0, duration)
                    exoPlayer?.seekTo(newPosition)

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

        gestureControlManager.setupAudioManager()
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControlsVisibility()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                handleLongPress()
            }
        })

        val mediaContainer = findViewById<FrameLayout>(R.id.mediaContainer)
        mediaContainer.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            gestureControlManager.handleTouchEvent(event, view.width)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressDetected = false
                    gestureControlManager.setupAudioManager()
                    originalSpeed = exoPlayer?.playbackParameters?.speed ?: 1.0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressDetected) {
                        exoPlayer?.setPlaybackSpeed(originalSpeed)
                        isLongPressDetected = false
                    }
                }
            }
            true
        }
    }

    private fun handleLongPress() {
        isLongPressDetected = true
        val currentSpeed = exoPlayer?.playbackParameters?.speed ?: 1.0f
        if (currentSpeed < 2.0f) {
            exoPlayer?.setPlaybackSpeed(2.0f)
            Toast.makeText(this, "2x 倍速", Toast.LENGTH_SHORT).show()
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
        // 返回按钮触发统一的返回事件处理器
        backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        downloadButton.setOnClickListener { downloadFile() }
        subtitleButton.setOnClickListener { showSubtitleSelectionMenu() }
        playPauseButton.setOnClickListener {
            exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        previousButton.setOnClickListener { autoPlayManager.playPreviousMedia() }
        nextButton.setOnClickListener { autoPlayManager.playNextMedia() }
        fullscreenToggleButton.setOnClickListener {
            if (fullscreenManager.isFullscreen()) fullscreenManager.exitFullscreen()
            else fullscreenManager.enterFullscreen()
        }
    }

    // ==================== ExoPlayer 初始化 ====================
    private fun initializePlayer() {
        val httpDataSourceFactory = OkHttpDataSource.Factory(client)
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(defaultDataSourceFactory))
            .build()
            .apply {
                playerView.player = this
                addListener(this@VideoPlayerActivityV2)
            }
        playerView.subtitleView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    // ==================== 加载视频 ====================
    private fun loadVideo() {
        if (currentFileUrl.isEmpty()) {
            showError("无效的视频地址")
            return
        }

        errorTextView.visibility = View.GONE
        mediaLoadingProgress.visibility = View.VISIBLE

        val mediaItem = MediaItem.fromUri(currentFileUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true

        val autoPlayEnabled = intent.getBooleanExtra("AUTO_PLAY_ENABLED", false)
        val mediaFileList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST", FileSystemItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST")
        }
        if (autoPlayEnabled && mediaFileList != null) {
            autoPlayManager.setupAutoPlay(
                enabled = true,
                fileList = mediaFileList,
                audioTracks = null,
                currentIndex = currentVideoIndex,
                serverUrl = currentServerUrl,
                directoryPath = currentDirectoryPath
            )
        }

        findAndLoadMatchingSubtitle()
    }

    private fun showError(message: String) {
        mediaLoadingProgress.visibility = View.GONE
        errorTextView.text = message
        errorTextView.visibility = View.VISIBLE
    }

    private fun downloadFile() {
        Toast.makeText(this, "开始下载: $currentFileName", Toast.LENGTH_SHORT).show()
    }

    // ==================== 字幕功能 ====================
    private fun isSubtitleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("srt", "vtt", "ass", "ssa")
    }

    private fun findAndLoadMatchingSubtitle() {
        val directory = currentFilePath.substringBeforeLast('/')
        val baseName = currentFilePath.substringAfterLast('/').substringBeforeLast('.')
        if (directory.isEmpty() || baseName.isEmpty()) return

        coroutineScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, directory)
                }
                val matching = items.firstOrNull { item ->
                    !item.isDirectory && isSubtitleFile(item.name) &&
                            item.name.substringBeforeLast('.') == baseName
                }
                matching?.let {
                    loadSubtitle(it.path)
                }
            } catch (_: Exception) {
                // 静默失败，不干扰播放
            }
        }
    }

    private fun showSubtitleSelectionMenu() {
        val directory = currentFilePath.substringBeforeLast('/')
        if (directory.isEmpty()) {
            Toast.makeText(this, "无法获取视频目录", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, directory)
                }
                val subtitleFiles = items.filter { item ->
                    !item.isDirectory && isSubtitleFile(item.name)
                }

                if (subtitleFiles.isEmpty()) {
                    Toast.makeText(this@VideoPlayerActivityV2, "该目录没有字幕文件", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val names = subtitleFiles.map { it.name }.toTypedArray()
                AlertDialog.Builder(this@VideoPlayerActivityV2)
                    .setTitle("选择字幕")
                    .setItems(names) { _, which ->
                        val selected = subtitleFiles[which]
                        loadSubtitle(selected.path)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@VideoPlayerActivityV2, "获取字幕列表失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSubtitle(subtitlePath: String) {
        val player = exoPlayer ?: return
        val currentMediaItem = player.currentMediaItem ?: return
        val currentPosition = player.currentPosition
        val videoUri = currentMediaItem.localConfiguration?.uri ?: return

        val encodedPath = URLEncoder.encode(subtitlePath, "UTF-8")
        val subtitleUri = Uri.parse("${currentServerUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath")

        val mimeType = when (subtitlePath.substringAfterLast('.').lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> "text/x-ssa"
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        // 1. 构建字幕配置（不再禁用样式）
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(mimeType)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        // 2. 构建新的 MediaItem
        val newMediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(listOf(subtitleConfig))
            .build()

        // 3. 替换播放项（保持当前进度）
        player.setMediaItem(newMediaItem, currentPosition)
        player.prepare()
        player.playWhenReady = true

        // 4. ★★★ 关键修改：不禁用 ASS 样式，只覆盖背景色 ★★★
        playerView.subtitleView?.apply {
            // 删除或注释掉 setApplyEmbeddedStyles(false)  ← 这行必须移除

            // 创建自定义样式：仅背景透明，其余保留 ASS 定义
            val customStyle = CaptionStyleCompat(
                Color.WHITE,                        // 文字颜色（ASS 会覆盖此值）
                Color.TRANSPARENT,                  // 背景颜色（完全透明）
                Color.TRANSPARENT,                  // 窗口颜色
                CaptionStyleCompat.EDGE_TYPE_NONE,  // 边缘类型（ASS 会覆盖）
                Color.BLACK,                        // 边缘颜色
                null                                // 字体（ASS 会覆盖）
            )
            setStyle(customStyle)  // 应用样式
        }

        currentSubtitlePath = subtitlePath
        Toast.makeText(this, "字幕已加载", Toast.LENGTH_SHORT).show()
    }

    // ==================== Player.Listener 实现 ====================
    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                mediaLoadingProgress.visibility = View.VISIBLE
            }
            Player.STATE_READY -> {
                mediaLoadingProgress.visibility = View.GONE
                updatePlayPauseButton()
            }
            Player.STATE_ENDED -> {
                mediaLoadingProgress.visibility = View.GONE
                if (autoPlayManager.isAutoPlayEnabled()) {
                    handler.postDelayed({ autoPlayManager.playNextMedia() }, 1000)
                }
            }
            Player.STATE_IDLE -> {
                mediaLoadingProgress.visibility = View.GONE
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updatePlayPauseButton()
    }

    override fun onPlayerError(error: PlaybackException) {
        mediaLoadingProgress.visibility = View.GONE
        showError("播放错误: ${error.message}")
    }

    private fun updatePlayPauseButton() {
        val isPlaying = exoPlayer?.isPlaying ?: false
        playPauseButton.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    // ==================== 工具 ====================
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
}