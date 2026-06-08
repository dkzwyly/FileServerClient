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

    // 跨页补全时的临时偏移存储（当目标块尚未加载时使用）
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

    // 页面内容缓存，键格式："{blockPage}_{subPage}"
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
        val consumedStartOffset: Int = 0   // 该块已被前页消费的字符数（相对于fullText）
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
        val fullText = block.fullText
        if (subPageBoundaries.isEmpty()) return block.startChar
        val (startLine, _) = subPageBoundaries.getOrElse(currentSubPage - 1) {
            return block.startChar
        }
        val layout = buildStaticLayout(fullText)
        return block.startChar + layout.getLineStart(startLine)
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

    // ─── 翻页逻辑 ───
    fun previousPage() {
        if (currentSubPage > 1) {
            currentSubPage--
            showCurrentSubPage()
        } else if (prevBlock != null) {
            switchToPrevBlock()
        } else if (currentBlock != null && currentBlock!!.blockPage > 1) {
            // 预加载未就绪，需网络加载并携带消费偏移
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
            // 预加载未就绪，需网络加载并携带消费偏移
            val consumed = pendingNextConsumed ?: 0
            loadBlock(currentBlock!!.blockPage + 1, goToFirstSubPage = true, consumedStartOffset = consumed)
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

                if (currentBlock != null) {
                    val currentOffset =
                        _currentAbsoluteCharOffset.value ?: currentBlock!!.startChar
                    rebuildSubPages(currentBlock!!)
                    currentSubPage = findSubPageForCharOffset(
                        currentBlock!!,
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

    // ─── 预加载/切换块 ───
    private fun loadBlock(
        page: Int,
        goToFirstSubPage: Boolean = false,
        goToLastSubPage: Boolean = false,
        consumedStartOffset: Int? = null
    ) {
        _loadingState.value = LoadingState(true, "正在加载...")
        viewModelScope.launch {
            try {
                val url = buildBlockUrl(page)
                val json = fetchJson(url)
                var block = parseBlockResponse(json)

                // 应用消费偏移
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
                // 应用待处理的消费偏移
                pendingPrevConsumed?.let { consumed ->
                    if (consumed > 0) {
                        block = block.copy(consumedStartOffset = consumed)
                    }
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
                // 应用待处理的消费偏移
                pendingNextConsumed?.let { consumed ->
                    if (consumed > 0) {
                        block = block.copy(consumedStartOffset = consumed)
                    }
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
        // 已通过consumedStartOffset标记了被消费的部分，直接作为有效块使用
        // 注意：prevBlock的consumedStartOffset已经在补全或加载时设置
        currentBlock = block.copy(consumedStartOffset = 0) // 切换后重置，避免二次消费
        prevBlock = null
        rebuildSubPages(currentBlock!!)
        currentSubPage = totalSubPages.coerceAtLeast(1)
        showCurrentSubPage()
        if (currentBlock!!.blockPage > 1) preloadPrevBlock(currentBlock!!.blockPage - 1)
        nextBlock = null
    }

    private fun switchToNextBlock() {
        val block = nextBlock ?: return
        // nextBlock的consumedStartOffset已在补全或加载时设置
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

    // ─── 分页（含标题断页） ───
    private fun rebuildSubPages(block: BlockData) {
        subPageBoundaries.clear()
        if (textWidth <= 0 || textPaint == null || block.fullText.isEmpty()) {
            totalSubPages = 1
            return
        }
        // 考虑到consumedStartOffset，有效文本的起始位置
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
        // 章节信息仍然基于绝对偏移，需要映射到有效文本的局部行号
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
            // 避免标题孤行：如果endLine正好是一个章节起始行，且不是startLine，则回退一行
            if (endLine < totalLines && chapterStartLines.contains(endLine)) {
                if (endLine > startLine + 1) endLine--
            }
            // 在[startLine, endLine)之间如果存在章节起始行，则在这一行断开
            for (line in startLine until endLine) {
                if (chapterStartLines.contains(line) && line != startLine) {
                    endLine = line
                    break
                }
            }
            // 避免最后剩一行孤行
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
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

    // ─── 显示当前子页及跨页补全 ───
    private fun showCurrentSubPage() {
        val block = currentBlock ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val (spannable, offset) = generatePageContent(block, currentSubPage)
            _pageContent.postValue(spannable)
            _currentAbsoluteCharOffset.postValue(offset)
            withContext(Dispatchers.Main) {
                updatePageInfo()
            }
        }
    }

    // 核心方法：生成页面内容并返回绝对偏移
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

        // 第一子页向前补全
        if (subPage == 1 && prevBlock != null && textPaint != null) {
            val currentLines = endLine - startLine
            if (currentLines < linesPerPage) {
                val needed = linesPerPage - currentLines
                val pBlock = prevBlock!!
                val pEffective = pBlock.fullText.substring(pBlock.consumedStartOffset)
                if (pEffective.isNotEmpty()) {
                    val pLayout = buildStaticLayout(pEffective)
                    val totalPrevLines = pLayout.lineCount
                    val endLinePrev = totalPrevLines
                    val startLinePrev = (endLinePrev - needed).coerceAtLeast(0)
                    if (startLinePrev < endLinePrev) {
                        val sc = pLayout.getLineStart(startLinePrev)
                        val ec = if (endLinePrev < totalPrevLines) pLayout.getLineStart(endLinePrev) else pEffective.length
                        val prevPart = pEffective.substring(sc, ec)
                        pageText = prevPart + "\n" + pageText
                        val consumedAbsoluteEnd = pBlock.startChar + pBlock.consumedStartOffset + ec
                        // 更新prevBlock的consumedStartOffset（相对于其fullText的原生偏移量）
                        val newConsumed = pBlock.consumedStartOffset + ec
                        if (prevBlock != null) {
                            prevBlock = prevBlock!!.copy(consumedStartOffset = newConsumed)
                        } else {
                            // 理论上不会进入这里
                        }
                        pendingPrevConsumed = newConsumed // 保存到pending，供后续预加载使用
                        pageAbsoluteStart = pBlock.startChar + pBlock.consumedStartOffset + sc
                    }
                }
            }
        }

        // 最后一子页向后补全
        if (subPage == totalSubPages && nextBlock != null && textPaint != null) {
            val currentLines = endLine - startLine
            if (currentLines < linesPerPage) {
                val needed = linesPerPage - currentLines
                val nBlock = nextBlock!!
                val nEffective = nBlock.fullText.substring(nBlock.consumedStartOffset)
                if (nEffective.isNotEmpty()) {
                    val nLayout = buildStaticLayout(nEffective)
                    val totalNextLines = nLayout.lineCount
                    val startLineNext = 0
                    val endLineNext = minOf(startLineNext + needed, totalNextLines)
                    if (endLineNext > startLineNext) {
                        val ns = nLayout.getLineStart(startLineNext)
                        val ne = if (endLineNext < totalNextLines) nLayout.getLineStart(endLineNext) else nEffective.length
                        val nextPart = nEffective.substring(ns, ne)
                        pageText = pageText + "\n" + nextPart
                        // 更新nextBlock的consumedStartOffset
                        val newConsumed = nBlock.consumedStartOffset + ne
                        nextBlock = nextBlock!!.copy(consumedStartOffset = newConsumed)
                        pendingNextConsumed = newConsumed
                    }
                }
            }
        }

        val spannable = applyChapterStylesForPage(pageText, pageAbsoluteStart)
        return Pair(spannable, pageAbsoluteStart)
    }

    private fun applyChapterStylesForPage(
        pageText: String,
        pageAbsoluteStart: Int
    ): CharSequence {
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
                        start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        RelativeSizeSpan(1.5f),
                        start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        StyleSpan(android.graphics.Typeface.BOLD),
                        start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return spannable
    }

    private fun updatePageInfo() {
        currentBlock?.let { block ->
            val progress =
                if (block.totalBlockPages > 0) ((block.blockPage - 1) * 100 / block.totalBlockPages) else 0
            _pageInfo.value = PageInfo(block.blockPage, block.totalBlockPages, progress)
            _currentPageState.value =
                PageState(block.blockPage, currentSubPage, block.totalBlockPages, totalSubPages)
        }
    }

    // ─── 章节网络请求 ───
    private suspend fun fetchChaptersFromServer(): List<ChapterInfo> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = if (fileUrl.contains("/preview/")) fileUrl.substringBefore("/preview/")
                else {
                    val parts = fileUrl.split("/api/")
                    if (parts.size > 1) "${parts[0]}/api" else fileUrl.substringBeforeLast("/")
                }
                val fileNameFromUrl =
                    if (fileUrl.contains("/preview/")) fileUrl.substringAfter("/preview/")
                        .substringBefore("?") else fileName
                val encoded = URLEncoder.encode(fileNameFromUrl, "UTF-8")
                val chaptersUrl = "$baseUrl/chapters/$encoded"
                val request = Request.Builder().url(chaptersUrl)
                    .addHeader("Accept", "application/json").build()
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
            var arr = obj.optJSONArray("chapters")
                ?: obj.optJSONObject("data")?.optJSONArray("chapters")
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
        } catch (e: Exception) {
            Log.e("ViewModel", "解析章节失败", e)
        }
        return list
    }

    // 为预加载提供的方法
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
            // 跨块预加载暂不实现，返回null
            callback(null)
        }
    }
}