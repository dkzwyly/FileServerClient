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
import coil.Coil
import coil.ImageLoader
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*
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

    // 网络客户端
    private val client = createUnsafeOkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = Runnable { updateProgress() }

    // 手势检测
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

    // 进度控制相关
    private var progressSensitivity = 0.5f // 进度控制灵敏度
    private var volumeSensitivity = 1.2f // 音量控制灵敏度
    private var brightnessSensitivity = 0.6f // 亮度控制灵敏度
    // 音量控制相关变量
    private var virtualVolume = 7.5f // 虚拟连续音量值 (0.0-15.0)
    private var lastSystemVolume = 0 // 上次设置的系统音量

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
        setupIntentData()
        setupEventListeners()
        setupGestureDetectors()
        setupImageZoom()
        setupAudioManager()
        loadPreview()
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
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        videoLoadingProgress.visibility = View.GONE
                        showError("播放错误: ${error.message}")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@PreviewActivity.isPlaying = isPlaying
                        updatePlayPauseButton()
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
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // 重新开始进度更新
                startProgressUpdates()
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
        val okHttpClient = createUnsafeOkHttpClient()
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        return DefaultDataSource.Factory(this, okHttpDataSourceFactory)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestureDetectors() {
        playerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    isSwiping = false
                    swipeRegion = -1 // 重置滑动区域

                    // 更新当前系统音量状态
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    virtualVolume = currentVolume.toFloat() // 直接使用 0-15 的值
                    lastSystemVolume = currentVolume

                    // 开始长按检测
                    isLongPressDetected = false
                    longPressRunnable = Runnable {
                        isLongPressDetected = true
                        enableFastForward(true)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, 800)
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY

                    // 检测滑动开始
                    if (!isSwiping && (abs(deltaX) > 30 || abs(deltaY) > 30)) {
                        isSwiping = true
                        // 取消长按检测
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                        // 确定滑动类型和区域（只确定一次）
                        if (swipeRegion == -1) {
                            if (abs(deltaX) > abs(deltaY) * 1.5) {
                                // 水平滑动 - 进度控制（全屏任意位置）
                                swipeRegion = 1
                                showSwipeHint(1)
                            } else {
                                // 垂直滑动 - 根据起始位置判断是亮度还是音量
                                swipeRegion = if (startX < regionWidth) 0 else 2
                                showSwipeHint(swipeRegion)
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
                        // 更新起始位置以实现连续滑动
                        startX = event.x
                        startY = event.y
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 取消长按检测
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                    if (isLongPressDetected) {
                        // 如果是长按后的松开，恢复速度
                        enableFastForward(false)
                        isLongPressDetected = false
                    } else if (!isSwiping) {
                        // 如果不是滑动，处理点击事件
                        handlePlayerTap()
                    }

                    // 隐藏控制提示
                    hideControlOverlay()

                    // 重置状态
                    isSwiping = false
                    swipeRegion = -1
                }
            }
            true
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

    private fun handlePlayerTap() {
        tapCount++

        if (tapCount == 1) {
            // 第一次点击
            doubleTapHandler.postDelayed({
                if (tapCount == 1) {
                    // 这是单击，不是双击 - 不执行任何操作
                    tapCount = 0
                }
            }, tapTimeoutMillis)
        } else if (tapCount == 2) {
            // 第二次点击 - 双击
            doubleTapHandler.removeCallbacksAndMessages(null)
            toggleVideoPlayback()
            tapCount = 0
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

                // 3秒后自动隐藏加速指示器
                handler.postDelayed({
                    speedIndicator.visibility = View.GONE
                }, 3000)
            } else {
                // 恢复正常速度
                player.playbackParameters = player.playbackParameters.withSpeed(1.0f)
                isFastForwarding = false
                speedIndicator.visibility = View.GONE
            }
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
                // 创建忽略SSL的ImageLoader
                val imageLoader = ImageLoader.Builder(this@PreviewActivity)
                    .okHttpClient(client) // 使用忽略SSL的客户端
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
                            // 图片加载成功后，设置初始缩放以显示全图
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
        showContainer(textContainer)
        fileTypeTextView.visibility = View.VISIBLE

        coroutineScope.launch {
            try {
                val textContent = withContext(Dispatchers.IO) {
                    loadTextContent(currentFileUrl)
                }

                textLoadingProgress.visibility = View.GONE
                textPreview.text = textContent

            } catch (e: Exception) {
                showError("文本加载失败: ${e.message}")
            }
        }
    }

    private fun loadGeneralPreview() {
        showContainer(generalContainer)
        fileTypeTextView.visibility = View.VISIBLE

        // 使用WebView加载文件
        webViewPreview.loadUrl(currentFileUrl)
    }

    private suspend fun loadTextContent(url: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            response.body?.string() ?: throw IOException("响应体为空")
        }
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

        // 全屏模式下隐藏控制栏
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
        updateControlsVisibility()
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
        if (isFullscreen) {
            // 全屏模式下隐藏控制栏
            videoControls.visibility = View.GONE
        } else {
            // 非全屏模式下显示控制栏
            videoControls.visibility = View.VISIBLE
        }
    }

    private fun downloadFile() {
        Toast.makeText(this, "开始下载: $currentFileName", Toast.LENGTH_SHORT).show()

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentFileUrl))
        startActivity(intent)
    }

    override fun onBackPressed() {
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
        // 清理长按检测
        longPressHandler.removeCallbacksAndMessages(null)
        // 隐藏控制覆盖层
        handler.removeCallbacks(hideControlRunnable)
        hideControlOverlay()
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
        doubleTapHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(hideControlRunnable)

        // 释放播放器资源
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            // 创建信任所有证书的 TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // 创建 SSLContext 使用信任所有证书的 TrustManager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 创建不验证主机名的 HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}