package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ImageActivity : AppCompatActivity() {

    private lateinit var viewModel: ImageViewModel
    private lateinit var mediaPager: ViewPager2
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var topControlBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var fileNameWithCount: TextView
    private lateinit var shareButton: ImageButton
    private lateinit var bottomControlBar: LinearLayout
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton

    private var adapter: MediaPagerAdapter? = null
    private var isControlsVisible = false
    private var isProgrammaticScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)

        viewModel = ViewModelProvider(this)[ImageViewModel::class.java]

        initViews()
        setupEventListeners()
        setupObservers()
        loadIntentData()
    }

    private fun initViews() {
        mediaPager = findViewById(R.id.mediaPager)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        topControlBar = findViewById(R.id.topControlBar)
        backButton = findViewById(R.id.backButton)
        fileNameWithCount = findViewById(R.id.fileNameWithCount)
        shareButton = findViewById(R.id.shareButton)
        bottomControlBar = findViewById(R.id.bottomControlBar)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)

        topControlBar.visibility = View.GONE
        bottomControlBar.visibility = View.GONE

        mediaPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (isProgrammaticScroll) {
                    isProgrammaticScroll = false
                    return
                }
                adapter?.setCurrentPage(position)
                viewModel.navigateToIndex(position)
                updateTitleAndNav()
            }
        })
    }

    private fun setupEventListeners() {
        backButton.setOnClickListener { finish() }
        shareButton.setOnClickListener { shareCurrentMedia() }
        previousButton.setOnClickListener { viewModel.navigateToPrevious() }
        nextButton.setOnClickListener { viewModel.navigateToNext() }
    }

    private fun setupObservers() {
        viewModel.imageList.observe(this) { list ->
            if (list.isNullOrEmpty()) return@observe
            buildAdapterIfNeeded(list)
        }

        viewModel.currentIndex.observe(this) { index ->
            if (index == null || adapter == null) return@observe
            if (mediaPager.currentItem != index) {
                isProgrammaticScroll = true
                mediaPager.setCurrentItem(index, false)
                adapter?.setCurrentPage(index)
            }
            updateTitleAndNav()
        }

        viewModel.loadingState.observe(this) { state ->
            when (state) {
                is ImageViewModel.LoadingState.Loading -> loadingProgress.visibility = View.VISIBLE
                is ImageViewModel.LoadingState.Success -> loadingProgress.visibility = View.GONE
                is ImageViewModel.LoadingState.Error -> {
                    loadingProgress.visibility = View.GONE
                    errorTextView.visibility = View.VISIBLE
                    errorTextView.text = state.message
                }
                else -> loadingProgress.visibility = View.GONE
            }
        }

        viewModel.errorState.observe(this) { error ->
            if (!error.isNullOrEmpty()) {
                errorTextView.visibility = View.VISIBLE
                errorTextView.text = error
            }
        }
    }

    private fun buildAdapterIfNeeded(list: List<FileSystemItem>) {
        if (adapter != null) return
        val serverUrl = viewModel.getServerUrl()
        adapter = MediaPagerAdapter(
            items = list,
            serverUrl = serverUrl,
            onClickPage = { toggleControls() }
        )
        mediaPager.adapter = adapter
        val startIndex = (viewModel.currentIndex.value ?: 0).coerceIn(0, list.size - 1)
        mediaPager.setCurrentItem(startIndex, false)
        adapter?.setCurrentPage(startIndex)
        updateTitleAndNav()
    }

    private fun loadIntentData() {
        val serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        val mediaList = intent.getStringArrayListExtra("IMAGE_LIST")
        val currentIndex = intent.getIntExtra("CURRENT_INDEX", -1)

        if (serverUrl.isEmpty()) {
            errorTextView.visibility = View.VISIBLE
            errorTextView.text = "服务器地址不能为空"
            return
        }

        if (mediaList != null && currentIndex >= 0 && currentIndex < mediaList.size) {
            viewModel.initializeWithPaths(serverUrl, mediaList, currentIndex)
        } else {
            var directoryPath = intent.getStringExtra("CURRENT_PATH") ?: ""
            val filePath = intent.getStringExtra("FILE_PATH") ?: ""
            val sortBy = intent.getStringExtra("SORT_BY") ?: ""
            val sortOrder = intent.getStringExtra("SORT_ORDER") ?: ""

            if (directoryPath.isEmpty() && filePath.isNotEmpty()) {
                directoryPath = File(filePath).parent ?: ""
            }
            viewModel.initialize(serverUrl, directoryPath, filePath, sortBy, sortOrder)
        }
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun updateTitleAndNav() {
        val items = viewModel.imageList.value ?: return
        val index = viewModel.currentIndex.value ?: 0
        if (index !in items.indices) return
        val item = items[index]
        fileNameWithCount.text = "${index + 1}/${items.size}  ${item.name}"

        previousButton.isEnabled = index > 0
        nextButton.isEnabled = index < items.size - 1
        previousButton.alpha = if (index > 0) 1f else 0.4f
        nextButton.alpha = if (index < items.size - 1) 1f else 0.4f
    }

    private fun toggleControls() {
        isControlsVisible = !isControlsVisible
        topControlBar.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
        bottomControlBar.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
    }

    private fun shareCurrentMedia() {
        val items = viewModel.imageList.value ?: return
        val index = viewModel.currentIndex.value ?: return
        if (index !in items.indices) return
        val item = items[index]

        val base = viewModel.getServerUrl().removeSuffix("/")
        val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
        val url = if (item.isVideo) {
            "$base/api/fileserver/stream/$encoded"
        } else {
            "$base/api/fileserver/preview/$encoded"
        }

        lifecycleScope.launch {
            try {
                val sharedFile = withContext(Dispatchers.IO) {
                    val client = UnsafeHttpClient.createUnsafeOkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("下载失败")

                    val dir = File(cacheDir, "shared_media").apply { mkdirs() }
                    val file = File(dir, item.name)
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(file).use { out -> input.copyTo(out) }
                    }
                    file
                }

                val uri = FileProvider.getUriForFile(
                    this@ImageActivity,
                    "${packageName}.fileprovider",
                    sharedFile
                )
                val mime = when {
                    item.isVideo -> item.mimeType?.takeIf { it.startsWith("video/") } ?: "video/*"
                    else -> item.mimeType?.takeIf { it.startsWith("image/") } ?: "image/*"
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "分享"))
            } catch (e: Exception) {
                Toast.makeText(this@ImageActivity, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}