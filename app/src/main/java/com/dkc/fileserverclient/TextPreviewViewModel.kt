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

    private var prevBlock: BlockData? = null
    private var currentBlock: BlockData? = null
    private var nextBlock: BlockData? = null

    private var pendingPrevConsumed: Int? = null
    private var pendingNextConsumed: Int? = null

    private var currentSubPage = 1
    private var totalSubPages = 1
    private val subPageBoundaries = mutableListOf<Pair<Int, Int>>()

    private var restoredBlockPage = 1
    private var isHistoryRestored = false
    private var pendingCharOffset: Int? = null

    private var cachedChapters: List<ChapterInfo>? = null

    private var isPreloadingPrev = false
    private var isPreloadingNext = false

    private val pageContentCache = mutableMapOf<String, CharSequence>()

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
        val endChar: Int,
        val consumedStartOffset: Int = 0
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
                rebuildSubPages(it)
                showCurrentSubPage()
            }
        }
    }

    fun onFontSizeChanged(newLinesPerPage: Int) {
        if (currentBlock == null) return
        val currentStartChar = getCurrentVisibleStartChar()
        linesPerPage = newLinesPerPage.coerceAtLeast(2)
        currentBlock?.let { block ->
            rebuildSubPages(block)
            currentSubPage = findSubPageForCharOffset(block, currentStartChar)
            showCurrentSubPage()
        }
    }

    private fun getCurrentVisibleStartChar(): Int {
        val block = currentBlock ?: return 0
        if (subPageBoundaries.isEmpty()) return block.startChar
        val (startLine, _) = subPageBoundaries.getOrElse(currentSubPage - 1) {
            return block.startChar
        }
        val layout = buildStaticLayout(block.fullText.substring(block.consumedStartOffset))
        return block.startChar + block.consumedStartOffset + layout.getLineStart(startLine)
    }

    fun restoreFromHistory(blockPage: Int, absoluteCharOffset: Int) {
        restoredBlockPage = blockPage
        isHistoryRestored = true
        pendingCharOffset = absoluteCharOffset
    }

    fun getCurrentPageState(): PageState? {
        return currentBlock?.let {
            PageState(it.blockPage, currentSubPage, it.totalBlockPages, totalSubPages)
        }
    }

    fun loadTextContent() {
        if (textWidth <= 0 || textPaint == null) return
        val targetPage = if (isHistoryRestored) restoredBlockPage else 1
        loadBlock(targetPage)
    }

    fun previousPage() {
        if (currentSubPage > 1) {
            currentSubPage--
            showCurrentSubPage()
        } else if (prevBlock != null) {
            switchToPrevBlock()
        } else if (currentBlock != null && currentBlock!!.blockPage > 1) {
            val consumed = pendingPrevConsumed ?: 0
            loadBlock(currentBlock!!.blockPage - 1, goToLastSubPage = true, consumedStartOffset = consumed)
        }
    }

    fun nextPage() {
        if (currentSubPage < totalSubPages) {
            currentSubPage++
            showCurrentSubPage()
        } else if (nextBlock != null) {
            switchToNextBlock()
        } else if (currentBlock != null && currentBlock!!.blockPage < currentBlock!!.totalBlockPages) {
            val consumed = pendingNextConsumed ?: 0
            loadBlock(currentBlock!!.blockPage + 1, goToFirstSubPage = true, consumedStartOffset = consumed)
        }
    }

    fun loadChapters() {
        viewModelScope.launch {
            _loadingState.value = LoadingState(true, "正在加载章节...")
            try {
                val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
                cachedChapters = list.sortedBy { it.startCharOffset }
                _chapters.value = list
                _showChapterDialogEvent.value = list

                if (currentBlock != null) {
                    val currentOffset = _currentAbsoluteCharOffset.value ?: currentBlock!!.startChar
                    rebuildSubPages(currentBlock!!)
                    currentSubPage = findSubPageForCharOffset(currentBlock!!, currentOffset)
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

    private fun loadBlock(
        page: Int,
        goToFirstSubPage: Boolean = false,
        goToLastSubPage: Boolean = false,
        consumedStartOffset: Int? = null
    ) {
        prevBlock = null
        nextBlock = null
        pendingPrevConsumed = null
        pendingNextConsumed = null

        _loadingState.value = LoadingState(true, "正在加载...")
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                var block = parseBlockResponse(json)

                if (consumedStartOffset != null && consumedStartOffset > 0) {
                    block = block.copy(consumedStartOffset = consumedStartOffset)
                }

                currentBlock = block
                ensureChaptersLoaded()

                withContext(Dispatchers.Default) {
                    rebuildSubPages(block)
                }

                val targetOffset = pendingCharOffset
                pendingCharOffset = null
                currentSubPage = when {
                    targetOffset != null -> findSubPageForCharOffset(block, targetOffset)
                    goToFirstSubPage -> 1
                    goToLastSubPage -> totalSubPages
                    else -> 1
                }

                showCurrentSubPage()
                _loadingState.value = LoadingState(false)

                if (page > 1 && prevBlock == null) preloadPrevBlock(page - 1)
                if (page < block.totalBlockPages && nextBlock == null) preloadNextBlock(page + 1)
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
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
                var block = parseBlockResponse(json)
                pendingPrevConsumed?.let { consumed ->
                    if (consumed > 0) block = block.copy(consumedStartOffset = consumed)
                    pendingPrevConsumed = null
                }
                if (currentBlock?.blockPage == page + 1) prevBlock = block
            } catch (e: Exception) {
                Log.e("ViewModel", "预加载上一块失败", e)
            } finally {
                isPreloadingPrev = false
            }
        }
    }

    private fun preloadNextBlock(page: Int) {
        if (isPreloadingNext) return
        isPreloadingNext = true
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                var block = parseBlockResponse(json)
                pendingNextConsumed?.let { consumed ->
                    if (consumed > 0) block = block.copy(consumedStartOffset = consumed)
                    pendingNextConsumed = null
                }
                if (currentBlock?.blockPage == page - 1) nextBlock = block
            } catch (e: Exception) {
                Log.e("ViewModel", "预加载下一块失败", e)
            } finally {
                isPreloadingNext = false
            }
        }
    }

    private fun switchToPrevBlock() {
        val block = prevBlock ?: return
        currentBlock = block.copy(consumedStartOffset = 0)
        prevBlock = null
        rebuildSubPages(currentBlock!!)
        currentSubPage = totalSubPages.coerceAtLeast(1)
        showCurrentSubPage()
        if (currentBlock!!.blockPage > 1) preloadPrevBlock(currentBlock!!.blockPage - 1)
        nextBlock = null
    }

    private fun switchToNextBlock() {
        val block = nextBlock ?: return
        currentBlock = block.copy(consumedStartOffset = 0)
        nextBlock = null
        rebuildSubPages(currentBlock!!)
        currentSubPage = 1
        showCurrentSubPage()
        if (currentBlock!!.blockPage < currentBlock!!.totalBlockPages) {
            preloadNextBlock(currentBlock!!.blockPage + 1)
        }
        prevBlock = null
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

    // ─── 修复后的 rebuildSubPages（防止空页） ───
    private fun rebuildSubPages(block: BlockData) {
        subPageBoundaries.clear()
        if (textWidth <= 0 || textPaint == null || block.fullText.isEmpty()) {
            totalSubPages = 1
            return
        }
        val effectiveText = block.fullText.substring(block.consumedStartOffset)
        if (effectiveText.isEmpty()) {
            totalSubPages = 1
            return
        }
        val layout = buildStaticLayout(effectiveText)
        val totalLines = layout.lineCount
        if (totalLines == 0) {
            totalSubPages = 1
            return
        }

        val chapterStartLines = mutableSetOf<Int>()
        if (cachedChapters != null) {
            val effectiveStartChar = block.startChar + block.consumedStartOffset
            val effectiveEndChar = block.endChar
            for (ch in cachedChapters!!) {
                if (ch.startCharOffset in effectiveStartChar until effectiveEndChar) {
                    val rel = ch.startCharOffset - effectiveStartChar
                    val line = layout.getLineForOffset(rel)
                    chapterStartLines.add(line)
                }
            }
        }

        var startLine = 0
        while (startLine < totalLines) {
            var endLine = minOf(startLine + linesPerPage, totalLines)

            // 如果 endLine 恰好是标题行且不是 startLine，退一行避免孤行
            if (endLine < totalLines && chapterStartLines.contains(endLine)) {
                if (endLine > startLine + 1) endLine--
            }

            // 在区间内若有标题行，提早断开
            for (line in startLine until endLine) {
                if (chapterStartLines.contains(line) && line != startLine) {
                    endLine = line
                    break
                }
            }

            // 避免最后留下极短的尾页
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
            }

            // 绝对保证本页至少一行（强制修复空页）
            if (endLine <= startLine) {
                endLine = minOf(startLine + 1, totalLines)
                Log.w("ViewModel", "强制修复空页: start=$startLine, end=$endLine, total=$totalLines")
            }

            subPageBoundaries.add(Pair(startLine, endLine))
            startLine = endLine
        }
        totalSubPages = subPageBoundaries.size
        Log.d("ViewModel", "分页完成: 总行=$totalLines, 子页数=$totalSubPages")
    }

    private fun findSubPageForCharOffset(block: BlockData, absCharOffset: Int): Int {
        val effectiveStart = block.startChar + block.consumedStartOffset
        val effectiveText = block.fullText.substring(block.consumedStartOffset)
        if (effectiveText.isEmpty() || subPageBoundaries.isEmpty()) return 1
        val relativeOffset = (absCharOffset - effectiveStart).coerceIn(0, effectiveText.length)
        val layout = buildStaticLayout(effectiveText)
        val line = layout.getLineForOffset(relativeOffset)
        for ((i, b) in subPageBoundaries.withIndex()) {
            if (line in b.first until b.second) return i + 1
        }
        return 1
    }

    private fun showCurrentSubPage() {
        val block = currentBlock ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val (spannable, offset) = generatePageContent(block, currentSubPage)
            _pageContent.postValue(spannable)
            _currentAbsoluteCharOffset.postValue(offset)
            withContext(Dispatchers.Main) { updatePageInfo() }
        }
    }

    // ─── 修复后的 generatePageContent（空内容防护） ───
    private suspend fun generatePageContent(
        block: BlockData,
        subPage: Int
    ): Pair<CharSequence, Int> {
        if (subPageBoundaries.isEmpty()) {
            val text = block.fullText.substring(block.consumedStartOffset)
            return Pair(text, block.startChar + block.consumedStartOffset)
        }
        val (startLine, endLine) = subPageBoundaries.getOrElse(subPage - 1) { subPageBoundaries.first() }
        val effectiveText = block.fullText.substring(block.consumedStartOffset)
        val layout = buildStaticLayout(effectiveText)
        val startCharLocal = layout.getLineStart(startLine)
        val endCharLocal = if (endLine >= layout.lineCount) effectiveText.length else layout.getLineStart(endLine)
        var pageText = effectiveText.substring(startCharLocal, minOf(endCharLocal, effectiveText.length))
        var pageAbsoluteStart = block.startChar + block.consumedStartOffset + startCharLocal

        // 向前补全（保留至少一行给 prevBlock）
        if (subPage == 1 && prevBlock != null && textPaint != null) {
            val currentLines = endLine - startLine
            if (currentLines < linesPerPage) {
                val needed = linesPerPage - currentLines
                val pBlock = prevBlock!!
                val pEffective = pBlock.fullText.substring(pBlock.consumedStartOffset)
                if (pEffective.isNotEmpty()) {
                    val pLayout = buildStaticLayout(pEffective)
                    val totalPrevLines = pLayout.lineCount
                    val maxBorrowLines = (totalPrevLines - 1).coerceAtLeast(0)
                    val actualBorrowLines = minOf(needed, maxBorrowLines)
                    if (actualBorrowLines > 0) {
                        val endLinePrev = totalPrevLines
                        val startLinePrev = (endLinePrev - actualBorrowLines).coerceAtLeast(0)
                        if (startLinePrev < endLinePrev) {
                            val sc = pLayout.getLineStart(startLinePrev)
                            val ec = if (endLinePrev < totalPrevLines) pLayout.getLineStart(endLinePrev) else pEffective.length
                            val prevPart = pEffective.substring(sc, ec)
                            pageText = prevPart + "\n" + pageText
                            val newConsumed = pBlock.consumedStartOffset + ec
                            prevBlock = prevBlock!!.copy(consumedStartOffset = newConsumed)
                            pendingPrevConsumed = newConsumed
                            pageAbsoluteStart = pBlock.startChar + pBlock.consumedStartOffset + sc
                        }
                    }
                }
            }
        }

        // 向后补全（保留至少一行给 nextBlock）
        if (subPage == totalSubPages && nextBlock != null && textPaint != null) {
            val currentLines = endLine - startLine
            if (currentLines < linesPerPage) {
                val needed = linesPerPage - currentLines
                val nBlock = nextBlock!!
                val nEffective = nBlock.fullText.substring(nBlock.consumedStartOffset)
                if (nEffective.isNotEmpty()) {
                    val nLayout = buildStaticLayout(nEffective)
                    val totalNextLines = nLayout.lineCount
                    val maxBorrowLines = (totalNextLines - 1).coerceAtLeast(0)
                    val actualBorrowLines = minOf(needed, maxBorrowLines)
                    if (actualBorrowLines > 0) {
                        val startLineNext = 0
                        val endLineNext = minOf(startLineNext + actualBorrowLines, totalNextLines)
                        if (endLineNext > startLineNext) {
                            val ns = nLayout.getLineStart(startLineNext)
                            val ne = if (endLineNext < totalNextLines) nLayout.getLineStart(endLineNext) else nEffective.length
                            val nextPart = nEffective.substring(ns, ne)
                            pageText = pageText + "\n" + nextPart
                            val newConsumed = nBlock.consumedStartOffset + ne
                            nextBlock = nextBlock!!.copy(consumedStartOffset = newConsumed)
                            pendingNextConsumed = newConsumed
                        }
                    }
                }
            }
        }

        // 最终空页保护：如果仍然生成了空字符串，用单个空格代替
        if (pageText.isEmpty()) {
            Log.e("ViewModel", "generatePageContent 生成了空页！block=${block.blockPage}, sub=$subPage, startLine=$startLine, endLine=$endLine")
            pageText = " "
            pageAbsoluteStart = block.startChar + block.consumedStartOffset
        }

        val spannable = applyChapterStylesForPage(pageText, pageAbsoluteStart)
        return Pair(spannable, pageAbsoluteStart)
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

    fun peekNextPageContent(callback: (CharSequence?) -> Unit) {
        val block = currentBlock ?: run { callback(null); return }
        val nextSubPage = currentSubPage + 1
        if (nextSubPage <= totalSubPages) {
            val cacheKey = "${block.blockPage}_$nextSubPage"
            val cached = pageContentCache[cacheKey]
            if (cached != null) {
                callback(cached)
            } else {
                viewModelScope.launch(Dispatchers.Default) {
                    val (content, _) = generatePageContent(block, nextSubPage)
                    pageContentCache[cacheKey] = content
                    callback(content)
                }
            }
        } else {
            callback(null)
        }
    }
}