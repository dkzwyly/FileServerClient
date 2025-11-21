package com.dkc.fileserverclient

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class PreviewActivity : AppCompatActivity() {

    // UI 组件
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var downloadButton: Button
    private lateinit var fullscreenButton: Button

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
    private lateinit var videoPreview: VideoView
    private lateinit var videoLoadingProgress: ProgressBar
    private lateinit var videoControls: LinearLayout
    private lateinit var playPauseButton: Button
    private lateinit var videoSeekBar: SeekBar
    private lateinit var videoTimeTextView: TextView

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

    // 网络客户端
    private val client = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initViews()
        setupIntentData()
        setupEventListeners()
        loadPreview()
    }

    private fun initViews() {
        // 初始化所有视图组件
        titleBar = findViewById(R.id.titleBar)
        backButton = findViewById(R.id.backButton)
        fileNameTextView = findViewById(R.id.fileNameTextView)
        fileTypeTextView = findViewById(R.id.fileTypeTextView)
        downloadButton = findViewById(R.id.downloadButton)
        fullscreenButton = findViewById(R.id.fullscreenButton)

        imageContainer = findViewById(R.id.imageContainer)
        videoContainer = findViewById(R.id.videoContainer)
        textContainer = findViewById(R.id.textContainer)
        generalContainer = findViewById(R.id.generalContainer)
        errorTextView = findViewById(R.id.errorTextView)

        imagePreview = findViewById(R.id.imagePreview)
        imageLoadingProgress = findViewById(R.id.imageLoadingProgress)

        videoPreview = findViewById(R.id.videoPreview)
        videoLoadingProgress = findViewById(R.id.videoLoadingProgress)
        videoControls = findViewById(R.id.videoControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        videoTimeTextView = findViewById(R.id.videoTimeTextView)

        textPreview = findViewById(R.id.textPreview)
        textLoadingProgress = findViewById(R.id.textLoadingProgress)

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

        fullscreenButton.setOnClickListener {
            toggleFullscreen()
        }

        // 视频控制事件
        playPauseButton.setOnClickListener {
            toggleVideoPlayback()
        }

        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoPreview.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadPreview() {
        when (currentFileType) {
            "image" -> loadImagePreview()
            "video" -> loadVideoPreview()
            "text" -> loadTextPreview()
            else -> loadGeneralPreview()
        }
    }

    private fun loadImagePreview() {
        showContainer(imageContainer)
        fileTypeTextView.visibility = View.VISIBLE
        fullscreenButton.visibility = View.VISIBLE

        // 使用图片加载库（如Glide、Picasso）来加载图片
        // 这里简化处理，实际应用中应该使用专门的图片加载库

        coroutineScope.launch {
            try {
                // 模拟图片加载
                withContext(Dispatchers.IO) {
                    // 这里应该是实际的图片加载逻辑
                    Thread.sleep(1000) // 模拟加载延迟
                }

                // 图片加载完成
                imageLoadingProgress.visibility = View.GONE
                // 实际应用中这里应该设置图片
                // imagePreview.setImageBitmap(bitmap)

            } catch (e: Exception) {
                showError("图片加载失败: ${e.message}")
            }
        }
    }

    private fun loadVideoPreview() {
        showContainer(videoContainer)
        fileTypeTextView.visibility = View.VISIBLE
        fullscreenButton.visibility = View.VISIBLE
        videoControls.visibility = View.VISIBLE

        try {
            videoPreview.setVideoURI(Uri.parse(currentFileUrl))

            videoPreview.setOnPreparedListener { mp ->
                videoLoadingProgress.visibility = View.GONE
                videoSeekBar.max = mp.duration
                updateVideoTimeText(0, mp.duration)

                // 设置视频进度更新监听
                val handler = Handler(Looper.getMainLooper())
                handler.post(object : Runnable {
                    override fun run() {
                        if (videoPreview.isPlaying) {
                            val currentPosition = videoPreview.currentPosition
                            videoSeekBar.progress = currentPosition
                            updateVideoTimeText(currentPosition, videoPreview.duration)
                        }
                        handler.postDelayed(this, 1000)
                    }
                })
            }

            videoPreview.setOnCompletionListener {
                playPauseButton.text = "播放"
            }

            // 自动开始播放
            videoPreview.start()
            playPauseButton.text = "暂停"

        } catch (e: Exception) {
            showError("视频加载失败: ${e.message}")
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

    private fun enterFullscreen() {
        isFullscreen = true

        // 隐藏状态栏和导航栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    )
        }

        // 隐藏标题栏
        titleBar.visibility = View.GONE
        fileTypeTextView.visibility = View.GONE

        // 横屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 更新全屏按钮文本
        fullscreenButton.text = "退出全屏"
    }

    private fun exitFullscreen() {
        isFullscreen = false

        // 显示状态栏和导航栏
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // 显示标题栏
        titleBar.visibility = View.VISIBLE
        fileTypeTextView.visibility = View.VISIBLE

        // 竖屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 取消屏幕常亮
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 更新全屏按钮文本
        fullscreenButton.text = "全屏"
    }

    private fun toggleVideoPlayback() {
        if (videoPreview.isPlaying) {
            videoPreview.pause()
            playPauseButton.text = "播放"
        } else {
            videoPreview.start()
            playPauseButton.text = "暂停"
        }
    }

    private fun updateVideoTimeText(current: Int, total: Int) {
        val currentMinutes = current / 1000 / 60
        val currentSeconds = current / 1000 % 60
        val totalMinutes = total / 1000 / 60
        val totalSeconds = total / 1000 % 60

        videoTimeTextView.text = String.format(
            "%02d:%02d / %02d:%02d",
            currentMinutes, currentSeconds, totalMinutes, totalSeconds
        )
    }

    private fun downloadFile() {
        // 实现文件下载逻辑
        Toast.makeText(this, "开始下载: $currentFileName", Toast.LENGTH_SHORT).show()

        // 这里可以调用系统的下载管理器或自定义下载逻辑
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

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()

        // 释放视频资源
        if (videoPreview.isPlaying) {
            videoPreview.stopPlayback()
        }
    }
}