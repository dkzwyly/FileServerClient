package com.dkc.fileserverclient

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class TextPreviewActivity : AppCompatActivity() {

    private lateinit var textContentTextView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var rootLayout: RelativeLayout
    private lateinit var chapterButton: ImageButton
    private lateinit var statusLabel: TextView

    private lateinit var viewModel: TextPreviewViewModel
    private lateinit var gestureDetector: GestureDetector

    private val isFirstLayoutComplete = AtomicBoolean(false)
    private var linesPerPage = 20
    private var lastClickTime = 0L
    private val minClickInterval = 200L  // 进一步缩短到200ms

    // 本地历史记录文件路径
    private lateinit var readingHistoryFile: File

    // 用于存储当前文件信息
    private lateinit var currentFileName: String
    private lateinit var currentFileUrl: String
    private lateinit var currentFilePath: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_preview)

        initViews()
        setupIntentData()
        initViewModel()
        setupGestureDetector()
        setupLayoutListener()
        setupObservers()

        // 先加载历史记录，再计算分页和加载内容
        loadReadingHistory()
        calculateLinesPerPageAndLoad()
    }

    private fun initViews() {
        textContentTextView = findViewById(R.id.textContentTextView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        pageIndicator = findViewById(R.id.pageIndicator)
        rootLayout = findViewById(R.id.rootLayout)
        chapterButton = findViewById(R.id.chapterButton)
        statusLabel = findViewById(R.id.statusLabel)

        supportActionBar?.hide()
        textContentTextView.isScrollContainer = false

        // 章节按钮：保持正常大小但完全透明
        // 1. 保持按钮原始大小（48x48dp），不修改布局参数
        // 2. 完全透明化按钮
        chapterButton.setBackgroundResource(android.R.color.transparent)
        chapterButton.setImageResource(android.R.color.transparent)
        chapterButton.alpha = 0.0f  // 完全透明

        // 3. 将按钮提到最前面，确保它不会被其他视图遮挡
        chapterButton.bringToFront()

        // 4. 设置点击事件
        chapterButton.setOnClickListener {
            showChapterDialog()
        }

        // 5. 确保按钮可见（虽然透明）
        chapterButton.isVisible = true

        // 设置页面指示器
        pageIndicator.textSize = 12f
        pageIndicator.setBackgroundColor(Color.TRANSPARENT)
        pageIndicator.setTextColor(Color.parseColor("#666666"))
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFilePath = intent.getStringExtra("FILE_PATH") ?: ""

        Log.d("TextPreview", "初始化文件信息: 文件名=$currentFileName, URL=$currentFileUrl, 路径=$currentFilePath")

        // 创建历史记录目录和文件
        val historyDir = File(filesDir, "reading_history")
        if (!historyDir.exists()) {
            historyDir.mkdirs()
            Log.d("TextPreview", "创建历史记录目录: ${historyDir.absolutePath}")
        }

        // 使用更简单的文件名，避免特殊字符问题
        val safeFileName = currentFileName.replace("[^a-zA-Z0-9]".toRegex(), "_")
        readingHistoryFile = File(historyDir, "history_${safeFileName}.dat")
        Log.d("TextPreview", "历史记录文件: ${readingHistoryFile.absolutePath}, 存在: ${readingHistoryFile.exists()}")
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this).get(TextPreviewViewModel::class.java)
        // 传递文件信息给ViewModel
        viewModel.initialize(currentFileName, currentFileUrl, currentFilePath)
    }

    private fun setupObservers() {
        // 观察页面内容
        viewModel.pageContent.observe(this) { content ->
            textContentTextView.text = content
            Log.d("TextPreview", "页面内容更新: ${content.length} 字符")
        }

        // 观察页面信息
        viewModel.pageInfo.observe(this) { pageInfo ->
            val progressText = "${pageInfo.currentPage}/${pageInfo.totalPages} (${pageInfo.progress}%)"
            pageIndicator.text = progressText
            Log.d("TextPreview", "页面信息更新: $progressText")
        }

        // 观察加载状态
        viewModel.loadingState.observe(this) { loadingState ->
            if (loadingState.isLoading) {
                showLoadingState(loadingState.message)
                Log.d("TextPreview", "显示加载状态: ${loadingState.message}")
            } else {
                showContentState()
                Log.d("TextPreview", "显示内容状态")
            }
        }

        // 观察错误信息
        viewModel.errorMessage.observe(this) { errorMessage ->
            if (errorMessage != null) {
                showErrorState(errorMessage)
                Log.e("TextPreview", "显示错误状态: $errorMessage")
            }
        }

        // 观察章节列表
        viewModel.chapters.observe(this) { chapters ->
            if (chapters.isNotEmpty()) {
                Log.d("TextPreview", "获取到章节列表: ${chapters.size} 个章节")
                showChapterList(chapters)
            } else {
                Log.d("TextPreview", "无章节数据")
                showNoChaptersDialog()
            }
        }

        // 观察当前页面变化，自动保存历史记录
        viewModel.currentPageState.observe(this) { pageState ->
            pageState?.let {
                Log.d("TextPreview", "页面状态变化: 服务器页=${it.serverPage}, 客户端页=${it.clientPage}")
                saveReadingHistory(it.serverPage, it.clientPage)
            }
        }
    }

    private fun setupLayoutListener() {
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            if (!isFirstLayoutComplete.get()) {
                Log.d("TextPreview", "首次布局完成，textView高度: ${textContentTextView.height}")
                isFirstLayoutComplete.set(true)
                calculateLinesPerPageAndLoad()
            }
        }
    }

    private fun calculateLinesPerPageAndLoad() {
        if (isFirstLayoutComplete.get()) {
            linesPerPage = calculateMaxLines()
            Log.d("TextPreview", "开始加载内容，每页行数: $linesPerPage")
            // 先从ViewModel获取历史记录，然后加载内容
            viewModel.loadTextContent(linesPerPage)
        }
    }

    private fun calculateMaxLines(): Int {
        return try {
            if (textContentTextView.height == 0) {
                textContentTextView.measure(0, 0)
            }

            val height = textContentTextView.measuredHeight
            val lineHeight = textContentTextView.lineHeight
            val paddingTop = textContentTextView.paddingTop
            val paddingBottom = textContentTextView.paddingBottom
            val availableHeight = height - paddingTop - paddingBottom
            val maxLines = (availableHeight / lineHeight).toInt()
            val safeMaxLines = (maxLines - 2).coerceAtLeast(1)

            Log.d("TextPreview", "计算最大行数: 高度=$height, 行高=$lineHeight, 可用高度=$availableHeight, 安全行数=$safeMaxLines")
            safeMaxLines
        } catch (e: Exception) {
            Log.e("TextPreview", "计算最大行数失败", e)
            18 // 默认值
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < minClickInterval) {
                    return true
                }
                lastClickTime = currentTime

                val screenWidth = resources.displayMetrics.widthPixels
                val x = e.x

                // 检查是否点击在章节按钮区域（右上角48x48dp区域）
                val chapterButtonRect = android.graphics.Rect(
                    screenWidth - 150,  // 150像素约为48dp
                    0,
                    screenWidth,
                    150
                )

                // 如果点击在章节按钮区域，让按钮处理点击
                if (chapterButtonRect.contains(x.toInt(), e.y.toInt())) {
                    Log.d("TextPreview", "点击在章节按钮区域")
                    return false  // 返回false，让按钮处理点击
                }

                // 否则处理翻页
                if (x < screenWidth / 3) {
                    Log.d("TextPreview", "点击左侧，上一页")
                    viewModel.previousPage()
                    return true
                } else if (x > screenWidth * 2 / 3) {
                    Log.d("TextPreview", "点击右侧，下一页")
                    viewModel.nextPage()
                    return true
                }
                return false
            }
        })

        // 直接监听触摸事件，简化手势处理
        rootLayout.setOnTouchListener { _, event ->
            // 处理触摸事件
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录触摸位置，但不立即处理
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    val currentTime = System.currentTimeMillis()
                    // 如果距离上次点击时间太短，不处理
                    if (currentTime - lastClickTime < minClickInterval) {
                        return@setOnTouchListener true
                    }
                    lastClickTime = currentTime

                    val screenWidth = resources.displayMetrics.widthPixels
                    val x = event.x
                    val y = event.y

                    // 检查是否点击在章节按钮区域（右上角48x48dp区域）
                    val chapterButtonRect = android.graphics.Rect(
                        screenWidth - 150,  // 150像素约为48dp
                        0,
                        screenWidth,
                        150
                    )

                    // 如果点击在章节按钮区域，触发章节按钮点击
                    if (chapterButtonRect.contains(x.toInt(), y.toInt())) {
                        Log.d("TextPreview", "点击在章节按钮区域，触发章节按钮")
                        chapterButton.performClick()
                        return@setOnTouchListener true
                    }

                    // 否则处理翻页
                    if (x < screenWidth / 3) {
                        // 点击左侧1/3区域，上一页
                        Log.d("TextPreview", "点击左侧区域，上一页")
                        viewModel.previousPage()
                        return@setOnTouchListener true
                    } else if (x > screenWidth * 2 / 3) {
                        // 点击右侧1/3区域，下一页
                        Log.d("TextPreview", "点击右侧区域，下一页")
                        viewModel.nextPage()
                        return@setOnTouchListener true
                    }
                    // 中间区域不处理翻页，但允许其他操作
                }
            }

            // 将事件传递给GestureDetector处理其他手势
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun showChapterDialog() {
        Log.d("TextPreview", "显示章节对话框")
        statusLabel.isVisible = true
        statusLabel.text = "正在从服务器加载章节..."

        CoroutineScope(Dispatchers.Main).launch {
            viewModel.loadChapters()
        }
    }

    private fun showChapterList(chapters: List<TextPreviewViewModel.ChapterInfo>) {
        val chapterTitles = chapters.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("章节跳转 (${chapters.size}章)")
            .setItems(chapterTitles) { _, which ->
                val chapter = chapters[which]
                Log.d("TextPreview", "跳转到章节: ${chapter.title}, 服务器页: ${chapter.serverPage}, 客户端页: ${chapter.clientPage}")
                viewModel.jumpToChapter(chapter)
                Toast.makeText(this, "跳转到: ${chapter.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNoChaptersDialog() {
        AlertDialog.Builder(this)
            .setTitle("未发现章节")
            .setMessage("该文件可能没有章节标记，或者章节索引尚未构建。")
            .setPositiveButton("确定", null)
            .show()
    }

    // UI状态管理
    private fun showLoadingState(message: String? = null) {
        loadingProgress.isVisible = true
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = false
        // 章节按钮保持透明但可见
        chapterButton.isVisible = true
        statusLabel.isVisible = true
        statusLabel.text = message ?: "正在加载..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        pageIndicator.isVisible = true
        errorTextView.isVisible = false
        // 章节按钮保持透明但可见
        chapterButton.isVisible = true
        statusLabel.isVisible = false
    }

    private fun showErrorState(message: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = message
        // 章节按钮保持透明但可见
        chapterButton.isVisible = true
        statusLabel.isVisible = false
    }

    // 修复：本地历史记录方法
    private fun loadReadingHistory() {
        Log.d("TextPreview", "尝试加载历史记录: ${readingHistoryFile.absolutePath}")

        if (readingHistoryFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(readingHistoryFile)).use { ois ->
                    val history = ois.readObject() as? ReadingHistory
                    history?.let {
                        Log.d("TextPreview", "找到历史记录: 文件名=${it.fileName}, 服务器页=${it.serverPage}, 客户端页=${it.clientPage}")

                        // 检查是否是同一文件（比较文件名或URL）
                        if (it.fileName == currentFileName || it.fileUrl == currentFileUrl) {
                            Log.d("TextPreview", "恢复历史记录: 服务器页=${it.serverPage}, 客户端页=${it.clientPage}")
                            viewModel.restoreFromHistory(it.serverPage, it.clientPage)
                        } else {
                            Log.d("TextPreview", "文件名不匹配，不使用历史记录")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TextPreview", "加载历史记录失败", e)
            }
        } else {
            Log.d("TextPreview", "历史记录文件不存在")
        }
    }

    private fun saveReadingHistory(serverPage: Int, clientPage: Int) {
        Log.d("TextPreview", "保存历史记录: 服务器页=$serverPage, 客户端页=$clientPage")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val history = ReadingHistory(
                    fileName = currentFileName,
                    fileUrl = currentFileUrl,
                    serverPage = serverPage,
                    clientPage = clientPage,
                    timestamp = System.currentTimeMillis()
                )

                ObjectOutputStream(FileOutputStream(readingHistoryFile)).use { oos ->
                    oos.writeObject(history)
                }

                Log.d("TextPreview", "历史记录保存成功: ${readingHistoryFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("TextPreview", "保存历史记录失败", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("TextPreview", "Activity暂停，保存当前状态")

        // 获取当前页面状态并保存
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.serverPage, it.clientPage)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TextPreview", "Activity销毁")

        // 获取当前页面状态并保存
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.serverPage, it.clientPage)
        }
    }
}

// 历史记录数据类
data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val serverPage: Int,
    val clientPage: Int,
    val timestamp: Long
) : java.io.Serializable