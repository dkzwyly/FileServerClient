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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var settingsButton: ImageButton
    private lateinit var audioButton: ImageButton

    private lateinit var viewModel: TextPreviewViewModel
    private lateinit var gestureDetector: GestureDetector

    private val isFirstLayoutComplete = AtomicBoolean(false)
    private var linesPerPage = 20
    private var lastClickTime = 0L
    private val minClickInterval = 200L

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

        // 处理系统窗口内边距，确保底部不被导航栏遮挡
        applyWindowInsets()

        // 应用保存的显示设置
        applyDisplaySettings()

        // 加载历史记录
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
        settingsButton = findViewById(R.id.settingsButton)
        audioButton = findViewById(R.id.audioButton)

        supportActionBar?.hide()
        textContentTextView.isScrollContainer = false

        // 章节按钮（右上角透明）
        chapterButton.setBackgroundResource(android.R.color.transparent)
        chapterButton.setImageResource(android.R.color.transparent)
        chapterButton.alpha = 0.0f
        chapterButton.bringToFront()
        chapterButton.setOnClickListener {
            showChapterDialog()
        }
        chapterButton.isVisible = true

        // 底部透明按钮
        setupTransparentButton(settingsButton) {
            showDisplaySettingsDialog()
        }
        setupTransparentButton(audioButton) {
            Toast.makeText(this, "听书功能即将上线", Toast.LENGTH_SHORT).show()
        }

        pageIndicator.textSize = 12f
        pageIndicator.setBackgroundColor(Color.TRANSPARENT)
        pageIndicator.setTextColor(Color.parseColor("#666666"))
    }

    private fun setupTransparentButton(button: ImageButton, onClick: () -> Unit) {
        button.setBackgroundResource(android.R.color.transparent)
        button.setImageResource(android.R.color.transparent)
        button.alpha = 0.0f
        button.isVisible = true
        button.setOnClickListener { onClick() }
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

        val safeFileName = currentFileName.replace("[^a-zA-Z0-9]".toRegex(), "_")
        readingHistoryFile = File(historyDir, "history_${safeFileName}.dat")
        Log.d("TextPreview", "历史记录文件: ${readingHistoryFile.absolutePath}, 存在: ${readingHistoryFile.exists()}")
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this).get(TextPreviewViewModel::class.java)
        viewModel.initialize(currentFileName, currentFileUrl, currentFilePath)
    }

    private fun setupObservers() {
        viewModel.pageContent.observe(this) { content ->
            textContentTextView.text = content
            Log.d("TextPreview", "页面内容更新: ${content.length} 字符")
        }

        viewModel.pageInfo.observe(this) { pageInfo ->
            val progressText = "${pageInfo.currentPage}/${pageInfo.totalPages} (${pageInfo.progress}%)"
            pageIndicator.text = progressText
            Log.d("TextPreview", "页面信息更新: $progressText")
        }

        viewModel.loadingState.observe(this) { loadingState ->
            if (loadingState.isLoading) {
                showLoadingState(loadingState.message)
                Log.d("TextPreview", "显示加载状态: ${loadingState.message}")
            } else {
                showContentState()
                Log.d("TextPreview", "显示内容状态")
            }
        }

        viewModel.errorMessage.observe(this) { errorMessage ->
            if (errorMessage != null) {
                showErrorState(errorMessage)
                Log.e("TextPreview", "显示错误状态: $errorMessage")
            }
        }

        viewModel.chapters.observe(this) { chapters ->
            if (chapters.isNotEmpty()) {
                Log.d("TextPreview", "获取到章节列表: ${chapters.size} 个章节")
                showChapterList(chapters)
            } else {
                Log.d("TextPreview", "无章节数据")
                showNoChaptersDialog()
            }
        }

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
            18
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

                // 章节按钮区域（右上角）
                val chapterButtonRect = android.graphics.Rect(
                    screenWidth - 150,
                    0,
                    screenWidth,
                    150
                )
                if (chapterButtonRect.contains(x.toInt(), e.y.toInt())) {
                    Log.d("TextPreview", "点击在章节按钮区域")
                    return false
                }

                // 翻页区域
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

        rootLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime < minClickInterval) {
                        return@setOnTouchListener true
                    }
                    lastClickTime = currentTime

                    val screenWidth = resources.displayMetrics.widthPixels
                    val x = event.x
                    val y = event.y

                    // 章节按钮区域（右上角）
                    val chapterButtonRect = android.graphics.Rect(
                        screenWidth - 150,
                        0,
                        screenWidth,
                        150
                    )
                    if (chapterButtonRect.contains(x.toInt(), y.toInt())) {
                        Log.d("TextPreview", "点击在章节按钮区域，触发章节按钮")
                        chapterButton.performClick()
                        return@setOnTouchListener true
                    }

                    // 翻页
                    if (x < screenWidth / 3) {
                        Log.d("TextPreview", "点击左侧区域，上一页")
                        viewModel.previousPage()
                        return@setOnTouchListener true
                    } else if (x > screenWidth * 2 / 3) {
                        Log.d("TextPreview", "点击右侧区域，下一页")
                        viewModel.nextPage()
                        return@setOnTouchListener true
                    }
                }
            }
            gestureDetector.onTouchEvent(event)
        }
    }

    // ---------- 显示设置 ----------
    private fun showDisplaySettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setItems(arrayOf("字体大小", "背景颜色")) { _, which ->
                when (which) {
                    0 -> showFontSizeDialog()
                    1 -> showBackgroundColorDialog()
                }
            }
            .show()
    }

    private fun showFontSizeDialog() {
        val currentSize = ReadingSettings.getFontSize(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }

        val previewText = TextView(this).apply {
            text = "预览字体大小：${currentSize.toInt()}sp"
            textSize = currentSize
            setPadding(0, 0, 0, 20)
            gravity = android.view.Gravity.CENTER
        }

        val seekBar = SeekBar(this).apply {
            max = 30
            progress = currentSize.toInt()
        }

        val rangeLabel = TextView(this).apply {
            text = "10sp                             30sp"
            setPadding(0, 0, 0, 10)
            textSize = 11f
            gravity = android.view.Gravity.CENTER
        }

        layout.addView(previewText)
        layout.addView(seekBar)
        layout.addView(rangeLabel)

        // 实时改变字号并保存，但不重新分页
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val selectedSize = progress.coerceAtLeast(10).toFloat()
                previewText.text = "预览字体大小：${selectedSize.toInt()}sp"
                previewText.textSize = selectedSize
                // 实时应用到正文
                textContentTextView.textSize = selectedSize
                ReadingSettings.setFontSize(this@TextPreviewActivity, selectedSize)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("调整字体大小")
            .setView(layout)
            .setCancelable(true)   // 允许点击外部关闭
            .create()

        // 对话框关闭时，重新计算分页使内容适配新字号
        dialog.setOnDismissListener {
            recalculatePagingAfterSettingsChange()
        }

        dialog.show()
    }

    private fun showBackgroundColorDialog() {
        val colors = arrayOf("纯白", "护眼米色", "深色", "纯黑")
        val bgValues = arrayOf(
            ReadingSettings.BG_WHITE,
            ReadingSettings.BG_EYE_CARE,
            ReadingSettings.BG_DARK,
            ReadingSettings.BG_BLACK
        )
        val currentColor = ReadingSettings.getBackgroundColor(this)
        val checked = bgValues.indexOfFirst { it == currentColor }.let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("选择背景颜色")
            .setSingleChoiceItems(colors, checked) { dialog, which ->
                val bgColor = bgValues[which]
                ReadingSettings.setBackgroundColor(this, bgColor)
                applyBackgroundColor(bgColor)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyDisplaySettings() {
        val fontSize = ReadingSettings.getFontSize(this)
        textContentTextView.textSize = fontSize

        val bgColor = ReadingSettings.getBackgroundColor(this)
        applyBackgroundColor(bgColor)
    }

    private fun applyBackgroundColor(bgColor: Int) {
        rootLayout.setBackgroundColor(bgColor)
        val textColor = ReadingSettings.getTextColorForBg(bgColor)
        textContentTextView.setTextColor(textColor)
        pageIndicator.setTextColor(textColor)
    }

    /**
     * 字体大小变化后需要重新测量并重新加载内容分页
     */
    private fun recalculatePagingAfterSettingsChange() {
        rootLayout.post {
            linesPerPage = calculateMaxLines()
            Log.d("TextPreview", "设置改变后重新计算，每页行数: $linesPerPage")
            viewModel.loadTextContent(linesPerPage)
        }
    }

    // ---------- 系统窗口内边距处理 ----------
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 为底部栏添加底部内边距，防止被导航栏遮挡
            val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)
            bottomBar.setPadding(
                bottomBar.paddingLeft,
                bottomBar.paddingTop,
                bottomBar.paddingRight,
                systemBars.bottom
            )
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                0 // 底部内边距已由 bottomBar 处理
            )
            insets
        }
    }

    // ---------- 章节对话框 ----------
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
        chapterButton.isVisible = true
        statusLabel.isVisible = true
        statusLabel.text = message ?: "正在加载..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        pageIndicator.isVisible = true
        errorTextView.isVisible = false
        chapterButton.isVisible = true
        statusLabel.isVisible = false
    }

    private fun showErrorState(message: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = message
        chapterButton.isVisible = true
        statusLabel.isVisible = false
    }

    // 历史记录
    private fun loadReadingHistory() {
        Log.d("TextPreview", "尝试加载历史记录: ${readingHistoryFile.absolutePath}")

        if (readingHistoryFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(readingHistoryFile)).use { ois ->
                    val history = ois.readObject() as? ReadingHistory
                    history?.let {
                        Log.d("TextPreview", "找到历史记录: 文件名=${it.fileName}, 服务器页=${it.serverPage}, 客户端页=${it.clientPage}")

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
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.serverPage, it.clientPage)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TextPreview", "Activity销毁")
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.serverPage, it.clientPage)
        }
    }
}

// 历史记录数据类（必须与 Activity 在同一个文件中）
data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val serverPage: Int,
    val clientPage: Int,
    val timestamp: Long
) : java.io.Serializable