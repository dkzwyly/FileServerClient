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
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class TextPreviewActivity : AppCompatActivity() {

    private lateinit var textContentTextView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var rootLayout: RelativeLayout
    private lateinit var chapterButton: ImageButton
    private lateinit var statusLabel: TextView

    private var currentFileUrl = ""
    private var currentFileName = ""
    private var currentServerPage = 1
    private var totalServerPages = 1
    private var currentClientPage = 1
    private var totalClientPages = 1
    private var totalLines = 0
    private var fullContent = ""
    private var linesPerPage = 20
    private var isChapterIndexBuilt = false

    // 防重复点击
    private var lastClickTime = 0L
    private val minClickInterval = 500L

    // 双次加载控制
    private val isFirstLayoutComplete = AtomicBoolean(false)
    private val isContentLoaded = AtomicBoolean(false)
    private var retryCount = 0
    private val maxRetryCount = 3

    // 章节相关
    private val chapterList = mutableListOf<ChapterInfo>()
    private val chapterIndexFile by lazy { File(filesDir, "chapter_index_${currentFileName.hashCode()}.dat") }
    private val readingHistoryFile by lazy { File(filesDir, "reading_history_${currentFileName.hashCode()}.dat") }

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()
    private lateinit var gestureDetector: GestureDetector

    // 章节信息数据类
    data class ChapterInfo(
        val title: String,
        val serverPage: Int,
        val clientPage: Int,
        val lineNumber: Int
    ) : Serializable

    // 阅读历史数据类
    data class ReadingHistory(
        val fileName: String,
        val fileUrl: String,
        val serverPage: Int,
        val clientPage: Int,
        val timestamp: Long
    ) : Serializable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_preview)

        initViews()
        setupIntentData()
        setupGestureDetector()

        // 设置布局完成监听器
        setupLayoutListener()

        loadReadingHistory()
        loadTextContent()
    }

    private fun initViews() {
        textContentTextView = findViewById(R.id.textContentTextView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        pageIndicator = findViewById(R.id.pageIndicator)
        rootLayout = findViewById(R.id.rootLayout)
        chapterButton = findViewById(R.id.chapterButton)
        statusLabel = findViewById(R.id.statusLabel)

        // 隐藏标题栏
        supportActionBar?.hide()

        // 设置文本视图为固定高度，避免滚动
        textContentTextView.isScrollContainer = false

        // 设置页面指示器为小字号，透明背景
        pageIndicator.textSize = 12f
        pageIndicator.setBackgroundColor(Color.TRANSPARENT)
        pageIndicator.setTextColor(getColor(R.color.text_primary))

        // 设置章节按钮为透明但保持可点击
        chapterButton.setImageResource(android.R.color.transparent)
        chapterButton.setBackgroundColor(Color.TRANSPARENT)

        // 设置章节按钮点击事件
        chapterButton.setOnClickListener {
            showChapterDialog()
        }
    }

    private fun setupLayoutListener() {
        // 添加全局布局监听器来检测视图布局完成
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            if (!isFirstLayoutComplete.get()) {
                Log.d("TextPreview", "首次布局完成，textView高度: ${textContentTextView.height}")
                isFirstLayoutComplete.set(true)

                // 如果内容已经加载但还未分页，立即执行分页
                if (isContentLoaded.get() && fullContent.isNotEmpty()) {
                    Log.d("TextPreview", "布局完成后触发分页")
                    performClientPagingWithRetry()
                }
            }
        }
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""

        Log.d("TextPreview", "初始化文件信息: 文件名=$currentFileName, URL=$currentFileUrl")
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 防重复点击
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < minClickInterval) {
                    return true
                }
                lastClickTime = currentTime

                val screenWidth = resources.displayMetrics.widthPixels
                // 左点击 - 上一页（屏幕左侧1/3区域）
                if (e.x < screenWidth / 3) {
                    showPreviousPage()
                    return true
                }
                // 右点击 - 下一页（屏幕右侧1/3区域）
                else if (e.x > screenWidth * 2 / 3) {
                    showNextPage()
                    return true
                }
                // 中间区域不处理
                return false
            }
        })

        // 设置触摸监听器到整个布局
        rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                // 防重复点击
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < minClickInterval) {
                    return@setOnTouchListener true
                }
                lastClickTime = currentTime

                val screenWidth = resources.displayMetrics.widthPixels
                if (event.x < screenWidth / 3) {
                    showPreviousPage()
                    return@setOnTouchListener true
                } else if (event.x > screenWidth * 2 / 3) {
                    showNextPage()
                    return@setOnTouchListener true
                }
            }
            gestureDetector.onTouchEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun loadReadingHistory() {
        Log.d("TextPreview", "尝试加载阅读历史: ${readingHistoryFile.absolutePath}")

        if (readingHistoryFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(readingHistoryFile)).use { ois ->
                    val history = ois.readObject() as ReadingHistory
                    if (history.fileName == currentFileName) {
                        currentServerPage = history.serverPage
                        currentClientPage = history.clientPage
                        Log.d("TextPreview", "加载阅读历史成功: 服务器页=${currentServerPage}, 客户端页=${currentClientPage}")
                    } else {
                        Log.d("TextPreview", "文件名不匹配，不使用历史记录")
                    }
                }
            } catch (e: Exception) {
                Log.e("TextPreview", "加载阅读历史失败", e)
            }
        } else {
            Log.d("TextPreview", "阅读历史文件不存在")
        }
    }

    private fun saveReadingHistory() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val history = ReadingHistory(
                    fileName = currentFileName,
                    fileUrl = currentFileUrl,
                    serverPage = currentServerPage,
                    clientPage = currentClientPage,
                    timestamp = System.currentTimeMillis()
                )
                ObjectOutputStream(FileOutputStream(readingHistoryFile)).use { oos ->
                    oos.writeObject(history)
                }
                Log.d("TextPreview", "保存阅读历史成功: ${readingHistoryFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("TextPreview", "保存阅读历史失败", e)
            }
        }
    }

    private fun loadTextContent() {
        showLoadingState()
        isContentLoaded.set(false)

        coroutineScope.launch {
            try {
                val url = buildTextUrlWithPagination()
                Log.d("TextPreview", "加载文本内容: $url")

                val textData = withContext(Dispatchers.IO) {
                    loadTextContentFromServer(url)
                }

                val jsonObject = JSONObject(textData)
                val content = jsonObject.optString("content", "")
                val fileName = jsonObject.optString("fileName", currentFileName)

                // 获取分页信息
                val pagination = jsonObject.optJSONObject("pagination")
                val fileInfo = jsonObject.optJSONObject("fileInfo")

                // 更新分页信息
                updatePaginationInfo(pagination, fileInfo)

                // 设置完整内容并进行客户端分页
                fullContent = content
                isContentLoaded.set(true)

                Log.d("TextPreview", "文本加载成功: ${fileName}, 服务器页码: ${currentServerPage}")

                // 立即更新进度显示
                updatePageIndicator()

                // 使用双次加载机制确保视图布局完成
                performClientPagingWithRetry()

            } catch (e: Exception) {
                Log.e("TextPreviewActivity", "文本加载失败", e)
                showErrorState("文本加载失败: ${e.message}")
                updatePageIndicator() // 错误时也更新进度
            }
        }
    }

    private fun performClientPagingWithRetry() {
        Log.d("TextPreview", "开始双次加载分页，重试计数: $retryCount, 首次布局完成: ${isFirstLayoutComplete.get()}")

        // 第一次尝试：立即执行
        textContentTextView.post {
            performClientPaging("第一次分页尝试")

            // 第二次尝试：延迟执行以确保布局完全稳定
            textContentTextView.postDelayed({
                if (retryCount < maxRetryCount) {
                    retryCount++
                    Log.d("TextPreview", "执行第二次分页尝试，计数: $retryCount")
                    performClientPaging("第二次分页尝试")
                }
            }, 100L) // 延迟100毫秒
        }
    }

    private fun performClientPaging(attempt: String) {
        if (fullContent.isEmpty()) {
            Log.w("TextPreview", "$attempt: 内容为空")
            textContentTextView.text = ""
            updatePageIndicator() // 确保进度更新
            return
        }

        try {
            // 计算文本视图可以显示多少行文本
            linesPerPage = calculateMaxLines()
            Log.d("TextPreview", "$attempt: 计算每页行数=$linesPerPage, 文本视图高度=${textContentTextView.height}")

            // 按行分割内容
            val lines = fullContent.split("\n")

            // 计算客户端总页数
            totalClientPages = if (linesPerPage > 0) {
                (lines.size + linesPerPage - 1) / linesPerPage
            } else {
                Log.w("TextPreview", "$attempt: 每页行数为0，使用默认值18")
                18 // 默认值
            }

            // 确保当前页在有效范围内
            currentClientPage = currentClientPage.coerceIn(1, totalClientPages)

            // 显示当前客户端页面
            showCurrentClientPage(lines, linesPerPage, attempt)

            // 保存阅读历史和更新进度
            saveReadingHistory()
            updatePageIndicator() // 确保进度更新

            // 显示内容状态
            showContentState()

            Log.d("TextPreview", "$attempt: 客户端分页完成 - 当前页=$currentClientPage, 总页数=$totalClientPages, 每页行数=$linesPerPage")

        } catch (e: Exception) {
            Log.e("TextPreview", "$attempt: 分页失败", e)
            updatePageIndicator() // 即使失败也更新进度
        }
    }

    private fun buildTextUrlWithPagination(): String {
        return if (currentFileUrl.contains("?")) {
            "${currentFileUrl}&page=$currentServerPage"
        } else {
            "${currentFileUrl}?page=$currentServerPage"
        }
    }

    private fun updatePaginationInfo(pagination: JSONObject?, fileInfo: JSONObject?) {
        pagination?.let {
            currentServerPage = it.optInt("currentPage", 1)
            totalServerPages = it.optInt("totalPages", 1)
            totalLines = it.optInt("totalLines", 0)
            Log.d("TextPreview", "分页信息: 当前页=$currentServerPage, 总页数=$totalServerPages, 总行数=$totalLines")
        }

        // 记录总体行数信息
        fileInfo?.let {
            val lines = it.optInt("lines", totalLines)
            if (lines > totalLines) {
                totalLines = lines
                Log.d("TextPreview", "更新总行数: $totalLines")
            }
        }
    }

    private fun calculateMaxLines(): Int {
        try {
            // 确保视图已经布局完成
            if (textContentTextView.height == 0) {
                textContentTextView.measure(0, 0)
                Log.d("TextPreview", "文本视图高度为0，进行测量")
            }

            // 获取文本视图的高度和行高
            val height = textContentTextView.measuredHeight
            val lineHeight = textContentTextView.lineHeight

            // 考虑边距
            val paddingTop = textContentTextView.paddingTop
            val paddingBottom = textContentTextView.paddingBottom
            val availableHeight = height - paddingTop - paddingBottom

            // 计算最大行数
            val maxLines = (availableHeight / lineHeight).toInt()

            // 减少2行作为安全余量，确保不会被切
            val safeMaxLines = (maxLines - 2).coerceAtLeast(1)

            Log.d("TextPreview", "计算最大行数: 高度=$height, 行高=$lineHeight, 可用高度=$availableHeight, 最大行数=$maxLines, 安全行数=$safeMaxLines")

            return safeMaxLines
        } catch (e: Exception) {
            Log.e("TextPreview", "计算最大行数失败", e)
            return 18 // 默认值
        }
    }

    private fun showCurrentClientPage(lines: List<String>, linesPerPage: Int, attempt: String) {
        val startIndex = (currentClientPage - 1) * linesPerPage
        val endIndex = (startIndex + linesPerPage).coerceAtMost(lines.size)

        val pageLines = if (startIndex < lines.size) {
            lines.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        val pageContent = pageLines.joinToString("\n")
        textContentTextView.text = pageContent
        updatePageIndicator()

        Log.d("TextPreview", "$attempt: 显示客户端页面 - 页$currentClientPage/$totalClientPages, 行范围: $startIndex-$endIndex, 总行数: ${lines.size}, 显示行数: ${pageLines.size}")
    }

    private fun updatePageIndicator() {
        val progress = calculateReadingProgress()
        pageIndicator.text = "$progress%"
    }

    private fun calculateReadingProgress(): Int {
        return try {
            // 方法1: 使用服务器分页信息计算（更准确）
            if (totalServerPages > 0) {
                val serverProgress = ((currentServerPage - 1) * 100 / totalServerPages).toFloat()

                // 如果当前服务器页有客户端分页，添加当前页内的进度
                val pageProgress = if (totalClientPages > 1) {
                    ((currentClientPage - 1) * 100 / totalClientPages).toFloat() / totalServerPages
                } else {
                    0f
                }

                (serverProgress + pageProgress).toInt().coerceIn(0, 100)
            }
            // 方法2: 使用客户端分页信息计算（备选方案）
            else if (totalClientPages > 0) {
                (currentClientPage * 100 / totalClientPages).coerceAtMost(100)
            }
            // 方法3: 使用总行数计算（如果可用）
            else if (totalLines > 0 && fullContent.isNotEmpty()) {
                val lines = fullContent.split("\n")
                val currentLine = ((currentServerPage - 1) * linesPerPage) + ((currentClientPage - 1) * linesPerPage)
                ((currentLine * 100) / totalLines.toFloat()).toInt().coerceIn(0, 100)
            }
            else {
                // 默认方法：基于当前章节位置估算
                estimateProgressFromChapters()
            }
        } catch (e: Exception) {
            Log.e("TextPreview", "计算阅读进度失败", e)
            estimateProgressFromChapters()
        }
    }

    private fun estimateProgressFromChapters(): Int {
        return if (chapterList.isNotEmpty() && totalServerPages > 0) {
            // 基于当前章节在章节列表中的位置估算进度
            val currentChapterIndex = findCurrentChapterIndex()
            if (currentChapterIndex >= 0) {
                ((currentChapterIndex + 1) * 100 / chapterList.size).coerceAtMost(100)
            } else {
                // 如果没有找到匹配的章节，使用服务器页数估算
                (currentServerPage * 100 / totalServerPages).coerceAtMost(100)
            }
        } else {
            // 最后备选：使用服务器页数
            if (totalServerPages > 0) {
                (currentServerPage * 100 / totalServerPages).coerceAtMost(100)
            } else {
                0
            }
        }
    }

    private fun findCurrentChapterIndex(): Int {
        // 查找当前阅读位置对应的章节
        for ((index, chapter) in chapterList.withIndex()) {
            if (chapter.serverPage == currentServerPage && chapter.clientPage <= currentClientPage) {
                return index
            } else if (chapter.serverPage < currentServerPage) {
                return index
            }
        }
        return -1
    }

    private fun showPreviousPage() {
        Log.d("TextPreview", "点击上一页: 当前客户端页=$currentClientPage, 总客户端页=$totalClientPages, 当前服务器页=$currentServerPage, 总服务器页=$totalServerPages")

        if (currentClientPage > 1) {
            currentClientPage--
            performClientPaging("上一页")
        } else if (currentServerPage > 1) {
            // 需要加载上一服务器页
            currentServerPage--
            currentClientPage = 1 // 重置为客户端第一页
            loadTextContent()
        } else {
            // 已经是第一页了
            Toast.makeText(this, "已经是第一页了", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNextPage() {
        Log.d("TextPreview", "点击下一页: 当前客户端页=$currentClientPage, 总客户端页=$totalClientPages, 当前服务器页=$currentServerPage, 总服务器页=$totalServerPages")

        if (currentClientPage < totalClientPages) {
            currentClientPage++
            performClientPaging("下一页")
        } else if (currentServerPage < totalServerPages) {
            // 需要加载下一服务器页
            currentServerPage++
            currentClientPage = 1 // 重置为客户端第一页
            loadTextContent()
        } else {
            // 已经是最后一页了
            Toast.makeText(this, "已经是最后一页了", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadTextContentFromServer(url: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            response.body?.string() ?: throw IOException("响应体为空")
        }
    }

    private fun showLoadingState() {
        loadingProgress.isVisible = true
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = false
        chapterButton.isVisible = false
        statusLabel.isVisible = false
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
        chapterButton.isVisible = false
        statusLabel.isVisible = false
    }

    // 章节跳转相关功能
    private fun showChapterDialog() {
        Log.d("TextPreview", "显示章节对话框，章节索引构建状态: $isChapterIndexBuilt")

        if (!isChapterIndexBuilt) {
            isChapterIndexBuilt = loadChapterIndex()
        }

        if (!isChapterIndexBuilt) {
            buildChapterIndex()
        } else {
            showChapterList()
        }
    }

    private fun buildChapterIndex() {
        coroutineScope.launch {
            showLoadingState()
            statusLabel.isVisible = true
            statusLabel.text = "正在构建章节索引..."

            try {
                // 先检查是否已经知道总页数
                if (totalServerPages <= 0) {
                    statusLabel.text = "正在获取文件信息..."
                    // 如果没有总页数信息，先获取第一页来获取分页信息
                    val firstPageUrl = if (currentFileUrl.contains("?")) {
                        "${currentFileUrl}&page=1"
                    } else {
                        "${currentFileUrl}?page=1"
                    }

                    val firstPageData = withContext(Dispatchers.IO) {
                        loadTextContentFromServer(firstPageUrl)
                    }

                    val jsonObject = JSONObject(firstPageData)
                    val pagination = jsonObject.optJSONObject("pagination")
                    totalServerPages = pagination?.optInt("totalPages", 1) ?: 1
                    Log.d("TextPreview", "获取到总页数: $totalServerPages")
                }

                val fullText = withContext(Dispatchers.IO) {
                    loadFullTextContent()
                }

                statusLabel.text = "正在分析章节结构..."

                val chapters = detectChapters(fullText)
                chapterList.clear()
                chapterList.addAll(chapters)

                // 按行号排序
                chapterList.sortBy { it.lineNumber }

                saveChapterIndex()

                isChapterIndexBuilt = true
                showContentState()

                // 显示构建结果
                val chapterCount = chapters.size
                if (chapterCount > 0) {
                    showChapterList()
                    Toast.makeText(this@TextPreviewActivity,
                        "章节索引构建完成，共发现 $chapterCount 个章节",
                        Toast.LENGTH_LONG).show()

                    // 记录发现的章节类型统计
                    val symbolWrappedCount = chapters.count {
                        it.title != extractContentFromWrapping(it.title)
                    }
                    Log.d("TextPreview", "章节统计: 符号包裹章节=$symbolWrappedCount, 普通章节=${chapterCount - symbolWrappedCount}")
                } else {
                    Toast.makeText(this@TextPreviewActivity,
                        "未发现章节结构，请检查文件格式",
                        Toast.LENGTH_LONG).show()
                }

                Log.d("TextPreview", "章节索引构建完成，共找到 ${chapters.size} 个章节")

            } catch (e: Exception) {
                Log.e("TextPreview", "构建章节索引失败", e)
                showErrorState("构建章节索引失败: ${e.message}")

                // 提供重试选项
                AlertDialog.Builder(this@TextPreviewActivity)
                    .setTitle("章节索引构建失败")
                    .setMessage("是否重新尝试构建章节索引？")
                    .setPositiveButton("重试") { _, _ ->
                        buildChapterIndex()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private suspend fun loadFullTextContent(): String {
        return withContext(Dispatchers.IO) {
            val fullText = StringBuilder()

            Log.d("TextPreview", "开始加载完整文本内容，总页数: $totalServerPages")

            for (page in 1..totalServerPages) {
                val url = if (currentFileUrl.contains("?")) {
                    "${currentFileUrl}&page=$page"
                } else {
                    "${currentFileUrl}?page=$page"
                }

                Log.d("TextPreview", "加载页面 $page: $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }

                val textData = response.body?.string() ?: throw IOException("响应体为空")
                val jsonObject = JSONObject(textData)
                val content = jsonObject.optString("content", "")

                fullText.append(content).append("\n")

                withContext(Dispatchers.Main) {
                    val progress = (page * 100 / totalServerPages)
                    statusLabel.text = "正在加载文本: $progress%"
                }
            }

            Log.d("TextPreview", "完整文本内容加载完成，总长度: ${fullText.length}")
            fullText.toString()
        }
    }

    private fun detectChapters(fullText: String): List<ChapterInfo> {
        val chapters = mutableListOf<ChapterInfo>()
        val lines = fullText.split("\n")

        Log.d("TextPreview", "开始检测章节，总行数: ${lines.size}")

        // 更健壮的章节模式匹配
        val chapterPatterns = listOf(
            // 中文数字章节: 第一章, 第一百章, 第零章
            Pattern.compile("^第[零一二三四五六七八九十百千]+章[\\s\\S]*"),
            Pattern.compile("^第[零一二三四五六七八九十百千]+节[\\s\\S]*"),
            Pattern.compile("^第[零一二三四五六七八九十百千]+回[\\s\\S]*"),

            // 阿拉伯数字章节: 第1章, 第123章, 第2节
            Pattern.compile("^第\\d+章[\\s\\S]*"),
            Pattern.compile("^第\\d+节[\\s\\S]*"),
            Pattern.compile("^第\\d+回[\\s\\S]*"),

            // 混合数字章节: 第1章, 第2节等
            Pattern.compile("^第\\d+[章节回][\\s\\S]*"),

            // 数字序号: 1., 1.1, 1.1.1, 01.
            Pattern.compile("^\\d+\\.[\\s\\S]*"),
            Pattern.compile("^\\d+\\.\\d+[\\s\\S]*"),
            Pattern.compile("^\\d+\\.\\d+\\.\\d+[\\s\\S]*"),
            Pattern.compile("^\\d{2,}\\.[\\s\\S]*"),

            // 中文序号: 一、, 二、, 第一章, 第一节
            Pattern.compile("^[一二三四五六七八九十百千]+、[\\s\\S]*"),
            Pattern.compile("^[一二三四五六七八九十百千]+\\.[\\s\\S]*"),

            // 括号数字: (1), (2), (一), (二)
            Pattern.compile("^[(（][一二三四五六七八九十百千]+[)）][\\s\\S]*"),
            Pattern.compile("^[(（]\\d+[)）][\\s\\S]*"),

            // 英文章节: Chapter 1, CHAPTER 1, Section 1
            Pattern.compile("^[Cc]hapter\\s+\\d+[\\s\\S]*"),
            Pattern.compile("^[Cc]hapter\\s+[IVXLCDM]+[\\s\\S]*"),
            Pattern.compile("^[Ss]ection\\s+\\d+[\\s\\S]*"),
            Pattern.compile("^[Pp]art\\s+\\d+[\\s\\S]*"),

            // 特殊章节标记
            Pattern.compile("^[卷集部篇][\\s\\S]*[一二三四五六七八九十百千\\d]+[\\s\\S]*"),
            Pattern.compile("^[前序后末终][记述言][\\s\\S]*"),
            Pattern.compile("^[引子楔子尾声结局][\\s\\S]*"),

            // 新增：处理特殊符号包裹的章节标题
            // ###第四章 标题###
            Pattern.compile("^###.*第[零一二三四五六七八九十百千]+章.*###"),
            Pattern.compile("^###.*第\\d+章.*###"),

            // ***第四章 标题***
            Pattern.compile("^\\*{3,}.*第[零一二三四五六七八九十百千]+章.*\\*{3,}"),
            Pattern.compile("^\\*{3,}.*第\\d+章.*\\*{3,}"),

            // ---第四章 标题---
            Pattern.compile("^-{3,}.*第[零一二三四五六七八九十百千]+章.*-{3,}"),
            Pattern.compile("^-{3,}.*第\\d+章.*-{3,}"),

            // ===第四章 标题===
            Pattern.compile("^={3,}.*第[零一二三四五六七八九十百千]+章.*={3,}"),
            Pattern.compile("^={3,}.*第\\d+章.*={3,}"),

            // 《《第四章 标题》》
            Pattern.compile("^《+.*第[零一二三四五六七八九十百千]+章.*》+"),
            Pattern.compile("^《+.*第\\d+章.*》+"),

            // 【第四章 标题】
            Pattern.compile("^【.*第[零一二三四五六七八九十百千]+章.*】"),
            Pattern.compile("^【.*第\\d+章.*】"),

            // [[第四章 标题]]
            Pattern.compile("^\\[+.*第[零一二三四五六七八九十百千]+章.*\\]+"),
            Pattern.compile("^\\[+.*第\\d+章.*\\]+"),

            // 通用符号包裹模式：任意重复符号包裹的章节
            Pattern.compile("^(.)\\1{2,}.*第[零一二三四五六七八九十百千]+章.*\\1{2,}"),
            Pattern.compile("^(.)\\1{2,}.*第\\d+章.*\\1{2,}")
        )

        // 章节关键词，用于进一步验证
        val chapterKeywords = listOf(
            "章", "节", "回", "卷", "集", "部", "篇",
            "chapter", "Chapter", "section", "Section", "part", "Part",
            "序", "前言", "后记", "楔子", "引子", "尾声", "结局"
        )

        // 特殊符号前缀模式（用于快速检测可能被符号包裹的章节行）
        val symbolPrefixPatterns = listOf(
            Pattern.compile("^###.*"),
            Pattern.compile("^\\*{3,}.*"),
            Pattern.compile("^-{3,}.*"),
            Pattern.compile("^={3,}.*"),
            Pattern.compile("^《+.*"),
            Pattern.compile("^【.*"),
            Pattern.compile("^\\[+.*"),
            Pattern.compile("^#+.*"), // Markdown 标题
            Pattern.compile("^~{3,}.*") // 其他符号
        )

        var currentServerPage = 1
        var currentClientPage = 1
        var linesInCurrentPage = 0

        for ((lineIndex, line) in lines.withIndex()) {
            val trimmedLine = line.trim()

            // 跳过空行和过短的行
            if (trimmedLine.isEmpty() || trimmedLine.length < 2) {
                continue
            }

            var isChapter = false
            var chapterTitle = trimmedLine

            // 1. 首先使用正则表达式匹配
            for (pattern in chapterPatterns) {
                val matcher = pattern.matcher(trimmedLine)
                if (matcher.matches()) {
                    isChapter = true

                    // 对于符号包裹的章节，清理符号提取纯文本标题
                    chapterTitle = extractCleanChapterTitle(trimmedLine)
                    break
                }
            }

            // 2. 如果正则匹配失败，检查是否是符号包裹的行，然后提取内容再匹配
            if (!isChapter && hasSymbolWrapping(trimmedLine, symbolPrefixPatterns)) {
                val cleanContent = extractContentFromWrapping(trimmedLine)
                if (cleanContent.isNotEmpty()) {
                    // 对清理后的内容再次进行章节匹配
                    for (pattern in chapterPatterns.subList(0, 20)) { // 使用基础模式匹配
                        val matcher = pattern.matcher(cleanContent)
                        if (matcher.matches()) {
                            isChapter = true
                            chapterTitle = extractCleanChapterTitle(cleanContent)
                            break
                        }
                    }

                    // 如果清理后的内容包含章节关键词，也认为是章节
                    if (!isChapter) {
                        val hasChapterKeyword = chapterKeywords.any { keyword ->
                            cleanContent.contains(keyword) &&
                                    cleanContent.substring(0, minOf(20, cleanContent.length)).contains(keyword)
                        }

                        if (hasChapterKeyword && cleanContent.length <= 50) {
                            isChapter = true
                            chapterTitle = cleanContent
                        }
                    }
                }
            }

            // 3. 如果正则匹配失败，使用关键词匹配
            if (!isChapter) {
                isChapter = chapterKeywords.any { keyword ->
                    trimmedLine.contains(keyword) &&
                            // 确保关键词不是出现在行中间（大概率是章节标题）
                            (trimmedLine.startsWith(keyword) ||
                                    trimmedLine.substring(0, minOf(20, trimmedLine.length)).contains(keyword))
                }
            }

            // 4. 进一步验证：检查行长度和内容特征
            if (isChapter) {
                // 章节标题通常不会太长（排除可能是正文的情况）
                if (trimmedLine.length > 100 && !hasSymbolWrapping(trimmedLine, symbolPrefixPatterns)) {
                    isChapter = false
                }

                // 章节标题通常不会包含太多标点符号（排除对话等）
                val punctuationCount = trimmedLine.count { it in listOf('，', '。', '！', '？', '；', '：', '、') }
                if (punctuationCount > 3 && !hasSymbolWrapping(trimmedLine, symbolPrefixPatterns)) {
                    isChapter = false
                }

                // 检查是否包含明显的非章节词汇
                val excludeWords = listOf("说道", "心想", "看着", "然后", "但是", "因为", "所以", "突然", "不过")
                if (excludeWords.any { trimmedLine.contains(it) } && !hasSymbolWrapping(trimmedLine, symbolPrefixPatterns)) {
                    isChapter = false
                }
            }

            // 5. 数字序列检测（备用方案）
            if (!isChapter && lineIndex > 0) {
                isChapter = detectNumberSequence(lines, lineIndex, trimmedLine)
            }

            if (isChapter) {
                val serverPage = currentServerPage
                val clientPage = currentClientPage

                chapters.add(ChapterInfo(chapterTitle, serverPage, clientPage, lineIndex))
                Log.d("TextPreview", "发现章节: $chapterTitle, 服务器页: $serverPage, 客户端页: $clientPage, 行号: $lineIndex")
            }

            // 更新分页状态
            linesInCurrentPage++
            if (linesInCurrentPage >= linesPerPage) {
                linesInCurrentPage = 0
                currentClientPage++

                if (currentClientPage > totalClientPages) {
                    currentClientPage = 1
                    currentServerPage++
                }
            }
        }

        Log.d("TextPreview", "章节检测完成，共发现 ${chapters.size} 个章节")

        // 后处理：过滤掉过于密集的"章节"（可能是误识别）
        return filterDenseChapters(chapters)
    }

    /**
     * 检查行是否被特殊符号包裹
     */
    private fun hasSymbolWrapping(line: String, patterns: List<Pattern>): Boolean {
        return patterns.any { pattern ->
            pattern.matcher(line).matches()
        }
    }

    /**
     * 从符号包裹的行中提取内容
     */
    private fun extractContentFromWrapping(line: String): String {
        var content = line.trim()

        // 移除常见的包裹符号
        val wrappingPatterns = listOf(
            Pattern.compile("^###(.*)###$"),
            Pattern.compile("^\\*{3,}(.*)\\*{3,}$"),
            Pattern.compile("^-{3,}(.*)-{3,}$"),
            Pattern.compile("^={3,}(.*)={3,}$"),
            Pattern.compile("^《+(.*)》+$"),
            Pattern.compile("^【(.*)】$"),
            Pattern.compile("^\\[+(.*)\\]$"),
            Pattern.compile("^#+(.*)$"), // Markdown 标题
            Pattern.compile("^~{3,}(.*)~{3,}$")
        )

        for (pattern in wrappingPatterns) {
            val matcher = pattern.matcher(content)
            if (matcher.matches()) {
                content = matcher.group(1)?.trim() ?: content
                break
            }
        }

        return content
    }

    /**
     * 提取清理后的章节标题（移除多余的符号）
     */
    private fun extractCleanChapterTitle(line: String): String {
        var title = line.trim()

        // 移除常见的包裹符号
        title = extractContentFromWrapping(title)

        // 移除行首行尾的标点符号
        title = title.trimStart(' ', '#', '*', '-', '=', '~', '《', '【', '[', '（', '(')
            .trimEnd(' ', '#', '*', '-', '=', '~', '》', '】', ']', '）', ')')

        return title
    }

    /**
     * 检测数字序列，用于识别类似"1" "2" "3"这样的简单章节编号
     */
    private fun detectNumberSequence(lines: List<String>, currentIndex: Int, currentLine: String): Boolean {
        if (currentIndex < 2) return false

        val currentTrimmed = currentLine.trim()
        val prev1Trimmed = lines[currentIndex - 1].trim()
        val prev2Trimmed = lines[currentIndex - 2].trim()

        // 检查当前行是否是纯数字
        val isCurrentNumber = currentTrimmed.matches(Regex("^\\d+$"))
        val isPrev1Number = prev1Trimmed.matches(Regex("^\\d+$"))
        val isPrev2Number = prev2Trimmed.matches(Regex("^\\d+$"))

        // 如果连续三行都是数字，且是递增的，则认为是章节编号
        if (isCurrentNumber && isPrev1Number && isPrev2Number) {
            val currentNum = currentTrimmed.toIntOrNull()
            val prev1Num = prev1Trimmed.toIntOrNull()
            val prev2Num = prev2Trimmed.toIntOrNull()

            if (currentNum != null && prev1Num != null && prev2Num != null) {
                return currentNum == prev1Num + 1 && prev1Num == prev2Num + 1
            }
        }

        return false
    }

    /**
     * 过滤过于密集的章节识别结果
     */
    private fun filterDenseChapters(chapters: List<ChapterInfo>): List<ChapterInfo> {
        if (chapters.size < 3) return chapters

        val filtered = mutableListOf<ChapterInfo>()
        filtered.add(chapters[0])

        for (i in 1 until chapters.size) {
            val current = chapters[i]
            val previous = filtered.last()

            // 如果两个相邻章节行号差距太小（小于10行），可能是误识别，跳过
            if (current.lineNumber - previous.lineNumber > 10) {
                filtered.add(current)
            } else {
                Log.d("TextPreview", "过滤密集章节: ${current.title} (行号: ${current.lineNumber})")
            }
        }

        return filtered
    }

    private fun saveChapterIndex() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                ObjectOutputStream(FileOutputStream(chapterIndexFile)).use { oos ->
                    oos.writeObject(ArrayList(chapterList))
                }
                Log.d("TextPreview", "章节索引保存成功: ${chapterIndexFile.absolutePath}, 章节数: ${chapterList.size}")
            } catch (e: Exception) {
                Log.e("TextPreview", "保存章节索引失败", e)
            }
        }
    }

    private fun loadChapterIndex(): Boolean {
        Log.d("TextPreview", "尝试加载章节索引: ${chapterIndexFile.absolutePath}")

        return if (chapterIndexFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(chapterIndexFile)).use { ois ->
                    val loadedChapters = ois.readObject() as ArrayList<ChapterInfo>
                    chapterList.clear()
                    chapterList.addAll(loadedChapters)
                    isChapterIndexBuilt = true
                    Log.d("TextPreview", "章节索引加载成功，共 ${chapterList.size} 个章节")
                    true
                }
            } catch (e: Exception) {
                Log.e("TextPreview", "加载章节索引失败", e)
                false
            }
        } else {
            Log.d("TextPreview", "章节索引文件不存在")
            false
        }
    }

    private fun showChapterList() {
        if (chapterList.isEmpty()) {
            Toast.makeText(this, "未发现章节", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("TextPreview", "显示章节列表，章节数: ${chapterList.size}")

        val chapterTitles = chapterList.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("章节跳转 (${chapterList.size}章)")
            .setItems(chapterTitles) { dialog: android.content.DialogInterface?, which: Int ->
                val chapter = chapterList[which]
                jumpToChapter(chapter)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun jumpToChapter(chapter: ChapterInfo) {
        currentServerPage = chapter.serverPage
        currentClientPage = chapter.clientPage
        loadTextContent()

        // 立即更新进度显示
        updatePageIndicator()

        Toast.makeText(this, "跳转到: ${chapter.title}", Toast.LENGTH_SHORT).show()
        Log.d("TextPreview", "跳转到章节: ${chapter.title}, 服务器页: ${chapter.serverPage}, 客户端页: ${chapter.clientPage}")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}