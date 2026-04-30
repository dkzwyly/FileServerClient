package com.dkc.fileserverclient

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class TextPreviewViewModel : ViewModel() {

    // UI 数据
    private val _pageContent = MutableLiveData<String>()
    val pageContent: LiveData<String> = _pageContent

    private val _pageInfo = MutableLiveData<PageInfo>()
    val pageInfo: LiveData<PageInfo> = _pageInfo

    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _chapters = MutableLiveData<List<ChapterInfo>>()
    val chapters: LiveData<List<ChapterInfo>> = _chapters

    private val _currentPageState = MutableLiveData<PageState?>()
    val currentPageState: LiveData<PageState?> = _currentPageState

    // 内部状态
    private lateinit var fileName: String
    private lateinit var fileUrl: String
    private lateinit var filePath: String

    // 显示参数（来自 TextView）
    private var textWidth = 0
    private var textPaint: TextPaint? = null
    private var lineSpacingExtra = 0f
    private var lineSpacingMultiplier = 1f
    private var linesPerPage = 20   // 每页显示的行数（动态更新）

    // 分页相关
    private var currentBlock: BlockData? = null
    private var currentSubPage = 1
    private var totalSubPages = 1
    private val subPageBoundaries = mutableListOf<Pair<Int, Int>>()  // (起始行索引, 结束行索引)

    // 历史记录恢复
    private var restoredBlockPage = 1
    private var restoredSubPage = 1
    private var isHistoryRestored = false

    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    // 数据类
    data class PageInfo(val currentPage: Int, val totalPages: Int, val progress: Int)
    data class LoadingState(val isLoading: Boolean, val message: String? = null)
    data class ChapterInfo(val title: String, val serverPage: Int, val lineNumber: Int)
    data class PageState(val blockPage: Int, val subPage: Int, val totalBlockPages: Int, val totalSubPages: Int)
    data class BlockData(val fullText: String, val blockPage: Int, val totalBlockPages: Int)

    // ---------- 公共方法 ----------
    fun initialize(fileName: String, fileUrl: String, filePath: String) {
        this.fileName = fileName
        this.fileUrl = fileUrl
        this.filePath = filePath
    }

    fun setDisplayParams(width: Int, paint: Paint, extra: Float, multiplier: Float) {
        this.textWidth = width
        this.textPaint = TextPaint(paint)
        this.lineSpacingExtra = extra
        this.lineSpacingMultiplier = multiplier
    }

    /**
     * 使用 StaticLayout 测量在给定高度下能显示的最大行数。
     * 参数 maxHeight 为 TextView 内容区域的实际高度（已减去 padding）。
     */
    fun calculateMaxLinesPerPage(maxHeight: Int): Int {
        if (textWidth <= 0 || textPaint == null || maxHeight <= 0) return 0
        val testText = "测试文字\n测试文字\n"
        val layout = StaticLayout.Builder.obtain(testText, 0, testText.length, textPaint!!, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()
        val lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        if (lineHeight <= 0) return 0
        val theoreticalLines = maxHeight / lineHeight
        // 保守减 1，防止底部裁剪
        return (theoreticalLines - 1).coerceAtLeast(1)
    }

    fun setLinesPerPage(lines: Int) {
        val safeLines = if (lines < 2) {
            Log.w("ViewModel", "linesPerPage 无效 ($lines)，使用 20")
            20
        } else {
            lines
        }
        if (linesPerPage != safeLines) {
            linesPerPage = safeLines
            currentBlock?.let {
                rebuildSubPages(it.fullText)
                showCurrentSubPage()
            }
        }
    }

    fun restoreFromHistory(blockPage: Int, subPage: Int) {
        restoredBlockPage = blockPage
        restoredSubPage = subPage
        isHistoryRestored = true
    }

    fun getCurrentPageState(): PageState? {
        return currentBlock?.let {
            PageState(it.blockPage, currentSubPage, it.totalBlockPages, totalSubPages)
        }
    }

    fun loadTextContent() {
        if (textWidth <= 0 || textPaint == null) {
            Log.d("ViewModel", "等待显示参数初始化")
            return
        }
        val targetPage = if (isHistoryRestored) restoredBlockPage else 1
        loadBlock(targetPage)
    }

    fun previousPage() {
        if (currentSubPage > 1) {
            currentSubPage--
            showCurrentSubPage()
        } else if (currentBlock != null && currentBlock!!.blockPage > 1) {
            loadBlock(currentBlock!!.blockPage - 1, goToLastSubPage = true)
        }
    }

    fun nextPage() {
        if (currentSubPage < totalSubPages) {
            currentSubPage++
            showCurrentSubPage()
        } else if (currentBlock != null && currentBlock!!.blockPage < currentBlock!!.totalBlockPages) {
            loadBlock(currentBlock!!.blockPage + 1, goToFirstSubPage = true)
        }
    }

    fun loadChapters() {
        viewModelScope.launch {
            _loadingState.value = LoadingState(true, "正在加载章节...")
            try {
                val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
                _chapters.value = list
            } catch (e: Exception) {
                _errorMessage.value = "章节加载失败: ${e.message}"
            } finally {
                _loadingState.value = LoadingState(false)
            }
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) {
        loadBlock(chapter.serverPage, goToFirstSubPage = true)
    }

    // ---------- 私有方法 ----------
    private fun loadBlock(page: Int, goToFirstSubPage: Boolean = false, goToLastSubPage: Boolean = false) {
        _loadingState.value = LoadingState(true, "正在加载...")
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                val (fullText, currentPage, totalPages) = parseBlockResponse(json)

                currentBlock = BlockData(fullText, currentPage, totalPages)
                rebuildSubPages(fullText)

                when {
                    goToFirstSubPage -> currentSubPage = 1
                    goToLastSubPage -> currentSubPage = totalSubPages
                    isHistoryRestored && page == restoredBlockPage -> {
                        currentSubPage = restoredSubPage.coerceIn(1, totalSubPages)
                        isHistoryRestored = false
                    }
                    else -> currentSubPage = 1
                }

                showCurrentSubPage()
                _loadingState.value = LoadingState(false)
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
                Log.e("ViewModel", "loadBlock error", e)
            }
        }
    }

    private fun buildBlockUrl(page: Int): String {
        val base = if (fileUrl.contains("?")) "$fileUrl&" else "$fileUrl?"
        return "${base}page=$page"
    }

    private suspend fun fetchJson(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        response.body?.string() ?: throw Exception("空响应")
    }

    private fun parseBlockResponse(json: String): Triple<String, Int, Int> {
        val obj = JSONObject(json)
        val content = obj.getString("content")
        val pagination = obj.getJSONObject("pagination")
        val currentPage = pagination.getInt("currentPage")
        val totalPages = pagination.getInt("totalPages")
        return Triple(content, currentPage, totalPages)
    }

    /**
     * 使用 StaticLayout 将全文划分为物理行，并按 linesPerPage 分组，同时避免孤行寡行。
     * 结果存储在 subPageBoundaries 中，每个元素为 (起始行索引, 结束行索引)。
     */
    private fun rebuildSubPages(fullText: String) {
        subPageBoundaries.clear()
        if (textWidth <= 0 || textPaint == null || fullText.isEmpty()) {
            totalSubPages = 1
            return
        }

        val layout = StaticLayout.Builder.obtain(fullText, 0, fullText.length, textPaint!!, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()

        val totalLines = layout.lineCount
        if (totalLines == 0) {
            totalSubPages = 1
            return
        }

        var startLine = 0
        while (startLine < totalLines) {
            var endLine = minOf(startLine + linesPerPage, totalLines)
            // 孤行寡行处理：如果剩余行数不足 2 行且不是最后一段，则减少当前页的行数，将孤行留给下一页
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
            }
            subPageBoundaries.add(Pair(startLine, endLine))
            startLine = endLine
        }
        totalSubPages = subPageBoundaries.size
        Log.d("ViewModel", "重建子页完成: 总物理行=$totalLines, 每页行数=$linesPerPage, 子页数=$totalSubPages")
    }

    private fun showCurrentSubPage() {
        if (currentBlock == null) return
        val fullText = currentBlock!!.fullText
        if (subPageBoundaries.isEmpty()) {
            _pageContent.value = fullText
            updatePageInfo()
            return
        }
        val (startLine, endLine) = subPageBoundaries.getOrNull(currentSubPage - 1) ?: return

        // 重新构建 StaticLayout 以获取字符索引（或者可以缓存 layout，但简单起见每次都新建）
        val layout = StaticLayout.Builder.obtain(fullText, 0, fullText.length, textPaint!!, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()

        val startChar = layout.getLineStart(startLine)
        val endChar = layout.getLineEnd(endLine - 1)
        val pageText = fullText.substring(startChar, endChar)
        _pageContent.value = pageText
        updatePageInfo()
    }

    private fun updatePageInfo() {
        currentBlock?.let { block ->
            val progress = if (block.totalBlockPages > 0) {
                ((block.blockPage - 1) * 100 / block.totalBlockPages)
            } else 0
            _pageInfo.value = PageInfo(block.blockPage, block.totalBlockPages, progress)
            _currentPageState.value = PageState(block.blockPage, currentSubPage, block.totalBlockPages, totalSubPages)
        }
    }

    // ---------- 章节加载 ----------
    private suspend fun fetchChaptersFromServer(): List<ChapterInfo> = withContext(Dispatchers.IO) {
        try {
            // 1. 计算基础 URL（与原实现一致）
            val baseUrl = if (fileUrl.contains("/preview/")) {
                fileUrl.substringBefore("/preview/")
            } else {
                val urlParts = fileUrl.split("/api/")
                if (urlParts.size > 1) {
                    "${urlParts[0]}/api"
                } else {
                    fileUrl.substringBeforeLast("/")
                }
            }

            // 2. 提取文件名（与原实现一致）
            val fileNameFromUrl = if (fileUrl.contains("/preview/")) {
                fileUrl.substringAfter("/preview/").substringBefore("?")
            } else {
                fileName
            }

            val encodedFileName = URLEncoder.encode(fileNameFromUrl, "UTF-8")
            val chaptersUrl = "$baseUrl/chapters/$encodedFileName"

            Log.d("TextPreviewViewModel", "请求章节URL: $chaptersUrl")

            val request = Request.Builder()
                .url(chaptersUrl)
                .addHeader("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("TextPreviewViewModel", "章节请求失败: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val jsonData = response.body?.string() ?: return@withContext emptyList()
            Log.d("TextPreviewViewModel", "章节响应: $jsonData")

            parseChaptersJson(jsonData)
        } catch (e: Exception) {
            Log.e("TextPreviewViewModel", "加载章节失败", e)
            emptyList()
        }
    }

    private fun parseChaptersJson(json: String): List<ChapterInfo> {
        val chapters = mutableListOf<ChapterInfo>()
        try {
            val jsonObject = JSONObject(json)
            var chaptersArray = jsonObject.optJSONArray("chapters")
            if (chaptersArray == null) {
                val data = jsonObject.optJSONObject("data")
                chaptersArray = data?.optJSONArray("chapters")
            }

            if (chaptersArray != null) {
                for (i in 0 until chaptersArray.length()) {
                    val chapterObj = chaptersArray.getJSONObject(i)
                    val title = chapterObj.optString("title", "未知章节")
                    val serverPage = chapterObj.optInt("page", 1)
                    val lineNumber = chapterObj.optInt("lineNumber", 0)
                    chapters.add(ChapterInfo(title, serverPage, lineNumber))
                    Log.d("TextPreviewViewModel", "解析章节: $title, 服务器页=$serverPage")
                }
            }
        } catch (e: Exception) {
            Log.e("TextPreviewViewModel", "解析章节JSON失败", e)
        }
        return chapters
    }
}