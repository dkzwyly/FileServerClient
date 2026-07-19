package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.SpannedString
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.*

class VideoPlayerActivityV2 : AppCompatActivity(), Player.Listener {

    // ==================== UI 组件 ====================
    private lateinit var playerView: PlayerView
    private lateinit var mediaLoadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var settingsButton: Button
    private lateinit var subtitleButton: ImageButton
    private lateinit var mediaControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView

    // 自定义 ASS 字幕视图
    private lateinit var customSubtitleView: TextView

    // 手势控制覆盖层
    private lateinit var controlOverlay: TextView
    private lateinit var controlIcon: ImageView
    private lateinit var controlContainer: LinearLayout

    // ==================== 核心组件 ====================
    private var exoPlayer: ExoPlayer? = null
    private lateinit var fullscreenManager: FullscreenManager
    private lateinit var gestureControlManager: GestureControlManager

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

    // ==================== 连播管理 ====================
    private var mediaFileList: List<FileSystemItem>? = null
    private var currentPlayIndex: Int = -1
    private var autoPlayEnabled: Boolean = false

    // ==================== ASS 字幕数据 ====================
    private var assStyles: Map<String, AssStyle> = emptyMap()
    private var assDialogues: List<AssDialogue> = emptyList()
    private var lastDisplayedDialogues: List<AssDialogue> = emptyList()

    // ==================== 字幕样式设置 ====================
    private var subtitleTextSize = 16f          // 自定义 ASS 字幕的绝对 SP 大小
    private var subtitleFraction = 1.0f         // 内嵌字幕的缩放比例（基准 16sp → 1.0）
    private var subtitleTextColor = Color.WHITE
    private lateinit var sharedPrefs: SharedPreferences
    private var controlBarHeight = 0

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
            updateAssSubtitle(position)
            handler.postDelayed(this, 100)
        }
    }

    // ==================== 生命周期 ====================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenManager.onBackPressed()) return
                setResult(RESULT_OK)
                finish()
            }
        })

        initViews()
        setupIntentData()
        initManagers()
        setupGestureControlManager()
        setupGestureDetector()
        setupEventListeners()
        initializePlayer()
        addCustomSubtitleView()
        loadSubtitlePreferences()
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
        settingsButton = findViewById(R.id.settingsButton)
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

    private fun addCustomSubtitleView() {
        customSubtitleView = TextView(this).apply {
            id = View.generateViewId()
            setTextColor(subtitleTextColor)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            visibility = View.GONE
            textSize = subtitleTextSize
        }
        val container = findViewById<FrameLayout>(R.id.mediaContainer)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        container.addView(customSubtitleView, params)

        // 监听控制栏布局变化，获取实际高度
        mediaControls.viewTreeObserver.addOnGlobalLayoutListener {
            val newHeight = mediaControls.height
            if (newHeight != controlBarHeight) {
                controlBarHeight = newHeight
                updateSubtitlePosition()
            }
        }

        updateSubtitlePosition()
    }

    private fun updateSubtitlePosition() {
        val lp = customSubtitleView.layoutParams as? FrameLayout.LayoutParams ?: return
        val isFull = fullscreenManager.isFullscreen()
        val controlsVisible = mediaControls.visibility == View.VISIBLE

        lp.bottomMargin = when {
            isFull -> dpToPx(40)
            controlsVisible && controlBarHeight > 0 -> controlBarHeight + dpToPx(16)
            else -> dpToPx(100)
        }
        customSubtitleView.layoutParams = lp
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知视频"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFilePath = intent.getStringExtra("FILE_PATH") ?: ""
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""
        currentVideoIndex = intent.getIntExtra("CURRENT_INDEX", -1)

        autoPlayEnabled = intent.getBooleanExtra("AUTO_PLAY_ENABLED", false)
        if (autoPlayEnabled) {
            mediaFileList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra("MEDIA_FILE_LIST", FileSystemItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra("MEDIA_FILE_LIST")
            }
            currentPlayIndex = currentVideoIndex
        }

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

        fullscreenManager.setFullscreenChangeListener { isFullscreen ->
            updateSubtitlePosition()
        }

        controlOverlay = TextView(this).apply {
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
        updateSubtitlePosition()
    }

    private fun setupEventListeners() {
        backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        settingsButton.setOnClickListener { showSubtitleSettingsDialog() }
        subtitleButton.setOnClickListener { showSubtitleSelectionMenu() }
        playPauseButton.setOnClickListener {
            exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        previousButton.setOnClickListener {
            if (autoPlayEnabled) playPreviousVideo()
        }
        nextButton.setOnClickListener {
            if (autoPlayEnabled) playNextVideo()
        }
        fullscreenToggleButton.setOnClickListener {
            if (fullscreenManager.isFullscreen()) fullscreenManager.exitFullscreen()
            else fullscreenManager.enterFullscreen()
            updateSubtitlePosition()
        }
    }

    // ==================== 字幕设置相关 ====================
    private fun loadSubtitlePreferences() {
        sharedPrefs = getSharedPreferences("subtitle_prefs", MODE_PRIVATE)
        subtitleTextSize = sharedPrefs.getFloat("text_size", 16f)
        subtitleFraction = sharedPrefs.getFloat("text_fraction", 1.0f)
        subtitleTextColor = sharedPrefs.getInt("text_color", Color.WHITE)

        customSubtitleView.textSize = subtitleTextSize
        customSubtitleView.setTextColor(subtitleTextColor)

        // 应用到 ExoPlayer 内嵌字幕视图
        applyEmbeddedSubtitleStyle()
    }

    /**
     * 设置内嵌字幕的样式：背景透明 + 统一缩放比例
     */
    private fun applyEmbeddedSubtitleStyle() {
        playerView.subtitleView?.apply {
            setBackgroundColor(Color.TRANSPARENT)

            // 使用分数缩放调整字体大小（替换私有 setTextSize）
            setFractionalTextSize(subtitleFraction)

            val style = CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK,
                null
            )
            setStyle(style)
            setApplyEmbeddedStyles(true)
        }
    }

    private fun showSubtitleSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_subtitle_settings, null)
        val sizeSeekBar = view.findViewById<SeekBar>(R.id.seekBarSize)
        val sizeValueText = view.findViewById<TextView>(R.id.textSizeValue)
        val colorWhite = view.findViewById<View>(R.id.colorWhite)
        val colorYellow = view.findViewById<View>(R.id.colorYellow)
        val colorGreen = view.findViewById<View>(R.id.colorGreen)
        val colorCyan = view.findViewById<View>(R.id.colorCyan)
        val colorRed = view.findViewById<View>(R.id.colorRed)

        sizeSeekBar.progress = ((subtitleTextSize - 10) * 10).toInt()
        sizeValueText.text = "${subtitleTextSize.toInt()}sp"

        sizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = 10f + progress / 10f
                sizeValueText.text = "${newSize.toInt()}sp"

                // 更新自定义 ASS 字幕大小
                customSubtitleView.textSize = newSize

                // 更新内嵌字幕缩放比例（基准 16sp）
                val newFraction = newSize / 16f
                playerView.subtitleView?.setFractionalTextSize(newFraction)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val colorClickListener = View.OnClickListener { v ->
            val color = when (v.id) {
                R.id.colorWhite -> Color.WHITE
                R.id.colorYellow -> Color.YELLOW
                R.id.colorGreen -> Color.GREEN
                R.id.colorCyan -> Color.CYAN
                R.id.colorRed -> Color.RED
                else -> Color.WHITE
            }
            customSubtitleView.setTextColor(color)
        }

        colorWhite.setOnClickListener(colorClickListener)
        colorYellow.setOnClickListener(colorClickListener)
        colorGreen.setOnClickListener(colorClickListener)
        colorCyan.setOnClickListener(colorClickListener)
        colorRed.setOnClickListener(colorClickListener)

        builder.setView(view)
            .setTitle("字幕样式设置")
            .setPositiveButton("保存") { _, _ ->
                subtitleTextSize = customSubtitleView.textSize / resources.displayMetrics.scaledDensity
                subtitleFraction = subtitleTextSize / 16f
                subtitleTextColor = customSubtitleView.currentTextColor

                sharedPrefs.edit()
                    .putFloat("text_size", subtitleTextSize)
                    .putFloat("text_fraction", subtitleFraction)
                    .putInt("text_color", subtitleTextColor)
                    .apply()

                // 确保内嵌字幕最终状态
                applyEmbeddedSubtitleStyle()
                Toast.makeText(this, "字幕样式已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消") { dialog, _ ->
                // 恢复先前值
                customSubtitleView.textSize = subtitleTextSize
                customSubtitleView.setTextColor(subtitleTextColor)
                playerView.subtitleView?.setFractionalTextSize(subtitleFraction)
                dialog.dismiss()
            }
            .show()
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
        // 初始应用透明背景和字体缩放
        applyEmbeddedSubtitleStyle()
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

        findAndLoadMatchingSubtitle()
    }

    private fun showError(message: String) {
        mediaLoadingProgress.visibility = View.GONE
        errorTextView.text = message
        errorTextView.visibility = View.VISIBLE
    }

    // ==================== 连播控制 ====================
    private fun playNextVideo() {
        val list = mediaFileList ?: return
        if (list.isEmpty()) return
        val nextIndex = currentPlayIndex + 1
        if (nextIndex >= list.size) {
            Toast.makeText(this, "已是最后一集", Toast.LENGTH_SHORT).show()
            return
        }
        val nextVideo = list[nextIndex]
        currentPlayIndex = nextIndex
        playVideoItem(nextVideo)
    }

    private fun playPreviousVideo() {
        val list = mediaFileList ?: return
        if (list.isEmpty()) return
        val prevIndex = currentPlayIndex - 1
        if (prevIndex < 0) {
            Toast.makeText(this, "已是第一集", Toast.LENGTH_SHORT).show()
            return
        }
        val prevVideo = list[prevIndex]
        currentPlayIndex = prevIndex
        playVideoItem(prevVideo)
    }

    private fun playVideoItem(video: FileSystemItem) {
        val encodedPath = URLEncoder.encode(video.path, "UTF-8")
        currentFileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath"
        currentFileName = video.name
        currentFilePath = video.path
        fileNameTextView.text = currentFileName
        // 重置字幕状态
        assDialogues = emptyList()
        customSubtitleView.visibility = View.GONE
        lastDisplayedDialogues = emptyList()
        loadVideo()
    }

    // ==================== 字幕功能（ASS/SSA 双语支持） ====================
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
                matching?.let { loadSubtitle(it.path) }
            } catch (_: Exception) { }
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
                        loadSubtitle(subtitleFiles[which].path)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@VideoPlayerActivityV2, "获取字幕列表失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSubtitle(subtitlePath: String) {
        val ext = subtitlePath.substringAfterLast('.').lowercase()
        if (ext !in setOf("ass", "ssa")) {
            loadSubtitleViaExoPlayer(subtitlePath)
            return
        }

        val encodedPath = URLEncoder.encode(subtitlePath, "UTF-8")
        val subtitleUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath"

        coroutineScope.launch {
            try {
                mediaLoadingProgress.visibility = View.VISIBLE
                val content = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(subtitleUrl).build()
                    client.newCall(request).execute().body?.string() ?: ""
                }
                val (styles, dialogues) = AssParser.parse(content)
                assStyles = styles
                assDialogues = dialogues
                lastDisplayedDialogues = emptyList()
                currentSubtitlePath = subtitlePath

                playerView.subtitleView?.visibility = View.GONE
                customSubtitleView.visibility = if (dialogues.isNotEmpty()) View.VISIBLE else View.GONE

                Toast.makeText(this@VideoPlayerActivityV2, "ASS字幕加载 (${dialogues.size}条)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@VideoPlayerActivityV2, "加载ASS失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                mediaLoadingProgress.visibility = View.GONE
            }
        }
    }

    private fun loadSubtitleViaExoPlayer(subtitlePath: String) {
        val player = exoPlayer ?: return
        val currentMediaItem = player.currentMediaItem ?: return
        val currentPosition = player.currentPosition
        val videoUri = currentMediaItem.localConfiguration?.uri ?: return

        val encodedPath = URLEncoder.encode(subtitlePath, "UTF-8")
        val subtitleUri = Uri.parse("${currentServerUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath")

        val mimeType = when (subtitlePath.substringAfterLast('.').lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }

        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(mimeType)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val newMediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(listOf(subtitleConfig))
            .build()

        player.setMediaItem(newMediaItem, currentPosition)
        player.prepare()
        player.playWhenReady = true

        customSubtitleView.visibility = View.GONE
        playerView.subtitleView?.visibility = View.VISIBLE

        Toast.makeText(this, "字幕已加载", Toast.LENGTH_SHORT).show()
    }

    private fun updateAssSubtitle(positionMs: Long) {
        if (assDialogues.isEmpty()) {
            customSubtitleView.visibility = View.GONE
            return
        }

        val currentDialogues = assDialogues.filter { positionMs in it.startMs until it.endMs }
        if (currentDialogues.isEmpty()) {
            customSubtitleView.text = ""
            customSubtitleView.visibility = View.GONE
            return
        }

        if (currentDialogues == lastDisplayedDialogues) return
        lastDisplayedDialogues = currentDialogues

        val builder = SpannableStringBuilder()
        for ((index, dialogue) in currentDialogues.withIndex()) {
            if (index > 0) builder.append("\n")
            val baseStyle = assStyles[dialogue.styleName] ?: AssStyle()
            val spanned: SpannedString = renderAssText(
                raw = dialogue.rawText,
                baseStyle = baseStyle,
                styleMap = assStyles,
                forceTextColor = subtitleTextColor
            )
            builder.append(spanned)
        }

        customSubtitleView.text = builder
        customSubtitleView.visibility = View.VISIBLE
    }

    // ==================== Player.Listener 实现 ====================
    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> mediaLoadingProgress.visibility = View.VISIBLE
            Player.STATE_READY -> {
                mediaLoadingProgress.visibility = View.GONE
                updatePlayPauseButton()
            }
            Player.STATE_ENDED -> {
                mediaLoadingProgress.visibility = View.GONE
                if (autoPlayEnabled && mediaFileList != null) {
                    handler.postDelayed({
                        playNextVideo()
                    }, 1000)
                }
            }
            Player.STATE_IDLE -> mediaLoadingProgress.visibility = View.GONE
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