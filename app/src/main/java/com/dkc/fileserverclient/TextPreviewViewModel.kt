package com.dkc.fileserverclient

import android.graphics.Paint
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
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

    private val _pageContent = MutableLiveData<CharSequence>()
    val pageContent: LiveData<CharSequence> = _pageContent

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

    private val _currentAbsoluteCharOffset = MutableLiveData<Int>()
    val currentAbsoluteCharOffset: LiveData<Int> = _currentAbsoluteCharOffset

    private val _showChapterDialogEvent = MutableLiveData<List<ChapterInfo>>()
    val showChapterDialogEvent: LiveData<List<ChapterInfo>> = _showChapterDialogEvent

    private lateinit var fileName: String
    private lateinit var fileUrl: String
    private lateinit var filePath: String

    private var textWidth = 0
    private var textPaint: TextPaint? = null
    private var lineSpacingExtra = 0f
    private var lineSpacingMultiplier = 1f
    private var linesPerPage = 20

    private var currentBlock: BlockData? = null
    private var currentSubPage = 1
    private var totalSubPages = 1
    private val subPageBoundaries = mutableListOf<Pair<Int, Int>>()

    private var restoredBlockPage = 1
    private var restoredSubPage = 1
    private var isHistoryRestored = false

    private var pendingCharOffset: Int? = null

    // 章节数据缓存与加载状态
    private var cachedChapters: List<ChapterInfo>? = null
    private var chaptersLoading = false

    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    // --- 数据类 ---
    data class PageInfo(val currentPage: Int, val totalPages: Int, val progress: Int)
    data class LoadingState(val isLoading: Boolean, val message: String? = null)
    data class ChapterInfo(
        val title: String,
        val serverPage: Int,
        val lineNumber: Int,
        val startCharOffset: Int
    )
    data class PageState(val blockPage: Int, val subPage: Int, val totalBlockPages: Int, val totalSubPages: Int)
    data class BlockData(
        val fullText: String,
        val blockPage: Int,
        val totalBlockPages: Int,
        val startChar: Int,
        val endChar: Int
    )

    // ---------- 公开初始化与参数 ----------
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

    fun calculateMaxLinesPerPage(maxHeight: Int): Int {
        if (textWidth <= 0 || textPaint == null || maxHeight <= 0) return 0

        val testLine = "T\n"
        val sampleLayout = buildStaticLayout(testLine.repeat(1))
        val lineHeight = sampleLayout.getLineBottom(0) - sampleLayout.getLineTop(0)
        if (lineHeight <= 0) return 0
        val maxPossibleLines = (maxHeight / lineHeight).toInt() + 2

        val testLines = List(maxPossibleLines) { "T" }.joinToString("\n")

        var low = 1
        var high = maxPossibleLines
        var best = 1
        while (low <= high) {
            val mid = (low + high) / 2
            val text = testLines.lines().take(mid).joinToString("\n")
            val layout = buildStaticLayout(text)
            when {
                layout.height <= maxHeight -> {
                    best = mid
                    low = mid + 1
                }
                else -> high = mid - 1
            }
        }
        return best.coerceAtLeast(1)
    }

    private fun buildStaticLayout(text: String): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint!!, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()
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

    fun onFontSizeChanged(newLinesPerPage: Int) {
        if (currentBlock == null) return
        val currentStartChar = getCurrentVisibleStartChar()
        linesPerPage = newLinesPerPage.coerceAtLeast(2)
        currentBlock?.let { block ->
            rebuildSubPages(block.fullText)
            currentSubPage = findSubPageForCharOffset(block.fullText, block.startChar, currentStartChar)
            showCurrentSubPage()
        }
    }

    private fun getCurrentVisibleStartChar(): Int {
        val block = currentBlock ?: return 0
        val fullText = block.fullText
        if (subPageBoundaries.isEmpty() || currentSubPage < 1 || currentSubPage > subPageBoundaries.size) {
            return block.startChar
        }
        val (startLine, _) = subPageBoundaries[currentSubPage - 1]
        val layout = buildStaticLayout(fullText)
        val startChar = layout.getLineStart(startLine)
        return block.startChar + startChar
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

    // 手动加载章节（供章节按钮调用，可刷新数据）
    fun loadChapters() {
        viewModelScope.launch {
            _loadingState.value = LoadingState(true, "正在加载章节...")
            try {
                val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
                cachedChapters = list.sortedBy { it.startCharOffset }
                _chapters.value = list
                _showChapterDialogEvent.value = list  // 触发弹窗事件
                // 如果当前有内容显示，刷新以应用章节标题样式
                if (currentBlock != null) {
                    showCurrentSubPage()
                }
            } catch (e: Exception) {
                _errorMessage.value = "章节加载失败: ${e.message}"
            } finally {
                _loadingState.value = LoadingState(false)
            }
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) {
        pendingCharOffset = chapter.startCharOffset
        loadBlock(chapter.serverPage, goToFirstSubPage = false)
    }

    // ---------- 内部核心 ----------
    private fun loadBlock(page: Int, goToFirstSubPage: Boolean = false, goToLastSubPage: Boolean = false) {
        _loadingState.value = LoadingState(true, "正在加载...")
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                val block = parseBlockResponse(json)

                currentBlock = block
                rebuildSubPages(block.fullText)

                // 👇 等待章节数据就绪（若未缓存则自动请求）
                ensureChaptersLoaded()

                val targetOffset = pendingCharOffset
                pendingCharOffset = null
                when {
                    targetOffset != null -> {
                        currentSubPage = findSubPageForCharOffset(block.fullText, block.startChar, targetOffset)
                    }
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

    /**
     * 确保章节数据已缓存，若没有则从服务器获取。
     * 此函数是挂起函数，可在协程中安全调用。
     */
    private suspend fun ensureChaptersLoaded() {
        if (cachedChapters != null) return
        try {
            val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
            cachedChapters = list.sortedBy { it.startCharOffset }
            _chapters.value = list
        } catch (e: Exception) {
            Log.e("ViewModel", "预加载章节失败", e)
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

    private fun parseBlockResponse(json: String): BlockData {
        val obj = JSONObject(json)
        val content = obj.getString("content")
        val pagination = obj.getJSONObject("pagination")
        val currentPage = pagination.getInt("currentPage")
        val totalPages = pagination.getInt("totalPages")
        val startChar = pagination.optInt("startChar", 0)
        val endChar = pagination.optInt("endChar", content.length)
        return BlockData(content, currentPage, totalPages, startChar, endChar)
    }

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
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
            }
            subPageBoundaries.add(Pair(startLine, endLine))
            startLine = endLine
        }
        totalSubPages = subPageBoundaries.size
        Log.d("ViewModel", "重建子页完成: 总物理行=$totalLines, 每页行数=$linesPerPage, 子页数=$totalSubPages")
    }

    private fun findSubPageForCharOffset(fullText: String, blockStartChar: Int, absCharOffset: Int): Int {
        if (fullText.isEmpty() || subPageBoundaries.isEmpty()) return 1
        val relativeOffset = (absCharOffset - blockStartChar).coerceIn(0, fullText.length)
        val layout = StaticLayout.Builder.obtain(fullText, 0, fullText.length, textPaint!!, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()

        val line = layout.getLineForOffset(relativeOffset)
        for ((index, boundaries) in subPageBoundaries.withIndex()) {
            val (startLine, endLine) = boundaries
            if (line in startLine until endLine) {
                return index + 1
            }
        }
        return 1
    }

    // ─── 章节标题富文本渲染 ───
    private fun showCurrentSubPage() {
        if (currentBlock == null) return
        val fullText = currentBlock!!.fullText
        if (subPageBoundaries.isEmpty()) {
            _pageContent.value = fullText
            updatePageInfo()
            _currentAbsoluteCharOffset.value = currentBlock!!.startChar
            return
        }
        val (startLine, endLine) = subPageBoundaries.getOrNull(currentSubPage - 1) ?: return

        val layout = buildStaticLayout(fullText)
        val startChar = layout.getLineStart(startLine)
        val endChar = layout.getLineEnd(endLine - 1)
        val pageText = fullText.substring(startChar, endChar)

        // 应用章节标题样式
        val spannable = applyChapterStyles(fullText, pageText, startChar, currentBlock!!.startChar)

        _pageContent.value = spannable
        updatePageInfo()
        _currentAbsoluteCharOffset.value = currentBlock!!.startChar + startChar
    }

    private fun applyChapterStyles(
        fullText: String,
        pageText: String,
        pageStartInBlock: Int,
        blockStartAbsolute: Int
    ): CharSequence {
        val chapters = cachedChapters ?: return pageText
        if (chapters.isEmpty()) return pageText

        val spannable = SpannableString(pageText)

        for (chapter in chapters) {
            val absOffset = chapter.startCharOffset
            val relOffset = absOffset - blockStartAbsolute
            if (relOffset < pageStartInBlock || relOffset >= pageStartInBlock + pageText.length) continue

            val startInPage = relOffset - pageStartInBlock
            val titleEndInBlock = minOf(relOffset + chapter.title.length, fullText.length)
            val titleLenInBlock = titleEndInBlock - relOffset
            val endInPage = minOf(startInPage + titleLenInBlock, pageText.length)

            spannable.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                startInPage, endInPage,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                RelativeSizeSpan(1.5f),
                startInPage, endInPage,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                startInPage, endInPage,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
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

    // ─── 自动加载章节 ───
    private fun autoLoadChapters() {
        if (cachedChapters != null || chaptersLoading) return
        chaptersLoading = true
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
                cachedChapters = list.sortedBy { it.startCharOffset }
                _chapters.value = list
                // 章节加载成功，刷新当前页以应用标题样式
                if (currentBlock != null) {
                    showCurrentSubPage()
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "自动加载章节失败", e)
            } finally {
                chaptersLoading = false
            }
        }
    }

    // ─── 网络请求：获取章节 ───
    private suspend fun fetchChaptersFromServer(): List<ChapterInfo> = withContext(Dispatchers.IO) {
        try {
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
                    val startCharOffset = chapterObj.optInt("startCharOffset", 0)
                    chapters.add(ChapterInfo(title, serverPage, lineNumber, startCharOffset))
                    Log.d("TextPreviewViewModel", "解析章节: $title, 服务器页=$serverPage, 偏移=$startCharOffset")
                }
            }
        } catch (e: Exception) {
            Log.e("TextPreviewViewModel", "解析章节JSON失败", e)
        }
        return chapters
    }
}