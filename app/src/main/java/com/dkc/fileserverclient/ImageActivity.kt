// ImageActivity.kt
package com.dkc.fileserverclient

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.KeyEvent

class ImageActivity : AppCompatActivity() {

    private lateinit var viewModel: ImageViewModel

    // UI组件
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var imageCountTextView: TextView
    private lateinit var downloadButton: Button

    private lateinit var imagePreview: ImageView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var navigationContainer: LinearLayout
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton

    // 管理器
    private lateinit var imageManager: ImagePreviewManager
    private lateinit var gestureDetector: GestureDetector

    // 状态
    private var isControlsVisible = true

    companion object {
        private const val TAG = "ImageActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)

        // 初始化ViewModel
        viewModel = ViewModelProvider(this)[ImageViewModel::class.java]

        initViews()
        setupImageManager()
        setupGestureDetector()
        setupEventListeners()
        loadIntentData()
        setupObservers()
    }

    private fun initViews() {
        titleBar = findViewById(R.id.titleBar)
        backButton = findViewById(R.id.backButton)
        fileNameTextView = findViewById(R.id.fileNameTextView)
        imageCountTextView = findViewById(R.id.imageCountTextView)
        downloadButton = findViewById(R.id.downloadButton)

        imagePreview = findViewById(R.id.imagePreview)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        navigationContainer = findViewById(R.id.navigationContainer)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
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
                // 图片开始加载
            }

            override fun onImageLoadSuccess(isGif: Boolean) {
                loadingProgress.visibility = View.GONE
                errorTextView.visibility = View.GONE
                imagePreview.visibility = View.VISIBLE

                // 自动播放GIF
                if (isGif) {
                    imageManager.startGifAnimation()
                }
            }

            override fun onImageLoadError(message: String) {
                loadingProgress.visibility = View.GONE
                showError(message)
            }

            override fun onDoubleTap() {
                handleDoubleTap()
            }
        })
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleDoubleTap()
                return true
            }
        })

        imagePreview.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }



    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                viewModel.navigateToPrevious()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                viewModel.navigateToNext()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun setupEventListeners() {
        backButton.setOnClickListener {
            onBackPressed()
        }

        downloadButton.setOnClickListener {
            downloadCurrentImage()
        }

        previousButton.setOnClickListener {
            viewModel.navigateToPrevious()
        }

        nextButton.setOnClickListener {
            viewModel.navigateToNext()
        }

        // 添加左右滑动手势
        imagePreview.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private val SWIPE_THRESHOLD = 100f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)

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
                                // 向右滑动 - 上一张图片
                                viewModel.navigateToPrevious()
                            } else {
                                // 向左滑动 - 下一张图片
                                viewModel.navigateToNext()
                            }
                            return true
                        }
                    }
                }
                return true
            }
        })
    }

    private fun loadIntentData() {
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        val directoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""
        val imagePath = intent.getStringExtra("FILE_URL") ?: ""

        if (serverUrl.isEmpty()) {
            showError("服务器地址不能为空")
            return
        }

        viewModel.initialize(serverUrl, directoryPath, imagePath)
    }

    @SuppressLint("SetTextI18n")
    private fun setupObservers() {
        viewModel.currentImage.observe(this) { imageItem ->
            imageItem?.let { item ->
                // 更新文件名
                fileNameTextView.text = item.name

                // 加载图片
                val imageUrl = getFullImageUrl(item)
                imageManager.loadImage(imageUrl, item.name)

                // 更新计数显示
                val currentIndex = (viewModel.currentIndex.value ?: 0) + 1
                val totalCount = viewModel.totalCount.value ?: 0
                imageCountTextView.text = "$currentIndex/$totalCount"

                // 更新导航按钮状态
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
            error?.let {
                showError(it)
            }
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

        if (isControlsVisible) {
            titleBar.visibility = View.VISIBLE
            navigationContainer.visibility = View.VISIBLE
        } else {
            titleBar.visibility = View.GONE
            navigationContainer.visibility = View.GONE
        }
    }

    private fun handleDoubleTap() {
        // 双击可以切换缩放模式或执行其他操作
        // 这里可以添加双击缩放功能
        Log.d(TAG, "Double tap detected")
    }

    private fun downloadCurrentImage() {
        viewModel.currentImage.value?.let { item ->
            val fileName = item.name
            val imageUrl = getFullImageUrl(item)

            Toast.makeText(this, "开始下载: $fileName", Toast.LENGTH_SHORT).show()
            // TODO: 实现下载逻辑
        }
    }

    private fun showError(message: String) {
        errorTextView.visibility = View.VISIBLE
        errorTextView.text = message
        imagePreview.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        imageManager.stopGifAnimation()
    }

    override fun onResume() {
        super.onResume()
        // 恢复GIF动画
        lifecycleScope.launch {
            // 给一点延迟确保UI完全恢复
            kotlinx.coroutines.delay(200)
            imageManager.startGifAnimation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageManager.clear()
    }
}