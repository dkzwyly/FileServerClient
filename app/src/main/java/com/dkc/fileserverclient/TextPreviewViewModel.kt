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

    // 三大页数据
    private var prevBlock: BlockData? = null
    private var currentBlock: BlockData? = null
    private var nextBlock: BlockData? = null

    // 用于跨页补全的消费偏移（记录被当前页显示的来自相邻块的开头/结尾字符数）
    private var prevBlockConsumedOffset = 0
    private var nextBlockConsumedOffset = 0

    private var currentSubPage = 1
    private var totalSubPages = 1
    private val subPageBoundaries = mutableListOf<Pair<Int, Int>>()

    private var restoredBlockPage = 1
    private var isHistoryRestored = false

    private var pendingCharOffset: Int? = null

    private var cachedChapters: List<ChapterInfo>? = null

    // 预加载状态
    private var isPreloadingPrev = false
    private var isPreloadingNext = false

    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

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
        if (subPageBoundaries.isEmpty()) return block.startChar
        val (startLine, _) = subPageBoundaries.getOrElse(currentSubPage - 1) { return block.startChar }
        val layout = buildStaticLayout(fullText)
        return block.startChar + layout.getLineStart(startLine)
    }

    /**
     * 通过绝对字符偏移恢复阅读位置，自动适应最新的分页规则（如标题断页）。
     */
    fun restoreFromHistory(blockPage: Int, absoluteCharOffset: Int) {
        restoredBlockPage = blockPage
        isHistoryRestored = true
        pendingCharOffset = absoluteCharOffset
    }

    fun getCurrentPageState(): PageState? {
        return currentBlock?.let { PageState(it.blockPage, currentSubPage, it.totalBlockPages, totalSubPages) }
    }

    fun loadTextContent() {
        if (textWidth <= 0 || textPaint == null) return
        val targetPage = if (isHistoryRestored) restoredBlockPage else 1
        loadBlock(targetPage)
    }

    // ─── 翻页逻辑（向前/向后） ───
    fun previousPage() {
        if (currentSubPage > 1) {
            currentSubPage--
            showCurrentSubPage()
        } else if (prevBlock != null) {
            // 当前块的第一子页，且上一块已预加载，则切换到上一块
            switchToPrevBlock()
        } else if (currentBlock != null && currentBlock!!.blockPage > 1) {
            // 没有预加载，请求上一块
            loadBlock(currentBlock!!.blockPage - 1, goToLastSubPage = true)
        }
    }

    fun nextPage() {
        if (currentSubPage < totalSubPages) {
            currentSubPage++
            showCurrentSubPage()
        } else if (nextBlock != null) {
            switchToNextBlock()
        } else if (currentBlock != null && currentBlock!!.blockPage < currentBlock!!.totalBlockPages) {
            loadBlock(currentBlock!!.blockPage + 1, goToFirstSubPage = true)
        }
    }

    // ─── 章节加载与跳转 ───
    fun loadChapters() {
        viewModelScope.launch {
            _loadingState.value = LoadingState(true, "正在加载章节...")
            try {
                val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
                cachedChapters = list.sortedBy { it.startCharOffset }
                _chapters.value = list
                _showChapterDialogEvent.value = list

                // 章节数据更新后，立即重建当前块的子页，使标题断页生效
                if (currentBlock != null) {
                    val currentOffset = _currentAbsoluteCharOffset.value ?: currentBlock!!.startChar
                    rebuildSubPages(currentBlock!!.fullText)
                    currentSubPage = findSubPageForCharOffset(
                        currentBlock!!.fullText,
                        currentBlock!!.startChar,
                        currentOffset
                    )
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

    // ─── 内部加载与预加载 ───
    private fun loadBlock(
        page: Int,
        goToFirstSubPage: Boolean = false,
        goToLastSubPage: Boolean = false
    ) {
        _loadingState.value = LoadingState(true, "正在加载...")
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                val block = parseBlockResponse(json)

                currentBlock = block

                // 👇 先加载章节数据，确保分页时 cachedChapters 可用
                ensureChaptersLoaded()

                // 👇 现在分页可以正确识别标题行
                rebuildSubPages(block.fullText)

                val targetOffset = pendingCharOffset
                pendingCharOffset = null
                currentSubPage = when {
                    targetOffset != null -> findSubPageForCharOffset(block.fullText, block.startChar, targetOffset)
                    goToFirstSubPage -> 1
                    goToLastSubPage -> totalSubPages
                    else -> 1
                }

                showCurrentSubPage()
                _loadingState.value = LoadingState(false)

                // 预加载相邻块
                if (page > 1 && prevBlock == null) preloadPrevBlock(page - 1)
                if (page < block.totalBlockPages && nextBlock == null) preloadNextBlock(page + 1)
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
                Log.e("ViewModel", "loadBlock error", e)
            }
        }
    }

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

    private fun preloadPrevBlock(page: Int) {
        if (isPreloadingPrev) return
        isPreloadingPrev = true
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                val block = parseBlockResponse(json)
                if (currentBlock?.blockPage == page + 1) prevBlock = block
            } catch (e: Exception) { Log.e("ViewModel", "预加载上一块失败", e) }
            finally { isPreloadingPrev = false }
        }
    }

    private fun preloadNextBlock(page: Int) {
        if (isPreloadingNext) return
        isPreloadingNext = true
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                val block = parseBlockResponse(json)
                if (currentBlock?.blockPage == page - 1) nextBlock = block
            } catch (e: Exception) { Log.e("ViewModel", "预加载下一块失败", e) }
            finally { isPreloadingNext = false }
        }
    }

    private fun switchToPrevBlock() {
        val block = prevBlock ?: return
        prevBlock = null
        // 因为先前的当前块的第一页可能已经消费了 prevBlock 的末尾（需用 prevBlockConsumedOffset 跳过）
        // 但换块时我们需要从 prevBlock 的末尾向前翻，实际上 previousPage 是从当前块的第一页回退到上一块的最后一页，
        // 这时 prevBlockConsumedOffset 表示本块开头消费了多少上一块的结尾，所以上一块的有效文本应该截掉末尾的 prevBlockConsumedOffset
        // 我们准备从上一块重新分页，并定位到最后一页
        val fullText = block.fullText
        val validLength = (fullText.length - prevBlockConsumedOffset).coerceAtLeast(1)
        val textForPagination = fullText.substring(0, validLength)
        currentBlock = block.copy(fullText = textForPagination, endChar = block.startChar + validLength)
        prevBlockConsumedOffset = 0  // 重置
        rebuildSubPages(textForPagination)
        currentSubPage = totalSubPages.coerceAtLeast(1)
        showCurrentSubPage()
        // 继续预加载更前一块
        if (block.blockPage > 1) preloadPrevBlock(block.blockPage - 1)
        // 旧当前块变成下一块
        val oldCurrent = currentBlock // 已赋值，但 currentBlock 现在是新的块
        // 实际旧当前块（现在已是 former block）应设为 nextBlock
        // 其实我们在 switchToPrevBlock 中已将 currentBlock 换成了 prevBlock，所以旧的 currentBlock 需要变成 nextBlock
        // 但旧 currentBlock 已经被覆盖，我们应从之前保存的引用获取？这里简单处理：丢失旧块，下次翻页再加载。
        nextBlock = null // 清除，后续会根据需要预加载
    }

    private fun switchToNextBlock() {
        val block = nextBlock ?: return
        nextBlock = null
        val consumed = nextBlockConsumedOffset
        nextBlockConsumedOffset = 0
        val text = if (consumed > 0 && consumed < block.fullText.length) {
            block.fullText.substring(consumed)
        } else {
            block.fullText
        }
        currentBlock = block.copy(
            fullText = text,
            startChar = block.startChar + consumed,
            endChar = block.endChar   // 注意 endChar 需调整？实际上 endChar 是原始块的结束偏移，但截掉开头后全文字符数变了，但用于章节定位的绝对偏移应使用原来的 startChar + 偏移，所以保持不变
        )
        rebuildSubPages(text)
        currentSubPage = 1
        showCurrentSubPage()
        if (block.blockPage < block.totalBlockPages) preloadNextBlock(block.blockPage + 1)
        // 旧当前块变成上一块
        prevBlock = null // 先清除，以后再预加载
    }

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
        val currentPage = pagination.getInt("currentPage")
        val totalPages = pagination.getInt("totalPages")
        val startChar = pagination.optInt("startChar", 0)
        val endChar = pagination.optInt("endChar", content.length)
        return BlockData(content, currentPage, totalPages, startChar, endChar)
    }

    // ─── 分页：强制章节标题位于页首 ───
    private fun rebuildSubPages(fullText: String) {
        subPageBoundaries.clear()
        if (textWidth <= 0 || textPaint == null || fullText.isEmpty()) {
            totalSubPages = 1
            return
        }
        val layout = buildStaticLayout(fullText)
        val totalLines = layout.lineCount
        if (totalLines == 0) {
            totalSubPages = 1
            return
        }
        val chapterStartLines = mutableSetOf<Int>()
        val block = currentBlock
        if (block != null && cachedChapters != null) {
            val blockStart = block.startChar
            val blockEnd = block.endChar
            for (ch in cachedChapters!!) {
                if (ch.startCharOffset in blockStart until blockEnd) {
                    val rel = ch.startCharOffset - blockStart
                    val line = layout.getLineForOffset(rel)
                    chapterStartLines.add(line)
                }
            }
        }
        var startLine = 0
        while (startLine < totalLines) {
            var endLine = minOf(startLine + linesPerPage, totalLines)
            // 如果标题行正好在边界或内部，强制从标题行开始新页
            if (endLine < totalLines && chapterStartLines.contains(endLine)) {
                if (endLine > startLine + 1) endLine--
            }
            for (line in startLine until endLine) {
                if (chapterStartLines.contains(line) && line != startLine) {
                    endLine = line
                    break
                }
            }
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
            }
            subPageBoundaries.add(Pair(startLine, endLine))
            startLine = endLine
        }
        totalSubPages = subPageBoundaries.size
        Log.d("ViewModel", "分页完成: 总行=$totalLines, 子页数=$totalSubPages")
    }

    private fun findSubPageForCharOffset(fullText: String, blockStartChar: Int, absCharOffset: Int): Int {
        if (fullText.isEmpty() || subPageBoundaries.isEmpty()) return 1
        val relativeOffset = (absCharOffset - blockStartChar).coerceIn(0, fullText.length)
        val layout = buildStaticLayout(fullText)
        val line = layout.getLineForOffset(relativeOffset)
        for ((i, b) in subPageBoundaries.withIndex()) {
            if (line in b.first until b.second) return i + 1
        }
        return 1
    }

    // ─── 显示与跨页补全 ───
    private fun showCurrentSubPage() {
        val block = currentBlock ?: return
        val fullText = block.fullText
        if (subPageBoundaries.isEmpty()) {
            _pageContent.value = fullText
            updatePageInfo()
            _currentAbsoluteCharOffset.value = block.startChar
            return
        }
        val (startLine, endLine) = subPageBoundaries.getOrElse(currentSubPage - 1) {
            currentSubPage = 1
            subPageBoundaries.first()
        }
        val layout = buildStaticLayout(fullText)
        val startChar = layout.getLineStart(startLine)
        val endChar = if (endLine >= layout.lineCount) fullText.length else layout.getLineStart(endLine)
        var pageText = fullText.substring(startChar, minOf(endChar, fullText.length))
        var pageAbsoluteStart = block.startChar + startChar

        // 第一子页向前补全
        if (currentSubPage == 1 && prevBlock != null && textPaint != null) {
            val currentLines = endLine - startLine
            if (currentLines < linesPerPage) {
                val needed = linesPerPage - currentLines
                val prevFull = prevBlock!!.fullText
                if (prevFull.isNotEmpty()) {
                    val prevLayout = buildStaticLayout(prevFull)
                    val totalPrevLines = prevLayout.lineCount
                    // 上一块被消费的偏移：从前一块的末尾扣除部分（prevBlockConsumedOffset 表示上一块末尾被当前块第一页使用的字符数）
                    val validLength = (prevFull.length - prevBlockConsumedOffset).coerceAtLeast(1)
                    val endLinePrev = totalPrevLines
                    val startLinePrev = (endLinePrev - needed).coerceAtLeast(0)
                    if (startLinePrev < endLinePrev) {
                        val startCharPrev = prevLayout.getLineStart(startLinePrev)
                        val endCharPrev = if (endLinePrev < totalPrevLines) prevLayout.getLineStart(endLinePrev) else prevFull.length
                        val prevPart = prevFull.substring(startCharPrev, endCharPrev)
                        pageText = prevPart + "\n" + pageText
                        pageAbsoluteStart = prevBlock!!.startChar + startCharPrev
                        prevBlockConsumedOffset = endCharPrev
                    }
                }
            }
        }

        // 最后一子页向后补全
        if (currentSubPage == totalSubPages && nextBlock != null && textPaint != null) {
            val currentLines = endLine - startLine
            if (currentLines < linesPerPage) {
                val needed = linesPerPage - currentLines
                val nextFull = nextBlock!!.fullText
                if (nextFull.isNotEmpty()) {
                    val nextLayout = buildStaticLayout(nextFull)
                    val totalNextLines = nextLayout.lineCount
                    val startLineNext = nextLayout.getLineForOffset(
                        nextBlockConsumedOffset.coerceIn(0, nextFull.length)
                    )
                    val endLineNext = minOf(startLineNext + needed, totalNextLines)
                    if (endLineNext > startLineNext) {
                        val ns = nextLayout.getLineStart(startLineNext)
                        val ne = if (endLineNext < totalNextLines) nextLayout.getLineStart(endLineNext) else nextFull.length
                        val nextPart = nextFull.substring(ns, ne)
                        pageText = pageText + "\n" + nextPart
                        nextBlockConsumedOffset = ne
                    }
                }
            }
        }

        val spannable = applyChapterStylesForPage(pageText, pageAbsoluteStart)
        _pageContent.value = spannable
        updatePageInfo()
        _currentAbsoluteCharOffset.value = pageAbsoluteStart
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
                    spannable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(RelativeSizeSpan(1.5f), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return spannable
    }

    private fun updatePageInfo() {
        currentBlock?.let { block ->
            val progress = if (block.totalBlockPages > 0) ((block.blockPage - 1) * 100 / block.totalBlockPages) else 0
            _pageInfo.value = PageInfo(block.blockPage, block.totalBlockPages, progress)
            _currentPageState.value = PageState(block.blockPage, currentSubPage, block.totalBlockPages, totalSubPages)
        }
    }

    // ─── 章节请求（不变） ───
    private suspend fun fetchChaptersFromServer(): List<ChapterInfo> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = if (fileUrl.contains("/preview/")) fileUrl.substringBefore("/preview/")
            else {
                val parts = fileUrl.split("/api/")
                if (parts.size > 1) "${parts[0]}/api" else fileUrl.substringBeforeLast("/")
            }
            val fileNameFromUrl = if (fileUrl.contains("/preview/")) fileUrl.substringAfter("/preview/").substringBefore("?")
            else fileName
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
                    list.add(ChapterInfo(
                        title = ch.optString("title", "未知"),
                        serverPage = ch.optInt("page", 1),
                        lineNumber = ch.optInt("lineNumber", 0),
                        startCharOffset = ch.optInt("startCharOffset", 0)
                    ))
                }
            }
        } catch (e: Exception) { Log.e("ViewModel", "解析章节失败", e) }
        return list
    }
}