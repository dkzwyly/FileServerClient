package com.dkc.fileserverclient

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class TextPreviewActivity : AppCompatActivity() {

    private lateinit var textContentTextView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var rootLayout: RelativeLayout

    private var currentFileUrl = ""
    private var currentFileName = ""
    private var currentServerPage = 1
    private var totalServerPages = 1
    private var currentClientPage = 1
    private var totalClientPages = 1
    private var totalLines = 0
    private var fullContent = ""
    private var linesPerPage = 20 // 默认值，会在计算后更新

    // 防重复点击
    private var lastClickTime = 0L
    private val minClickInterval = 100L // 最小点击间隔500毫秒

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val client = UnsafeHttpClient.createUnsafeOkHttpClient()
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_preview)

        initViews()
        setupIntentData()
        setupGestureDetector()
        loadTextContent()
    }

    private fun initViews() {
        textContentTextView = findViewById(R.id.textContentTextView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        pageIndicator = findViewById(R.id.pageIndicator)
        rootLayout = findViewById(R.id.rootLayout)

        // 隐藏标题栏
        supportActionBar?.hide()

        // 设置文本视图为固定高度，避免滚动
        textContentTextView.isScrollContainer = false

        // 设置页面指示器为小字号，透明背景
        pageIndicator.textSize = 12f
        pageIndicator.setBackgroundColor(Color.TRANSPARENT) // 透明背景
        pageIndicator.setTextColor(getColor(R.color.text_primary)) // 使用主文本颜色
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
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

    private fun loadTextContent() {
        showLoadingState()

        coroutineScope.launch {
            try {
                val url = buildTextUrlWithPagination()
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

                // 在视图布局完成后计算分页
                textContentTextView.post {
                    performClientPaging()
                    showContentState()
                }

                Log.d("TextPreview", "文本加载成功: ${fileName}, 服务器页码: ${currentServerPage}")

            } catch (e: Exception) {
                Log.e("TextPreviewActivity", "文本加载失败", e)
                showErrorState("文本加载失败: ${e.message}")
            }
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
        }

        // 记录总体行数信息
        fileInfo?.let {
            totalLines = it.optInt("lines", totalLines)
        }
    }

    private fun performClientPaging() {
        if (fullContent.isEmpty()) {
            textContentTextView.text = ""
            updatePageIndicator()
            return
        }

        // 计算文本视图可以显示多少行文本
        linesPerPage = calculateMaxLines()

        // 按行分割内容
        val lines = fullContent.split("\n")

        // 计算客户端总页数
        totalClientPages = if (linesPerPage > 0) {
            (lines.size + linesPerPage - 1) / linesPerPage
        } else {
            1
        }

        // 确保当前页在有效范围内
        currentClientPage = currentClientPage.coerceIn(1, totalClientPages)

        // 显示当前客户端页面
        showCurrentClientPage(lines, linesPerPage)
    }

    private fun calculateMaxLines(): Int {
        try {
            // 确保视图已经布局完成
            if (textContentTextView.height == 0) {
                textContentTextView.measure(0, 0)
            }

            // 获取文本视图的高度和行高
            val height = textContentTextView.measuredHeight
            val lineHeight = textContentTextView.lineHeight

            // 考虑边距 - 使用实际的padding值
            val paddingTop = textContentTextView.paddingTop
            val paddingBottom = textContentTextView.paddingBottom
            val availableHeight = height - paddingTop - paddingBottom

            // 计算最大行数，减少一行以避免被切
            val maxLines = ((availableHeight / lineHeight) - 1).coerceAtLeast(1)

            Log.d("TextPreview", "计算最大行数: 高度=$height, 行高=$lineHeight, 顶部边距=$paddingTop, 底部边距=$paddingBottom, 可用高度=$availableHeight, 最大行数=$maxLines")

            return maxLines
        } catch (e: Exception) {
            Log.e("TextPreview", "计算最大行数失败", e)
            return 20 // 默认值
        }
    }

    private fun showCurrentClientPage(lines: List<String>, linesPerPage: Int) {
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

        Log.d("TextPreview", "显示客户端页面: $currentClientPage/$totalClientPages, 行范围: $startIndex-$endIndex, 总行数: ${lines.size}")
    }

    private fun updatePageIndicator() {
        // 计算相对于总体的阅读进度百分比
        // 这里需要知道总体有多少行，我们使用服务器返回的totalLines
        // 当前已读行数 = (当前服务器页-1) * 每页行数 + 当前客户端页 * 每客户端页行数
        // 但由于我们不知道服务器每页的确切行数，我们使用估算

        val progress = if (totalLines > 0) {
            // 估算当前已读行数
            val estimatedCurrentLines = (currentServerPage - 1) * linesPerPage + (currentClientPage - 1) * linesPerPage

            // 计算百分比，确保不超过100%
            ((estimatedCurrentLines * 100) / totalLines.toFloat()).coerceAtMost(100f).toInt()
        } else {
            // 如果没有总体行数信息，使用客户端分页百分比
            if (totalClientPages > 0) {
                (currentClientPage * 100 / totalClientPages).coerceAtMost(100)
            } else {
                0
            }
        }

        pageIndicator.text = "$progress%"
    }

    private fun showPreviousPage() {
        Log.d("TextPreview", "点击上一页: 当前客户端页=$currentClientPage, 总客户端页=$totalClientPages, 当前服务器页=$currentServerPage, 总服务器页=$totalServerPages")

        if (currentClientPage > 1) {
            currentClientPage--
            performClientPaging()
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
            performClientPaging()
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
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        pageIndicator.isVisible = true
        errorTextView.isVisible = false
    }

    private fun showErrorState(message: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}