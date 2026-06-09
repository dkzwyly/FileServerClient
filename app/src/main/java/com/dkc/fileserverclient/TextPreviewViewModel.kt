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

    private var windowText: String = ""
    private var blocksInWindow: List<BlockOffsetMapping> = emptyList()
    private var currentGlobalStartLine = 0
    private val pageBoundaries = mutableListOf<Pair<Int, Int>>()
    private var currentSubPage = 1

    private var windowStartBlockPage = 1
    private var windowEndBlockPage = 1
    private var totalServerBlocks = 1

    private var restoredBlockPage = 1
    private var isHistoryRestored = false
    private var pendingCharOffset: Int? = null

    private var cachedChapters: List<ChapterInfo>? = null
    private var isLoadingChapters = false
    private var isLoadingWindow = false

    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    data class PageInfo(val currentPage: Int, val totalPages: Int, val progress: Int)
    data class LoadingState(val isLoading: Boolean, val message: String? = null)
    data class ChapterInfo(
        val title: String,
        val serverPage: Int,
        val lineNumber: Int,
        val startCharOffset: Int
    )
    data class PageState(
        val blockPage: Int,
        val subPage: Int,
        val totalBlockPages: Int,
        val totalSubPages: Int
    )
    data class BlockData(
        val fullText: String,
        val blockPage: Int,
        val totalBlockPages: Int,
        val startChar: Int,
        val endChar: Int
    )
    data class BlockOffsetMapping(
        val blockPage: Int,
        val startChar: Int,
        val textStartInWindow: Int
    )

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
        val sampleLayout = buildStaticLayout("T\n")
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
        val safeLines = if (lines < 2) 20 else lines
        if (linesPerPage != safeLines) {
            linesPerPage = safeLines
            if (windowText.isNotEmpty()) {
                rebuildPagesFromCurrentWindow()
                showCurrentSubPage()
            }
        }
    }

    fun onFontSizeChanged(newLinesPerPage: Int) {
        if (windowText.isEmpty()) return
        linesPerPage = newLinesPerPage.coerceAtLeast(2)
        rebuildPagesFromCurrentWindow()
        val currentAbsOffset = _currentAbsoluteCharOffset.value ?: return
        currentSubPage = findSubPageForAbsoluteOffset(currentAbsOffset)
        showCurrentSubPage()
    }

    fun restoreFromHistory(blockPage: Int, absoluteCharOffset: Int) {
        restoredBlockPage = blockPage
        isHistoryRestored = true
        pendingCharOffset = absoluteCharOffset
    }

    fun getCurrentPageState(): PageState? {
        val blockPage = getBlockPageForCurrentSubPage()
        return PageState(
            blockPage = blockPage,
            subPage = currentSubPage,
            totalBlockPages = totalServerBlocks,
            totalSubPages = pageBoundaries.size
        )
    }

    fun loadTextContent() {
        if (textWidth <= 0 || textPaint == null) return
        val targetPage = if (isHistoryRestored) restoredBlockPage else 1
        loadWindow(centerBlockPage = targetPage)
    }

    fun previousPage() {
        if (currentSubPage > 1) {
            currentSubPage--
            showCurrentSubPage()
        } else {
            val newCenter = windowStartBlockPage - 1
            if (newCenter >= 1) {
                loadWindow(centerBlockPage = newCenter, goToEnd = true)
            }
        }
    }

    fun nextPage() {
        if (currentSubPage < pageBoundaries.size) {
            currentSubPage++
            showCurrentSubPage()
        } else {
            val newCenter = windowEndBlockPage + 1
            if (newCenter <= totalServerBlocks) {
                loadWindow(centerBlockPage = newCenter, goToStart = true)
            }
        }
    }

    fun loadChapters() {
        // 手动触发章节加载
        viewModelScope.launch {
            fetchAndApplyChapters()
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) {
        pendingCharOffset = chapter.startCharOffset
        loadWindow(centerBlockPage = chapter.serverPage)
    }

    // ─── 窗口加载与分页核心 ───

    private fun loadWindow(
        centerBlockPage: Int,
        goToStart: Boolean = false,
        goToEnd: Boolean = false
    ) {
        if (isLoadingWindow) return
        isLoadingWindow = true
        _loadingState.value = LoadingState(true, "加载中...")

        viewModelScope.launch {
            try {
                val pagesToLoad = mutableSetOf(centerBlockPage)
                if (centerBlockPage > 1) pagesToLoad.add(centerBlockPage - 1)
                pagesToLoad.add(centerBlockPage + 1)

                val blockMap = mutableMapOf<Int, BlockData>()
                for (page in pagesToLoad) {
                    try {
                        val url = buildBlockUrl(page)
                        val json = fetchJson(url)
                        val block = parseBlockResponse(json)
                        blockMap[page] = block
                        totalServerBlocks = block.totalBlockPages
                    } catch (e: Exception) {
                        Log.w("ViewModel", "加载块 $page 失败: ${e.message}")
                    }
                }

                val sortedPages = blockMap.keys.sorted()
                val selectedPages = mutableListOf<Int>()
                if (sortedPages.isNotEmpty()) {
                    val minPage = sortedPages.first()
                    val maxPage = sortedPages.last()
                    for (p in minPage..maxPage) {
                        if (p in blockMap) selectedPages.add(p) else break
                    }
                }

                if (selectedPages.isEmpty()) throw Exception("无法加载任何块")

                val sb = StringBuilder()
                val mappings = mutableListOf<BlockOffsetMapping>()
                for (page in selectedPages) {
                    val block = blockMap[page]!!
                    val startIndex = sb.length
                    sb.append(block.fullText)
                    mappings.add(
                        BlockOffsetMapping(
                            blockPage = page,
                            startChar = block.startChar,
                            textStartInWindow = startIndex
                        )
                    )
                }

                windowText = sb.toString()
                blocksInWindow = mappings
                windowStartBlockPage = selectedPages.first()
                windowEndBlockPage = selectedPages.last()

                rebuildPagesFromCurrentWindow()

                val targetOffset = pendingCharOffset
                pendingCharOffset = null
                currentSubPage = when {
                    targetOffset != null -> findSubPageForAbsoluteOffset(targetOffset)
                    goToStart -> 1
                    goToEnd -> pageBoundaries.size.coerceAtLeast(1)
                    else -> 1
                }

                showCurrentSubPage()
                _loadingState.value = LoadingState(false)

                // 不再自动加载章节，由外部手动调用 loadChapters()
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
            } finally {
                isLoadingWindow = false
            }
        }
    }

    private suspend fun fetchAndApplyChapters() {
        if (isLoadingChapters || cachedChapters != null) return
        isLoadingChapters = true
        try {
            val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
            cachedChapters = list.sortedBy { it.startCharOffset }
            _chapters.postValue(list)
            _showChapterDialogEvent.postValue(list)

            withContext(Dispatchers.Main) {
                if (windowText.isNotEmpty()) {
                    val currentOffset = _currentAbsoluteCharOffset.value
                    rebuildPagesFromCurrentWindow()
                    currentSubPage = when {
                        currentOffset != null -> findSubPageForAbsoluteOffset(currentOffset)
                        else -> currentSubPage.coerceIn(1, pageBoundaries.size)
                    }
                    showCurrentSubPage()
                }
            }
        } catch (e: Exception) {
            Log.e("ViewModel", "章节加载失败", e)
            _errorMessage.postValue("章节加载失败: ${e.message}")
        } finally {
            isLoadingChapters = false
        }
    }

    private fun rebuildPagesFromCurrentWindow() {
        pageBoundaries.clear()
        if (windowText.isEmpty() || textWidth <= 0 || textPaint == null) return

        val layout = buildStaticLayout(windowText)
        val totalLines = layout.lineCount
        if (totalLines == 0) return

        val chapterStartLines = mutableSetOf<Int>()
        cachedChapters?.let { chapters ->
            val windowStartAbs = getWindowStartAbsoluteOffset()
            for (ch in chapters) {
                val relativeOffset = ch.startCharOffset - windowStartAbs
                if (relativeOffset in 0..windowText.length) {
                    val line = layout.getLineForOffset(relativeOffset)
                    chapterStartLines.add(line)
                }
            }
        }

        var startLine = 0
        while (startLine < totalLines) {
            var endLine = minOf(startLine + linesPerPage, totalLines)

            if (endLine < totalLines && chapterStartLines.contains(endLine) && endLine > startLine + 1) {
                endLine--
            }
            for (line in startLine + 1 until endLine) {
                if (chapterStartLines.contains(line)) {
                    endLine = line
                    break
                }
            }
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
            }
            if (endLine <= startLine) {
                endLine = minOf(startLine + 1, totalLines)
            }

            pageBoundaries.add(Pair(startLine, endLine))
            startLine = endLine
        }

        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < pageBoundaries.size) {
                if (i == pageBoundaries.size - 1) break
                val (s, e) = pageBoundaries[i]
                val startChar = layout.getLineStart(s)
                val endChar = if (e >= totalLines) windowText.length else layout.getLineStart(e)
                val pageText = windowText.substring(startChar, endChar.coerceAtMost(windowText.length)).trim()
                if (pageText.isEmpty() && i > 0) {
                    val prev = pageBoundaries[i - 1]
                    pageBoundaries[i - 1] = Pair(prev.first, e)
                    pageBoundaries.removeAt(i)
                    changed = true
                } else if (pageText.isEmpty() && i == 0 && pageBoundaries.size > 1) {
                    val next = pageBoundaries[1]
                    pageBoundaries[1] = Pair(s, next.second)
                    pageBoundaries.removeAt(0)
                    changed = true
                } else {
                    i++
                }
            }
        }

        Log.d("ViewModel", "窗口分页: 总行=$totalLines, 页数=${pageBoundaries.size}, 章节标记=$chapterStartLines")
    }

    private fun findSubPageForAbsoluteOffset(absCharOffset: Int): Int {
        if (windowText.isEmpty() || pageBoundaries.isEmpty()) return 1
        val windowStartAbs = getWindowStartAbsoluteOffset()
        val relativeOffset = (absCharOffset - windowStartAbs).coerceIn(0, windowText.length)
        val layout = buildStaticLayout(windowText)
        val targetLine = layout.getLineForOffset(relativeOffset)
        for ((index, bound) in pageBoundaries.withIndex()) {
            if (targetLine in bound.first until bound.second) {
                return index + 1
            }
        }
        return 1
    }

    private fun getWindowStartAbsoluteOffset(): Int {
        return blocksInWindow.firstOrNull()?.startChar ?: 0
    }

    private fun showCurrentSubPage() {
        if (windowText.isEmpty() || pageBoundaries.isEmpty()) return
        val (startLine, endLine) = pageBoundaries.getOrElse(currentSubPage - 1) { pageBoundaries.first() }
        val layout = buildStaticLayout(windowText)
        val startChar = layout.getLineStart(startLine)
        val endChar = if (endLine >= layout.lineCount) windowText.length else layout.getLineStart(endLine)
        val pageText = windowText.substring(startChar, endChar.coerceAtMost(windowText.length))
        val absOffset = getWindowStartAbsoluteOffset() + startChar

        val spannable = applyChapterStylesForPage(pageText, absOffset)
        _pageContent.postValue(spannable)
        _currentAbsoluteCharOffset.postValue(absOffset)
        updatePageInfo()
    }

    private fun getBlockPageForCurrentSubPage(): Int {
        val (startLine, _) = pageBoundaries.getOrElse(currentSubPage - 1) { return currentBlockOrWindowCenter() }
        val layout = buildStaticLayout(windowText)
        val lineStartChar = layout.getLineStart(startLine)
        val absOffset = getWindowStartAbsoluteOffset() + lineStartChar
        for (mapping in blocksInWindow) {
            val blockEnd = if (mapping.blockPage == blocksInWindow.last().blockPage) {
                getWindowStartAbsoluteOffset() + windowText.length
            } else {
                blocksInWindow.firstOrNull { it.blockPage == mapping.blockPage + 1 }?.startChar ?: Int.MAX_VALUE
            }
            if (absOffset >= mapping.startChar && absOffset < blockEnd) {
                return mapping.blockPage
            }
        }
        return blocksInWindow.first().blockPage
    }

    private fun currentBlockOrWindowCenter(): Int {
        return windowStartBlockPage + (windowEndBlockPage - windowStartBlockPage) / 2
    }

    private fun applyChapterStylesForPage(pageText: String, pageAbsoluteStart: Int): CharSequence {
        val chapters = cachedChapters ?: return pageText
        if (chapters.isEmpty()) return pageText
        val spannable = SpannableString(pageText)
        for (ch in chapters) {
            val abs = ch.startCharOffset
            if (abs >= pageAbsoluteStart && abs < pageAbsoluteStart + pageText.length) {
                val start = abs - pageAbsoluteStart
                val len = minOf(ch.title.length, pageText.length - start)
                if (len > 0) {
                    val end = start + len
                    spannable.setSpan(
                        AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                        start, end,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        RelativeSizeSpan(1.5f),
                        start, end,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD),
                        start, end,
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return spannable
    }

    private fun updatePageInfo() {
        val currentServerPage = getBlockPageForCurrentSubPage()
        val progress = if (totalServerBlocks > 0) ((currentServerPage - 1) * 100 / totalServerBlocks) else 0
        _pageInfo.value = PageInfo(currentServerPage, totalServerBlocks, progress)
        _currentPageState.value = PageState(currentServerPage, currentSubPage, totalServerBlocks, pageBoundaries.size)
    }

    fun peekNextPageContent(callback: (CharSequence?) -> Unit) {
        val nextPage = currentSubPage + 1
        if (nextPage <= pageBoundaries.size) {
            val (startLine, endLine) = pageBoundaries[nextPage - 1]
            val layout = buildStaticLayout(windowText)
            val startChar = layout.getLineStart(startLine)
            val endChar = if (endLine >= layout.lineCount) windowText.length else layout.getLineStart(endLine)
            val content = windowText.substring(startChar, endChar.coerceAtMost(windowText.length))
            callback(content)
        } else {
            callback(null)
        }
    }

    // ─── 网络请求 ───

    private fun buildBlockUrl(page: Int): String {
        val base = if (fileUrl.contains("?")) "$fileUrl&" else "$fileUrl?"
        return "${base}page=$page"
    }

    private suspend fun fetchJson(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        response.body?.string() ?: throw Exception("空响应")
    }

    private fun parseBlockResponse(json: String): BlockData {
        val obj = JSONObject(json)
        val content = obj.getString("content")
        val pagination = obj.getJSONObject("pagination")
        return BlockData(
            fullText = content,
            blockPage = pagination.getInt("currentPage"),
            totalBlockPages = pagination.getInt("totalPages"),
            startChar = pagination.optInt("startChar", 0),
            endChar = pagination.optInt("endChar", content.length)
        )
    }

    private suspend fun fetchChaptersFromServer(): List<ChapterInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = if (fileUrl.contains("/preview/")) fileUrl.substringBefore("/preview/")
            else {
                val parts = fileUrl.split("/api/")
                if (parts.size > 1) "${parts[0]}/api" else fileUrl.substringBeforeLast("/")
            }
            val fileNameFromUrl = if (fileUrl.contains("/preview/")) fileUrl.substringAfter("/preview/").substringBefore("?") else fileName
            val encoded = URLEncoder.encode(fileNameFromUrl, "UTF-8")
            val chaptersUrl = "$baseUrl/chapters/$encoded"
            val request = Request.Builder().url(chaptersUrl).addHeader("Accept", "application/json").build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()
            parseChaptersJson(response.body?.string() ?: "")
        } catch (e: Exception) {
            Log.e("ViewModel", "章节请求失败", e)
            emptyList()
        }
    }

    private fun parseChaptersJson(json: String): List<ChapterInfo> {
        val list = mutableListOf<ChapterInfo>()
        try {
            val obj = JSONObject(json)
            var arr = obj.optJSONArray("chapters") ?: obj.optJSONObject("data")?.optJSONArray("chapters")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val ch = arr.getJSONObject(i)
                    list.add(
                        ChapterInfo(
                            title = ch.optString("title", "未知"),
                            serverPage = ch.optInt("page", 1),
                            lineNumber = ch.optInt("lineNumber", 0),
                            startCharOffset = ch.optInt("startCharOffset", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) { Log.e("ViewModel", "解析章节失败", e) }
        return list
    }
}