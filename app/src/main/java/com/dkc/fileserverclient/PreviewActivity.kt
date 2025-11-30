package com.dkc.fileserverclient

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Matrix
import android.graphics.PointF
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.*
import java.util.*
import kotlin.math.abs

@UnstableApi
class PreviewActivity : AppCompatActivity() {

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

    // 视频预览组件 - 使用 ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var videoLoadingProgress: ProgressBar
    private lateinit var videoControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView
    private lateinit var speedIndicator: TextView

    // 文本预览组件
    private lateinit var textPreview: TextView
    private lateinit var textLoadingProgress: ProgressBar

    // 通用预览组件
    private lateinit var webViewPreview: WebView
    private lateinit var generalLoadingProgress: ProgressBar

    // 状态变量
    private var isFullscreen = false
    private var currentFileType = ""
    private var currentFileUrl = ""
    private var currentFileName = ""
    private var isPlaying = false
    private var isFastForwarding = false

    // ExoPlayer 实例
    private var exoPlayer: ExoPlayer? = null

    // 网络客户端 - 使用统一的 UnsafeHttpClient
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = Runnable { updateProgress() }

    // 手势检测 - 使用 GestureDetector
    private lateinit var gestureDetector: GestureDetector
    private var isGestureHandling = false
    private var lastMoveX = 0f
    private var lastMoveY = 0f

    private var tapCount = 0
    private val tapTimeoutMillis = 300L
    private val doubleTapHandler = Handler(Looper.getMainLooper())
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPressDetected = false

    // 视频时长相关
    private var videoDuration: Long = 0

    // 图片缩放相关变量
    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val start = PointF()
    private val mid = PointF()
    private var mode = NONE
    private var minScale = 1.0f
    private var maxScale = 4.0f

    // 滑动控制相关变量
    private var startX = 0f
    private var startY = 0f
    private var isSwiping = false
    private var swipeRegion = 0 // 0:左, 1:中, 2:右
    private var regionWidth = 0

    // 音频管理
    private lateinit var audioManager: AudioManager
    private var maxVolume = 0
    private var currentVolume = 0

    // 控制提示视图
    private lateinit var controlOverlay: TextView
    private lateinit var controlIcon: ImageView
    private lateinit var controlContainer: LinearLayout
    private val hideControlRunnable = Runnable { hideControlOverlay() }

    // 控制栏自动隐藏
    private val hideControlsRunnable = Runnable { hideControls() }

    // 进度控制相关
    private var progressSensitivity = 0.5f // 进度控制灵敏度
    private var volumeSensitivity = 1.2f // 音量控制灵敏度
    private var brightnessSensitivity = 0.6f // 亮度控制灵敏度
    // 音量控制相关变量
    private var virtualVolume = 7.5f // 虚拟连续音量值 (0.0-15.0)
    private var lastSystemVolume = 0 // 上次设置的系统音量

    // 自动连播相关变量
    private var autoPlayEnabled = false
    private var mediaFileList: ArrayList<FileSystemItem>? = null
    private var currentMediaIndex = -1
    private var currentServerUrl = ""
    private var currentDirectoryPath = ""
    private var autoPlayListener: Player.Listener? = null

    // 控制栏显示状态
    private var controlsVisible = true
    private var controlsAutoHideEnabled = true

    // 缩放模式常量
    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val CLICK = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()
        setupGestureDetector() // 初始化手势检测器
        setupIntentData()
        setupEventListeners()
        setupImageZoom()
        setupAudioManager()
        loadPreview()

        // 获取自动连播相关参数
        autoPlayEnabled = intent.getBooleanExtra("AUTO_PLAY_ENABLED", false)
        mediaFileList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST", FileSystemItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("MEDIA_FILE_LIST")
        }
        currentMediaIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        currentDirectoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""

        // 设置自动连播监听
        if (autoPlayEnabled && mediaFileList != null && currentMediaIndex != -1) {
            setupAutoPlayListener()
            addAutoPlayControls()
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // 返回true表示要处理后续事件
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 确认的单击（不是双击的一部分）
                if (!isLongPressDetected && !isSwiping && !isGestureHandling) {
                    handleSingleTap()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击事件
                if (!isLongPressDetected && !isSwiping && !isGestureHandling) {
                    handleDoubleTapVideo()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 长按事件
                if (!isSwiping && !isGestureHandling) {
                    handleLongPress()
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 滑动事件 - 这里我们主要用来自定义滑动处理
                if (!isLongPressDetected) {
                    handleScrollGesture(distanceX, distanceY, e2.x, e2.y)
                }
                return true
            }
        })

        // 启用长按检测
        gestureDetector.setIsLongpressEnabled(true)
    }

    private fun handleScrollGesture(distanceX: Float, distanceY: Float, currentX: Float, currentY: Float) {
        // 这个方法由GestureDetector的onScroll调用
        // 但我们主要使用自定义的滑动处理，所以这里可以留空或用于特殊处理
    }

    private fun handleLongPress() {
        isLongPressDetected = true
        hideControls() // 长按时立即隐藏控制栏
        enableFastForward(true)
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

        // 初始化 ExoPlayer 组件
        playerView = findViewById(R.id.playerView)
        videoLoadingProgress = findViewById(R.id.videoLoadingProgress)
        videoControls = findViewById(R.id.videoControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        fullscreenToggleButton = findViewById(R.id.fullscreenToggleButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)
        speedIndicator = findViewById(R.id.speedIndicator)

        textPreview = findViewById(R.id.textPreview)
        textLoadingProgress = findViewById(R.id.textLoadingProgress)

        webViewPreview = findViewById(R.id.webViewPreview)
        generalLoadingProgress = findViewById(R.id.generalLoadingProgress)

        // 初始化控制覆盖层
        controlOverlay = findViewById(R.id.controlOverlay)
        controlIcon = findViewById(R.id.controlIcon)
        controlContainer = findViewById(R.id.controlContainer)

        // 初始化WebView
        setupWebView()
        // 初始化 ExoPlayer
        initializePlayer()
        // 初始化进度条
        setupSeekBar()
        // 初始化手势检测
        setupGestureDetectors()

        // 初始状态：非全屏模式下控制栏始终显示
        updateControlsVisibility()

        // 计算区域宽度
        val displayMetrics = resources.displayMetrics
        regionWidth = displayMetrics.widthPixels / 3
    }

    private fun setupAudioManager() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // 初始化虚拟音量值为当前系统音量 (0-15)
        virtualVolume = currentVolume.toFloat()
        lastSystemVolume = currentVolume

        Log.d("VolumeControl", "系统音量范围: 0-$maxVolume, 当前音量: $currentVolume")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupImageZoom() {
        // 设置图片视图的缩放功能
        imagePreview.scaleType = ImageView.ScaleType.MATRIX
        imagePreview.setOnTouchListener { _, event ->
            handleImageTouch(event)
        }
    }

    private fun handleImageTouch(event: MotionEvent): Boolean {
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                // 单点触摸开始
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多点触摸开始
                val oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 触摸结束
                mode = NONE

                // 处理双击事件
                if (event.action == MotionEvent.ACTION_UP) {
                    tapCount++
                    if (tapCount == 1) {
                        doubleTapHandler.postDelayed({
                            if (tapCount == 1) {
                                // 单击 - 不执行操作
                                tapCount = 0
                            }
                        }, tapTimeoutMillis)
                    } else if (tapCount == 2) {
                        // 双击 - 切换缩放状态
                        doubleTapHandler.removeCallbacksAndMessages(null)
                        handleDoubleTap()
                        tapCount = 0
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    // 拖动图片
                    matrix.set(savedMatrix)
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                } else if (mode == ZOOM) {
                    // 缩放图片
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        matrix.set(savedMatrix)
                        val scale = newDist / spacing(event, savedMatrix)
                        val currentScale = getCurrentScale()

                        // 限制缩放范围
                        val newScale = currentScale * scale
                        if (newScale in minScale..maxScale) {
                            matrix.postScale(scale, scale, mid.x, mid.y)
                        }
                    }
                }
            }
        }

        // 应用变换矩阵
        imagePreview.imageMatrix = matrix
        return true
    }

    private fun handleDoubleTap() {
        val currentScale = getCurrentScale()

        if (currentScale > minScale) {
            // 如果当前已缩放，则重置到最小缩放
            matrix.setScale(minScale, minScale)
            matrix.postTranslate(
                (imagePreview.width - imagePreview.drawable.intrinsicWidth * minScale) / 2,
                (imagePreview.height - imagePreview.drawable.intrinsicHeight * minScale) / 2
            )
        } else {
            // 如果当前是最小缩放，则缩放到最大缩放
            val scale = maxScale / minScale
            matrix.postScale(scale, scale, imagePreview.width / 2f, imagePreview.height / 2f)
        }

        imagePreview.imageMatrix = matrix
    }

    private fun getCurrentScale(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun spacing(event: MotionEvent, matrix: Matrix): Float {
        val point1 = floatArrayOf(event.getX(0), event.getY(0))
        val point2 = floatArrayOf(event.getX(1), event.getY(1))
        matrix.mapPoints(point1)
        matrix.mapPoints(point2)
        val x = point1[0] - point2[0]
        val y = point1[1] - point2[1]
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    @UnstableApi
    private fun initializePlayer() {
        // 创建使用不安全 SSL 的数据源工厂
        val dataSourceFactory = createUnsafeDataSourceFactory()

        exoPlayer = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                videoLoadingProgress.visibility = View.GONE
                                this@PreviewActivity.isPlaying = this@apply.isPlaying
                                updatePlayPauseButton()
                                updateDuration()
                                startProgressUpdates()
                                updateControlsVisibility()
                            }
                            Player.STATE_BUFFERING -> {
                                videoLoadingProgress.visibility = View.VISIBLE
                            }
                            Player.STATE_ENDED -> {
                                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                                this@PreviewActivity.isPlaying = false
                                if (isFullscreen) {
                                    showControlsPersistent() // 播放结束时显示控制栏
                                }

                                // 自动连播：播放结束后播放下一个
                                if (autoPlayEnabled && this@apply.isPlaying) {
                                    handler.postDelayed({
                                        playNextMedia()
                                    }, 1000) // 延迟1秒后播放下一个
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        videoLoadingProgress.visibility = View.GONE
                        showError("播放错误: ${error.message}")

                        // 自动连播：播放错误时也尝试播放下一个
                        if (autoPlayEnabled) {
                            handler.postDelayed({
                                playNextMedia()
                            }, 2000)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@PreviewActivity.isPlaying = isPlaying
                        updatePlayPauseButton()
                        updateControlsVisibility() // 播放状态变化时更新控制栏
                    }
                })
            }

        playerView.player = exoPlayer
        playerView.useController = false // 使用自定义控制器
    }

    private fun setupSeekBar() {
        // 设置进度条的最大值为1000，这样可以更精确地控制进度
        seekBar.max = 1000

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.let { player ->
                        val duration = player.duration
                        if (duration > 0) {
                            // 将进度条的进度(0-1000)转换为实际的时间位置
                            val newPosition = (duration * progress / 1000).toLong()
                            player.seekTo(newPosition)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // 停止进度更新，避免冲突
                stopProgressUpdates()
                // 暂停自动隐藏控制栏
                controlsAutoHideEnabled = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 重新开始进度更新
                startProgressUpdates()
                // 恢复自动隐藏控制栏
                controlsAutoHideEnabled = true
                // 如果正在播放，0.5秒后隐藏控制栏
                if (isFullscreen && isPlaying) {
                    handler.postDelayed({ hideControls() }, 500)
                }
            }
        })
    }

    private fun startProgressUpdates() {
        handler.post(updateProgressRunnable)
    }

    private fun stopProgressUpdates() {
        handler.removeCallbacks(updateProgressRunnable)
    }

    private fun updateProgress() {
        exoPlayer?.let { player ->
            val duration = player.duration
            val position = player.currentPosition

            if (duration > 0) {
                // 更新视频时长（如果发生了变化）
                if (duration != videoDuration) {
                    videoDuration = duration
                    updateDuration()
                }

                // 计算进度百分比 (0-1000)
                val progress = if (duration > 0) {
                    (position * 1000 / duration).toInt()
                } else {
                    0
                }

                // 更新进度条
                seekBar.progress = progress

                // 更新时间显示
                currentTimeTextView.text = formatTime(position)
            }

            // 继续更新
            handler.postDelayed(updateProgressRunnable, 1000)
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

    private fun updateDuration() {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration > 0) {
                videoDuration = duration
                durationTextView.text = formatTime(duration)
            }
        }
    }

    @UnstableApi
    private fun createUnsafeDataSourceFactory(): DataSource.Factory {
        // 使用统一的 UnsafeHttpClient 创建数据源工厂
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(client)
        return DefaultDataSource.Factory(this, okHttpDataSourceFactory)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetectors() {
        playerView.setOnTouchListener { _, event ->
            // 先传递给 GestureDetector
            val handledByGesture = gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isGestureHandling = false
                    startX = event.x
                    startY = event.y
                    lastMoveX = event.x
                    lastMoveY = event.y
                    isSwiping = false
                    swipeRegion = -1

                    // 更新当前系统音量状态
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    virtualVolume = currentVolume.toFloat()
                    lastSystemVolume = currentVolume

                    // 取消所有延迟任务
                    doubleTapHandler.removeCallbacksAndMessages(null)
                    longPressHandler.removeCallbacksAndMessages(null)
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - lastMoveX
                    val deltaY = event.y - lastMoveY

                    // 检测滑动开始
                    if (!isSwiping && (abs(deltaX) > 5 || abs(deltaY) > 5)) {
                        isSwiping = true
                        isGestureHandling = true

                        // 确定滑动类型和区域（只确定一次）
                        if (swipeRegion == -1) {
                            if (abs(deltaX) > abs(deltaY) * 1.5) {
                                // 水平滑动 - 进度控制
                                swipeRegion = 1
                                showControlsTemporarily()
                            } else {
                                // 垂直滑动 - 根据起始位置判断是亮度还是音量
                                swipeRegion = if (startX < regionWidth) 0 else 2
                            }
                        }
                    }

                    if (isSwiping && swipeRegion != -1) {
                        when (swipeRegion) {
                            0 -> { // 左侧垂直滑动 - 亮度控制
                                handleBrightnessControl(deltaY)
                            }
                            1 -> { // 水平滑动 - 进度控制
                                handleProgressControl(deltaX)
                            }
                            2 -> { // 右侧垂直滑动 - 音量控制
                                handleVolumeControl(deltaY)
                            }
                        }
                    }

                    lastMoveX = event.x
                    lastMoveY = event.y
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressDetected) {
                        // 如果是长按后的松开，恢复速度
                        enableFastForward(false)
                        isLongPressDetected = false
                    }

                    // 如果是水平滑动结束，快速隐藏控制栏
                    if (isSwiping && swipeRegion == 1) {
                        handler.postDelayed({ hideControls() }, 500)
                    }

                    // 隐藏控制提示
                    hideControlOverlay()

                    // 重置状态
                    isSwiping = false
                    isGestureHandling = false
                    swipeRegion = -1
                    volumeChangeAccumulator = 0f // 重置音量累计器
                }
            }
            true
        }
    }

    private fun handleSingleTap() {
        // 如果处于长按状态或滑动状态，不处理单击事件
        if (isLongPressDetected || isSwiping || isGestureHandling) {
            return
        }

        if (isFullscreen) {
            // 全屏模式下：切换控制栏可见性
            if (controlsVisible) {
                hideControls()
            } else {
                showControlsPersistent()
            }
        } else {
            // 非全屏模式下：切换播放状态
            toggleVideoPlayback()
        }
    }

    private fun handleDoubleTapVideo() {
        // 如果处于长按状态或滑动状态，不处理双击事件
        if (isLongPressDetected || isSwiping || isGestureHandling) {
            return
        }

        // 视频双击处理：切换播放状态
        toggleVideoPlayback()
    }

    private fun showControlsPersistent() {
        // 如果处于长按状态，不显示控制栏
        if (isLongPressDetected) {
            return
        }

        videoControls.visibility = View.VISIBLE
        controlsVisible = true
        // 移除所有自动隐藏任务，保持显示状态
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun showControlsTemporarily() {
        // 如果处于长按状态，不显示控制栏
        if (isLongPressDetected) {
            return
        }

        videoControls.visibility = View.VISIBLE
        controlsVisible = true
        // 显示控制栏但不设置自动隐藏
    }

    private fun hideControls() {
        if (isFullscreen) {
            videoControls.visibility = View.GONE
            controlsVisible = false
        }
    }

    private fun toggleControlsVisibility() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControlsPersistent()
        }
    }

    private fun setupClickGestures() {
        // 为播放器视图添加单击监听器
        playerView.setOnClickListener {
            // 点击事件已在手势检测中处理，这里不需要重复处理
        }

        // 为控制栏添加点击监听器，防止事件冒泡
        videoControls.setOnClickListener {
            // 阻止事件传递到播放器视图
        }
    }

    // 显示滑动控制提示
    private fun showSwipeHint(region: Int) {
        val (iconRes, hintText) = when (region) {
            0 -> Pair(R.drawable.ic_brightness, "亮度控制")
            1 -> Pair(R.drawable.ic_fast_forward, "进度控制")
            2 -> Pair(R.drawable.ic_volume, "音量控制")
            else -> Pair(0, "")
        }

        if (iconRes != 0) {
            controlIcon.setImageResource(iconRes)
            controlOverlay.text = hintText
            controlContainer.visibility = View.VISIBLE

            // 2秒后自动隐藏
            handler.removeCallbacks(hideControlRunnable)
            handler.postDelayed(hideControlRunnable, 2000)
        }
    }

    // 显示控制覆盖层（用于显示亮度、音量、进度信息）
    private fun showControlOverlay(text: String, iconRes: Int = 0) {
        controlOverlay.text = text
        if (iconRes != 0) {
            controlIcon.setImageResource(iconRes)
        }
        controlContainer.visibility = View.VISIBLE

        // 2秒后自动隐藏
        handler.removeCallbacks(hideControlRunnable)
        handler.postDelayed(hideControlRunnable, 2000)
    }

    // 隐藏控制覆盖层
    private fun hideControlOverlay() {
        controlContainer.visibility = View.GONE
    }

    // 亮度控制
    private fun handleBrightnessControl(deltaY: Float) {
        val window = window
        val layoutParams = window.attributes

        // 计算亮度变化（向下滑动降低亮度，向上滑动提高亮度）
        val brightnessChange = -deltaY / 800f * brightnessSensitivity // 调整灵敏度

        // 更新亮度值（范围：0.0 - 1.0）
        var newBrightness = layoutParams.screenBrightness + brightnessChange
        if (layoutParams.screenBrightness < 0) {
            // 如果之前没有设置过亮度，使用系统默认
            newBrightness = 0.5f + brightnessChange
        }
        newBrightness = newBrightness.coerceIn(0.01f, 1.0f)

        // 设置新的亮度
        layoutParams.screenBrightness = newBrightness
        window.attributes = layoutParams

        // 显示亮度提示
        showControlOverlay("亮度: ${(newBrightness * 100).toInt()}%", android.R.drawable.ic_menu_edit)
    }

    // 进度控制 - 修复版本
    private fun handleProgressControl(deltaX: Float) {
        exoPlayer?.let { player ->
            val duration = player.duration
            if (duration > 0) {
                // 计算进度变化（向右滑动快进，向左滑动快退）
                // 使用灵敏度系数，使小幅度滑动也能产生明显效果
                val progressChange = (deltaX / resources.displayMetrics.widthPixels) * duration * progressSensitivity

                // 计算新的播放位置
                var newPosition = player.currentPosition + progressChange.toLong()
                newPosition = newPosition.coerceIn(0, duration)

                // 跳转到新位置
                player.seekTo(newPosition)

                // 显示进度提示
                val progressPercent = (newPosition * 100 / duration).toInt()
                val direction = if (deltaX > 0) "快进" else "快退"
                showControlOverlay("$direction ${formatTime(newPosition)} / ${formatTime(duration)} ($progressPercent%)",
                    android.R.drawable.ic_media_ff)

                // 更新进度条显示
                updateSeekBarProgress(newPosition, duration)
            }
        }
    }

    // 更新进度条显示
    private fun updateSeekBarProgress(position: Long, duration: Long) {
        if (duration > 0) {
            val progress = (position * 1000 / duration).toInt()
            seekBar.progress = progress
            currentTimeTextView.text = formatTime(position)
        }
    }

    // 音量控制 - 使用累计变化量避免跳变
    private var volumeChangeAccumulator = 0f

    private fun handleVolumeControl(deltaY: Float) {
        // 累计变化量
        volumeChangeAccumulator += -deltaY / 800f * volumeSensitivity

        // 当累计变化量足够改变至少1个音量单位时
        if (abs(volumeChangeAccumulator) >= 1.0f / maxVolume) {
            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // 计算需要改变的音量单位数
            val volumeUnitsToChange = (volumeChangeAccumulator * maxVolume).toInt()

            if (volumeUnitsToChange != 0) {
                var newVolume = currentVolume + volumeUnitsToChange
                newVolume = newVolume.coerceIn(0, maxVolume)

                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                // 显示音量提示
                val volumePercent = (newVolume * 100 / maxVolume).toInt()
                showControlOverlay("音量: $volumePercent%", R.drawable.ic_volume)

                // 重置累计器，保留余数
                volumeChangeAccumulator -= volumeUnitsToChange.toFloat() / maxVolume
            }
        }
    }

    private fun enableFastForward(enable: Boolean) {
        exoPlayer?.let { player ->
            if (enable) {
                // 启用加速
                player.playbackParameters = player.playbackParameters.withSpeed(2.0f)
                isFastForwarding = true
                speedIndicator.visibility = View.VISIBLE
                speedIndicator.text = "2.0x"

                // 快进时立即隐藏控制栏
                hideControls()

                // 3秒后自动隐藏加速指示器
                handler.postDelayed({
                    speedIndicator.visibility = View.GONE
                }, 3000)
            } else {
                // 恢复正常速度
                player.playbackParameters = player.playbackParameters.withSpeed(1.0f)
                isFastForwarding = false
                speedIndicator.visibility = View.GONE

                // 快进结束后，根据播放状态决定是否显示控制栏
                if (!isPlaying) {
                    showControlsPersistent() // 如果暂停了，显示控制栏
                } else {
                    // 什么都不做 - 只是为了满足语法要求
                }
            }
        }
    }

    // 自动连播相关方法
    private fun setupAutoPlayListener() {
        autoPlayListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && autoPlayEnabled) {
                    handler.postDelayed({
                        playNextMedia()
                    }, 1000) // 延迟1秒后播放下一个
                }
            }
        }
        exoPlayer?.addListener(autoPlayListener!!)

        // 显示自动连播提示
        showToast("自动连播已启用 (${currentMediaIndex + 1}/${mediaFileList?.size ?: 0})")
    }

    private fun addAutoPlayControls() {
        // 在控制栏添加上一个/下一个按钮
        val previousButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundResource(R.drawable.selectable_item_background)
            setOnClickListener {
                playPreviousMedia()
            }
            contentDescription = "上一个"
        }

        val nextButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setBackgroundResource(R.drawable.selectable_item_background)
            setOnClickListener {
                playNextMedia()
            }
            contentDescription = "下一个"
        }

        // 在播放/暂停按钮旁边添加上一个/下一个按钮
        val params = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
        params.gravity = android.view.Gravity.CENTER_VERTICAL

        // 找到播放/暂停按钮的位置并插入
        val playPauseIndex = videoControls.indexOfChild(playPauseButton)
        videoControls.addView(previousButton, playPauseIndex, params)
        videoControls.addView(nextButton, playPauseIndex + 2, params) // +2 因为已经插入了一个按钮
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun playNextMedia() {
        if (!autoPlayEnabled || mediaFileList == null) return

        val nextIndex = currentMediaIndex + 1
        if (nextIndex < mediaFileList!!.size) {
            val nextItem = mediaFileList!![nextIndex]
            loadMediaFile(nextItem, nextIndex)
        } else {
            // 已经是最后一个，显示提示
            showToast("已经是最后一个文件")
            // 可以选择循环播放
            // loadMediaFile(mediaFileList!![0], 0)
        }
    }

    private fun playPreviousMedia() {
        if (!autoPlayEnabled || mediaFileList == null) return

        val prevIndex = currentMediaIndex - 1
        if (prevIndex >= 0) {
            val prevItem = mediaFileList!![prevIndex]
            loadMediaFile(prevItem, prevIndex)
        } else {
            showToast("已经是第一个文件")
        }
    }

    private fun loadMediaFile(item: FileSystemItem, index: Int) {
        try {
            val fileType = getFileType(item)
            val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
            val fileUrl = "${currentServerUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"

            // 更新当前文件信息
            currentFileName = item.name
            currentFileUrl = fileUrl
            currentFileType = fileType
            currentMediaIndex = index

            // 更新UI
            fileNameTextView.text = currentFileName
            fileTypeTextView.text = when (currentFileType) {
                "image" -> "图片"
                "video" -> "视频"
                "text" -> "文本"
                "audio" -> "音频"
                else -> "文件"
            }

            // 重新加载预览
            loadPreview()

            // 显示提示
            showToast("正在播放: ${item.name} (${index + 1}/${mediaFileList!!.size})")

        } catch (e: Exception) {
            Log.e("PreviewActivity", "加载媒体文件失败", e)
            showToast("加载失败: ${e.message}")
            // 加载失败时尝试播放下一个
            if (autoPlayEnabled) {
                handler.postDelayed({
                    playNextMedia()
                }, 2000)
            }
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

        // 视频控制事件
        playPauseButton.setOnClickListener {
            toggleVideoPlayback()
        }

        fullscreenToggleButton.setOnClickListener {
            toggleFullscreen()
        }
    }

    private fun loadPreview() {
        when (currentFileType) {
            "image" -> loadImagePreview()
            "video" -> loadVideoPreview()
            "audio" -> loadAudioPreview()
            "text" -> loadTextPreview()
            else -> loadGeneralPreview()
        }
    }

    private fun loadImagePreview() {
        showContainer(imageContainer)
        fileTypeTextView.visibility = View.VISIBLE

        coroutineScope.launch {
            try {
                // 使用统一的 UnsafeHttpClient 创建 ImageLoader
                val imageLoader = ImageLoader.Builder(this@PreviewActivity)
                    .okHttpClient(client) // 使用统一的客户端
                    .components {
                        // 如果需要自定义组件可以在这里添加
                    }
                    .build()

                val request = ImageRequest.Builder(this@PreviewActivity)
                    .data(currentFileUrl)
                    .target(imagePreview)
                    .transformations(RoundedCornersTransformation(8f))
                    .listener(
                        onStart = {
                            imageLoadingProgress.visibility = View.VISIBLE
                        },
                        onSuccess = { _, _ ->
                            imageLoadingProgress.visibility = View.GONE
                            setupInitialImageScale()
                        },
                        onError = { _, result ->
                            imageLoadingProgress.visibility = View.GONE
                            Log.e("PreviewActivity", "图片加载失败: ${result.throwable.message}")
                            showError("图片加载失败: ${result.throwable.message}")
                        }
                    )
                    .build()

                imageLoader.enqueue(request)

            } catch (e: Exception) {
                imageLoadingProgress.visibility = View.GONE
                Log.e("PreviewActivity", "图片加载异常: ${e.message}")
                showError("图片加载失败: ${e.message}")
            }
        }
    }

    private fun setupInitialImageScale() {
        // 延迟执行以确保图片已完全加载到ImageView中
        handler.postDelayed({
            val drawable = imagePreview.drawable
            if (drawable != null) {
                val imageWidth = drawable.intrinsicWidth.toFloat()
                val imageHeight = drawable.intrinsicHeight.toFloat()
                val viewWidth = imagePreview.width.toFloat()
                val viewHeight = imagePreview.height.toFloat()

                // 计算适合屏幕的缩放比例
                val scaleX = viewWidth / imageWidth
                val scaleY = viewHeight / imageHeight
                minScale = minOf(scaleX, scaleY).coerceAtMost(1.0f) // 确保不会放大超过原图

                // 设置初始矩阵以显示全图
                matrix.setScale(minScale, minScale)
                matrix.postTranslate(
                    (viewWidth - imageWidth * minScale) / 2,
                    (viewHeight - imageHeight * minScale) / 2
                )

                imagePreview.imageMatrix = matrix
            }
        }, 100)
    }

    @UnstableApi
    private fun loadVideoPreview() {
        showContainer(videoContainer)
        fileTypeTextView.visibility = View.VISIBLE

        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(currentFileUrl)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            isPlaying = true
            updatePlayPauseButton()

        } catch (e: Exception) {
            showError("视频加载失败: ${e.message}")
        }
    }

    @UnstableApi
    private fun loadAudioPreview() {
        showContainer(videoContainer) // 使用视频容器，但不显示视频视图
        fileTypeTextView.visibility = View.VISIBLE

        playerView.visibility = View.GONE // 隐藏视频视图
        videoControls.visibility = View.VISIBLE // 显示控制条

        try {
            val mediaSourceFactory = ProgressiveMediaSource.Factory(createUnsafeDataSourceFactory())
            val mediaItem = MediaItem.fromUri(currentFileUrl)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

            isPlaying = true
            updatePlayPauseButton()

        } catch (e: Exception) {
            showError("音频加载失败: ${e.message}")
        }
    }

    private fun loadTextPreview() {
        // 启动新的文本预览Activity
        val intent = Intent(this, TextPreviewActivity::class.java).apply {
            putExtra("FILE_NAME", currentFileName)
            putExtra("FILE_URL", currentFileUrl)
        }
        startActivity(intent)
        finish() // 关闭当前预览界面
    }

    private fun loadGeneralPreview() {
        showContainer(generalContainer)
        fileTypeTextView.visibility = View.VISIBLE

        // 使用WebView加载文件
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

        // 重置双击状态
        tapCount = 0
        doubleTapHandler.removeCallbacksAndMessages(null)
    }

    private fun showError(message: String) {
        showContainer(errorTextView)
        errorTextView.text = message
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    @SuppressLint("InlinedApi")
    private fun enterFullscreen() {
        isFullscreen = true

        // 隐藏状态栏和导航栏
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )

        // 设置全屏窗口标志
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 隐藏标题栏和其他UI元素
        titleBar.visibility = View.GONE
        fileTypeTextView.visibility = View.GONE

        // 横屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 隐藏系统UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }

        // 更新全屏按钮图标
        fullscreenToggleButton.setImageResource(R.drawable.ic_fullscreen_exit)

        // 全屏模式下更新控制栏可见性
        updateControlsVisibility()
    }

    @SuppressLint("InlinedApi")
    private fun exitFullscreen() {
        isFullscreen = false

        // 显示状态栏和导航栏
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // 清除全屏窗口标志
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 显示标题栏
        titleBar.visibility = View.VISIBLE
        fileTypeTextView.visibility = View.VISIBLE

        // 竖屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 取消屏幕常亮
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 显示系统UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        }

        // 更新全屏按钮图标
        fullscreenToggleButton.setImageResource(R.drawable.ic_fullscreen)

        // 非全屏模式下显示控制栏
        videoControls.visibility = View.VISIBLE
        controlsVisible = true
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun toggleVideoPlayback() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            this@PreviewActivity.isPlaying = player.isPlaying
            updatePlayPauseButton()
            updateControlsVisibility()
        }
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        playPauseButton.setImageResource(iconRes)
    }

    // 更新控制栏可见性
    private fun updateControlsVisibility() {
        // 如果处于长按状态，保持控制栏隐藏
        if (isLongPressDetected) {
            hideControls()
            return
        }

        if (isFullscreen) {
            // 全屏模式下：播放时控制栏根据手势显示/隐藏，暂停时显示控制栏
            if (isPlaying) {
                // 播放时不自动显示控制栏，完全由手势控制
                // 不执行任何操作，保持当前状态
            } else {
                // 暂停时显示控制栏，不自动隐藏
                showControlsPersistent()
            }
        } else {
            // 非全屏模式下：始终显示控制栏
            videoControls.visibility = View.VISIBLE
            controlsVisible = true
            handler.removeCallbacks(hideControlsRunnable)
        }
    }

    private fun downloadFile() {
        Toast.makeText(this, "开始下载: $currentFileName", Toast.LENGTH_SHORT).show()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentFileUrl))
        startActivity(intent)
    }

    // 在返回时通知FileListActivity
    override fun onBackPressed() {
        val resultIntent = Intent().apply {
            if (autoPlayEnabled) {
                putExtra("ACTION", "EXIT_AUTO_PLAY")
            }
        }
        setResult(RESULT_OK, resultIntent)

        if (isFullscreen) {
            exitFullscreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        // 当Activity进入后台时暂停播放
        exoPlayer?.pause()
        stopProgressUpdates()
        // 清理所有手势相关状态
        isLongPressDetected = false
        isSwiping = false
        isGestureHandling = false
        // 清理所有handler
        doubleTapHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(hideControlRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (isFullscreen) {
            enterFullscreen()
        }
        // 重新开始进度更新
        startProgressUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        stopProgressUpdates()

        // 清理所有handler
        doubleTapHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(hideControlRunnable)
        handler.removeCallbacks(hideControlsRunnable)

        // 移除自动连播监听器
        autoPlayListener?.let { listener ->
            exoPlayer?.removeListener(listener)
        }

        // 释放播放器资源
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}