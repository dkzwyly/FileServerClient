package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min

class ImageActivity : AppCompatActivity() {

    private lateinit var viewModel: ImageViewModel

    // UI 组件
    private lateinit var topControlBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var fileNameWithCount: TextView
    private lateinit var shareButton: ImageButton

    private lateinit var imagePreview: ImageView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var bottomControlBar: LinearLayout
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton

    private lateinit var imageManager: ImagePreviewManager

    // 手势
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // 矩阵与状态
    private val baseMatrix = Matrix()       // 初始居中适配矩阵
    private val matrix = Matrix()           // 当前变换矩阵
    private var currentScale = 1f           // 当前相对初始的缩放倍数
    private val minScale = 1f
    private val maxScale = 5f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false

    private var isControlsVisible = false   // 默认沉浸

    companion object {
        private const val TAG = "ImageActivity"
        private const val SWIPE_THRESHOLD = 100f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)

        viewModel = ViewModelProvider(this)[ImageViewModel::class.java]

        initViews()
        setupImageManager()
        setupGestureDetectors()
        setupTouchListener()
        setupEventListeners()
        loadIntentData()
        setupObservers()
    }

    private fun initViews() {
        topControlBar = findViewById(R.id.topControlBar)
        backButton = findViewById(R.id.backButton)
        fileNameWithCount = findViewById(R.id.fileNameWithCount)
        shareButton = findViewById(R.id.shareButton)

        imagePreview = findViewById(R.id.imagePreview)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        bottomControlBar = findViewById(R.id.bottomControlBar)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)

        // 默认隐藏控制栏（沉浸式）
        topControlBar.visibility = View.GONE
        bottomControlBar.visibility = View.GONE
    }

    private fun setupImageManager() {
        val client = UnsafeHttpClient.createUnsafeOkHttpClient()
        imageManager = ImagePreviewManager(
            context = this,
            coroutineScope = lifecycleScope,
            imageView = imagePreview,
            loadingProgress = loadingProgress,
            httpClient = client
        )

        imageManager.setListener(object : ImagePreviewManager.ImageStateListener {
            override fun onImageLoadStart() {
                // 隐藏图片，避免显示未定位的图片导致跳变
                imagePreview.visibility = View.INVISIBLE
                imagePreview.setImageDrawable(null)
                matrix.reset()
                baseMatrix.reset()
                currentScale = 1f
                imagePreview.imageMatrix = matrix
            }

            override fun onImageLoadSuccess(isGif: Boolean) {
                loadingProgress.visibility = View.GONE
                errorTextView.visibility = View.GONE
                // 延迟到下一帧，确保 ImageView 尺寸已确定，计算矩阵后再显示
                imagePreview.post {
                    applyFittedCenterMatrix()
                    imagePreview.visibility = View.VISIBLE
                    if (isGif) {
                        imageManager.startGifAnimation()
                    }
                }

                if (isGif) {
                    imageManager.startGifAnimation()
                }
            }

            override fun onImageLoadError(message: String) {
                loadingProgress.visibility = View.GONE
                showError(message)
            }

            override fun onDoubleTap() { /* 由手势检测器处理 */ }
        })
    }

    /**
     * 计算并应用初始居中适配矩阵（FIT_CENTER 效果）
     */
    private fun applyFittedCenterMatrix() {
        val drawable = imagePreview.drawable ?: return
        val dw = drawable.intrinsicWidth
        val dh = drawable.intrinsicHeight
        if (dw <= 0 || dh <= 0) return

        val vw = imagePreview.width - imagePreview.paddingLeft - imagePreview.paddingRight
        val vh = imagePreview.height - imagePreview.paddingTop - imagePreview.paddingBottom
        if (vw <= 0 || vh <= 0) return

        baseMatrix.reset()
        val scale = min(vw.toFloat() / dw, vh.toFloat() / dh)
        val dx = (vw - dw * scale) / 2f + imagePreview.paddingLeft
        val dy = (vh - dh * scale) / 2f + imagePreview.paddingTop
        baseMatrix.setScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        matrix.set(baseMatrix)
        imagePreview.imageMatrix = matrix
        currentScale = 1f
    }

    // ---------- 手势相关 ----------
    private fun setupGestureDetectors() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleDoubleTap(e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean = false // 让 onTouch 处理拖动
        })

        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    val focusX = detector.focusX
                    val focusY = detector.focusY

                    val newScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)
                    // 计算相对于当前矩阵的缩放变换
                    matrix.postScale(
                        newScale / currentScale,
                        newScale / currentScale,
                        focusX,
                        focusY
                    )
                    currentScale = newScale
                    imagePreview.imageMatrix = matrix
                    return true
                }
            })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        imagePreview.setOnTouchListener { _, event ->
            // 双指缩放优先
            scaleGestureDetector.onTouchEvent(event)
            // 双指缩放进行中时不处理单指手势
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleGestureDetector.isInProgress) return@setOnTouchListener true

                    // 放大超过 1.01 倍才允许拖动
                    if (event.pointerCount == 1 && currentScale > 1.01f) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        matrix.postTranslate(dx, dy)
                        imagePreview.imageMatrix = matrix
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        if (abs(dx) > 5 || abs(dy) > 5) {
                            isDragging = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 未放大且未拖动时，左右滑动可以切换图片
                    if (!isDragging && currentScale <= 1.01f) {
                        val diffX = event.rawX - touchStartX
                        if (abs(diffX) > SWIPE_THRESHOLD) {
                            if (diffX > 0) viewModel.navigateToPrevious()
                            else viewModel.navigateToNext()
                        }
                    }
                }
            }
            true
        }
    }

    private fun handleDoubleTap(focusX: Float, focusY: Float) {
        val targetScale = if (currentScale > 1.1f) minScale else 2.5f.coerceAtMost(maxScale)
        animateScale(currentScale, targetScale, focusX, focusY)
    }

    /**
     * 平滑缩放动画，以 (focusX, focusY) 为中心进行缩放
     */
    private fun animateScale(
        fromScale: Float,
        toScale: Float,
        pivotX: Float,
        pivotY: Float
    ) {
        val startMatrix = Matrix(matrix) // 克隆当前矩阵
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                val scaleThisFrame = fromScale + (toScale - fromScale) * fraction
                val scaleChange = scaleThisFrame / fromScale

                // 基于开始矩阵进行变换，避免累积误差
                val m = Matrix(startMatrix)
                m.postScale(scaleChange, scaleChange, pivotX, pivotY)
                imagePreview.imageMatrix = m
                matrix.set(m)
            }
        }.start()
        currentScale = toScale
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                viewModel.navigateToPrevious()
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                viewModel.navigateToNext()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ---------- 控制栏与事件 ----------
    private fun setupEventListeners() {
        backButton.setOnClickListener { onBackPressed() }
        shareButton.setOnClickListener { shareCurrentImage() }
        previousButton.setOnClickListener { viewModel.navigateToPrevious() }
        nextButton.setOnClickListener { viewModel.navigateToNext() }
    }

    // ImageActivity.kt
    private fun loadIntentData() {
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        var directoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""
        val imagePath = intent.getStringExtra("FILE_URL") ?: ""
        val filePath = intent.getStringExtra("FILE_PATH") ?: ""
        val sortBy = intent.getStringExtra("SORT_BY") ?: ""
        val sortOrder = intent.getStringExtra("SORT_ORDER") ?: ""

        // 如果 CURRENT_PATH 为空，则从 FILE_PATH 提取父目录
        if (directoryPath.isEmpty() && filePath.isNotEmpty()) {
            directoryPath = File(filePath).parent ?: ""
        }

        if (serverUrl.isEmpty()) {
            showError("服务器地址不能为空")
            return
        }
        viewModel.initialize(serverUrl, directoryPath, imagePath, sortBy, sortOrder)
    }

    @SuppressLint("SetTextI18n")
    private fun setupObservers() {
        viewModel.currentImage.observe(this) { imageItem ->
            imageItem?.let { item ->
                val currentIndex = (viewModel.currentIndex.value ?: 0) + 1
                val totalCount = viewModel.totalCount.value ?: 0
                fileNameWithCount.text = "$currentIndex/$totalCount  ${item.name}"

                val imageUrl = getFullImageUrl(item)
                imageManager.loadImage(imageUrl, item.name)

                updateNavigationButtons()
            }
        }

        viewModel.loadingState.observe(this) { state ->
            when (state) {
                is ImageViewModel.LoadingState.Loading -> {
                    loadingProgress.visibility = View.VISIBLE
                    errorTextView.visibility = View.GONE
                }
                is ImageViewModel.LoadingState.Success -> {
                    loadingProgress.visibility = View.GONE
                }
                is ImageViewModel.LoadingState.Error -> {
                    loadingProgress.visibility = View.GONE
                    showError(state.message)
                }
                else -> {}
            }
        }

        viewModel.errorState.observe(this) { error ->
            error?.let { showError(it) }
        }
    }

    private fun updateNavigationButtons() {
        val currentIndex = viewModel.currentIndex.value ?: 0
        val totalCount = viewModel.totalCount.value ?: 0
        previousButton.isEnabled = currentIndex > 0
        nextButton.isEnabled = currentIndex < totalCount - 1
    }

    private fun getFullImageUrl(item: FileSystemItem): String {
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
        return "${serverUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
    }

    private fun toggleControls() {
        isControlsVisible = !isControlsVisible
        topControlBar.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
        bottomControlBar.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        errorTextView.visibility = View.VISIBLE
        errorTextView.text = message
        imagePreview.visibility = View.GONE
    }

    // ---------- 分享功能 ----------
    private fun shareCurrentImage() {
        val item = viewModel.currentImage.value ?: return
        val imageUrl = getFullImageUrl(item)

        lifecycleScope.launch {
            try {
                val sharedFile = withContext(Dispatchers.IO) {
                    val client = UnsafeHttpClient.createUnsafeOkHttpClient()
                    val request = okhttp3.Request.Builder().url(imageUrl).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("下载图片失败")

                    val cacheDir = File(cacheDir, "shared_images")
                    cacheDir.mkdirs()
                    val file = File(cacheDir, item.name)
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    file
                }

                val uri = FileProvider.getUriForFile(
                    this@ImageActivity,
                    "${packageName}.fileprovider",
                    sharedFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = item.mimeType ?: "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享图片"))
            } catch (e: Exception) {
                Toast.makeText(this@ImageActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        imageManager.stopGifAnimation()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            delay(200)
            imageManager.startGifAnimation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageManager.clear()
    }
}