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
import kotlin.math.min

class TextPreviewViewModel : ViewModel() {

    companion object {
        const val WINDOW_PADDING_PAGES = 1  // 窗口前后至少保留的块数，实际通过行数阈值控制
    }

    // ── 公开状态 ──
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

    // ── 文件信息 ──
    private lateinit var fileName: String
    private lateinit var fileUrl: String
    private lateinit var filePath: String

    // ── 显示参数 ──
    private var textWidth = 0
    private var textPaint: TextPaint? = null
    private var lineSpacingExtra = 0f
    private var lineSpacingMultiplier = 1f
    private var linesPerPage = 20

    // ── 分段布局核心 ──
    private val blockCache = mutableMapOf<Int, CachedBlock>()
    private var windowBlocks = listOf<CachedBlock>()
    private var globalLines = listOf<LineInfo>()
    private var currentPageIndex = 0
    private var totalServerBlocks = 1

    // ── 章节数据 ──
    private var cachedChapters: List<ChapterInfo>? = null
    private var isLoadingChapters = false

    // ── 其他状态 ──
    private var isInitialWindowLoaded = false
    private var pendingCharOffset: Int? = null
    private var isHistoryRestored = false
    private var restoredBlockPage = 1

    // OkHttp客户端
    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    // ── 公开数据类 ──
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

    private data class BlockData(
        val fullText: String,
        val blockPage: Int,
        val totalBlockPages: Int,
        val startChar: Int,
        val endChar: Int
    )

    private data class CachedBlock(
        val blockPage: Int,
        val fullText: String,
        val startCharAbs: Int,
        val layout: StaticLayout
    )

    private data class LineInfo(
        val cachedBlock: CachedBlock,
        val lineIndexInBlock: Int,
        val absoluteStartChar: Int
    )

    // ── 公开初始化方法 ──
    fun initialize(fileName: String, fileUrl: String, filePath: String) {
        this.fileName = fileName
        this.fileUrl = fileUrl
        this.filePath = filePath
    }

    fun setDisplayParams(width: Int, paint: Paint, extra: Float, multiplier: Float) {
        val needRelayout = (this.textWidth != width || this.textPaint?.textSize != paint.textSize ||
                this.lineSpacingExtra != extra || this.lineSpacingMultiplier != multiplier)
        this.textWidth = width
        this.textPaint = TextPaint(paint)
        this.lineSpacingExtra = extra
        this.lineSpacingMultiplier = multiplier
        if (needRelayout && isInitialWindowLoaded) {
            invalidateAllLayouts()
            rebuildWindowAndShow()
        }
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
            if (isInitialWindowLoaded) {
                rebuildWindowAndShow()
            }
        }
    }

    fun onFontSizeChanged(newLinesPerPage: Int) {
        if (isInitialWindowLoaded) {
            linesPerPage = newLinesPerPage.coerceAtLeast(2)
            rebuildWindowAndShow()
        }
    }

    fun restoreFromHistory(blockPage: Int, absoluteCharOffset: Int) {
        pendingCharOffset = absoluteCharOffset
        if (isInitialWindowLoaded) {
            navigateToGlobalOffset(absoluteCharOffset, centerBlockHint = blockPage)
        } else {
            isHistoryRestored = true
            restoredBlockPage = blockPage
        }
    }

    fun getCurrentPageState(): PageState? {
        if (!isInitialWindowLoaded || windowBlocks.isEmpty()) return null
        val curPageBlock = getBlockPageForCurrentGlobalPosition()
        val totalSubPages = if (globalLines.isEmpty() || linesPerPage <= 0) 0
        else (globalLines.size + linesPerPage - 1) / linesPerPage
        return PageState(
            blockPage = curPageBlock,
            subPage = currentPageIndex + 1,
            totalBlockPages = totalServerBlocks,
            totalSubPages = totalSubPages
        )
    }

    fun loadTextContent() {
        if (textWidth <= 0 || textPaint == null) return
        val centerBlock = if (isHistoryRestored) restoredBlockPage else 1
        isHistoryRestored = false
        loadWindow(centerBlockPage = centerBlock)
    }

    // ── 窗口管理 ──
    private fun loadWindow(centerBlockPage: Int) {
        _loadingState.value = LoadingState(true, "加载中...")
        viewModelScope.launch {
            try {
                // 初始加载范围扩大，保证充足边界
                val pagesToLoad = ((centerBlockPage - 2)..(centerBlockPage + 2))
                    .filter { it >= 1 }
                    .toSet()
                val newBlocks = mutableListOf<CachedBlock>()
                for (page in pagesToLoad) {
                    val cached = blockCache[page] ?: fetchAndCacheBlock(page)
                    newBlocks.add(cached)
                }
                windowBlocks = newBlocks.sortedBy { it.blockPage }
                rebuildGlobalLines()
                currentPageIndex = if (pendingCharOffset != null) {
                    val idx = findPageForAbsoluteOffset(pendingCharOffset!!)
                    pendingCharOffset = null
                    idx
                } else 0
                isInitialWindowLoaded = true
                showCurrentPage()
                checkAndPreload()  // 显示后立即检查预加载
                _loadingState.value = LoadingState(false)
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
            }
        }
    }

    private suspend fun fetchAndCacheBlock(page: Int): CachedBlock {
        val url = buildBlockUrl(page)
        val json = withContext(Dispatchers.IO) { fetchJson(url) }
        val blockData = withContext(Dispatchers.IO) { parseBlockResponse(json) }
        totalServerBlocks = blockData.totalBlockPages
        val layout = withContext(Dispatchers.IO) {
            buildStaticLayout(blockData.fullText)
        }
        val cached = CachedBlock(
            blockPage = blockData.blockPage,
            fullText = blockData.fullText,
            startCharAbs = blockData.startChar,
            layout = layout
        )
        blockCache[page] = cached
        trimCache()
        return cached
    }

    private fun trimCache() {
        if (windowBlocks.isEmpty()) return
        val minPage = windowBlocks.minOf { it.blockPage } - 2
        val maxPage = windowBlocks.maxOf { it.blockPage } + 2
        val toRemove = blockCache.keys.filter { it < minPage || it > maxPage }
        toRemove.forEach { blockCache.remove(it) }
    }

    private fun rebuildGlobalLines() {
        val lines = mutableListOf<LineInfo>()
        for (block in windowBlocks) {
            val layout = block.layout
            for (i in 0 until layout.lineCount) {
                val lineStart = layout.getLineStart(i)
                val absStart = block.startCharAbs + lineStart
                lines.add(LineInfo(block, i, absStart))
            }
        }
        globalLines = lines
    }

    // 核心：智能预加载，基于当前页偏移与窗口边界的行数距离判断
    private fun checkAndPreload() {
        if (!isInitialWindowLoaded || globalLines.isEmpty()) return

        val firstBlock = windowBlocks.firstOrNull() ?: return
        val lastBlock = windowBlocks.lastOrNull() ?: return

        val lp = this.linesPerPage
        val curPageStartLine = currentPageIndex * lp  // 当前页第一行索引

        // 检查头部：当前页第一行距离窗口第一行不足一页？
        if (curPageStartLine < lp && firstBlock.blockPage > 1) {
            val prevPage = firstBlock.blockPage - 1
            if (!blockCache.containsKey(prevPage)) {
                viewModelScope.launch(Dispatchers.IO) {
                    fetchAndCacheBlock(prevPage)
                    // 如果窗口头部未变，插入新块并重建全局行表
                    if (windowBlocks.firstOrNull()?.blockPage == firstBlock.blockPage) {
                        withContext(Dispatchers.Main) {
                            windowBlocks = listOf(blockCache[prevPage]!!) + windowBlocks
                            rebuildGlobalLines()
                            // 头部增加行后，当前页索引需后移以保持偏移不变
                            val offset = _currentAbsoluteCharOffset.value ?: 0
                            currentPageIndex = findPageForAbsoluteOffset(offset)
                            showCurrentPage()
                            // 扩展后再次检查，可能还需要继续预加载
                            checkAndPreload()
                        }
                    }
                }
            }
        }

        // 检查尾部：当前页最后一行距离窗口最后一行不足一页？
        val lastLineIdx = min((currentPageIndex + 1) * lp, globalLines.size) - 1
        val distanceToEnd = globalLines.size - lastLineIdx
        if (distanceToEnd < lp && lastBlock.blockPage < totalServerBlocks) {
            val nextPage = lastBlock.blockPage + 1
            if (!blockCache.containsKey(nextPage)) {
                viewModelScope.launch(Dispatchers.IO) {
                    fetchAndCacheBlock(nextPage)
                    if (windowBlocks.lastOrNull()?.blockPage == lastBlock.blockPage) {
                        withContext(Dispatchers.Main) {
                            windowBlocks = windowBlocks + blockCache[nextPage]!!
                            rebuildGlobalLines()
                            // 尾部增长不影响当前页索引，直接刷新显示即可
                            showCurrentPage()
                            checkAndPreload()
                        }
                    }
                }
            }
        }
    }

    // 滑动窗口方法变为轻量级，优先使用已预加载的块
    private suspend fun slideWindowForward(nextBlockPage: Int) {
        val existing = blockCache[nextBlockPage]
        if (existing != null) {
            windowBlocks = windowBlocks.drop(1) + existing
            rebuildGlobalLines()
            val targetPage = findPageForAbsoluteOffset(_currentAbsoluteCharOffset.value ?: 0)
            currentPageIndex = (targetPage + 1).coerceAtMost((globalLines.size + linesPerPage - 1) / linesPerPage - 1)
            showCurrentPage()
            checkAndPreload()
        } else {
            _loadingState.value = LoadingState(true, "加载中...")
            try {
                val newBlock = fetchAndCacheBlock(nextBlockPage)
                windowBlocks = windowBlocks.drop(1) + newBlock
                rebuildGlobalLines()
                val lastReadOffset = _currentAbsoluteCharOffset.value ?: 0
                val targetPage = findPageForAbsoluteOffset(lastReadOffset)
                currentPageIndex = (targetPage + 1).coerceAtMost((globalLines.size + linesPerPage - 1) / linesPerPage - 1)
                showCurrentPage()
                checkAndPreload()
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
            } finally {
                _loadingState.value = LoadingState(false)
            }
        }
    }

    private suspend fun slideWindowBackward(prevBlockPage: Int) {
        val existing = blockCache[prevBlockPage]
        if (existing != null) {
            windowBlocks = listOf(existing) + windowBlocks.dropLast(1)
            rebuildGlobalLines()
            val lastReadOffset = _currentAbsoluteCharOffset.value ?: 0
            val targetPage = findPageForAbsoluteOffset(lastReadOffset)
            currentPageIndex = (targetPage - 1).coerceAtLeast(0)
            showCurrentPage()
            checkAndPreload()
        } else {
            _loadingState.value = LoadingState(true, "加载中...")
            try {
                val newBlock = fetchAndCacheBlock(prevBlockPage)
                windowBlocks = listOf(newBlock) + windowBlocks.dropLast(1)
                rebuildGlobalLines()
                val lastReadOffset = _currentAbsoluteCharOffset.value ?: 0
                val targetPage = findPageForAbsoluteOffset(lastReadOffset)
                currentPageIndex = (targetPage - 1).coerceAtLeast(0)
                showCurrentPage()
                checkAndPreload()
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
            } finally {
                _loadingState.value = LoadingState(false)
            }
        }
    }

    private fun navigateToGlobalOffset(absoluteOffset: Int, centerBlockHint: Int? = null) {
        if (!isInitialWindowLoaded) {
            pendingCharOffset = absoluteOffset
            return
        }
        if (isOffsetInWindow(absoluteOffset)) {
            currentPageIndex = findPageForAbsoluteOffset(absoluteOffset)
            showCurrentPage()
        } else {
            val targetBlock = centerBlockHint ?: guessBlockForOffset(absoluteOffset)
            pendingCharOffset = absoluteOffset
            loadWindow(centerBlockPage = targetBlock)
        }
    }

    private fun isOffsetInWindow(absoluteOffset: Int): Boolean {
        if (globalLines.isEmpty()) return false
        val firstStart = globalLines.first().absoluteStartChar
        val lastEnd = (globalLines.last().let {
            val layout = it.cachedBlock.layout
            it.cachedBlock.startCharAbs + layout.getLineEnd(it.lineIndexInBlock)
        })
        return absoluteOffset in firstStart until lastEnd
    }

    private fun guessBlockForOffset(absoluteOffset: Int): Int {
        val cached = blockCache.values.sortedBy { it.startCharAbs }
        for (block in cached) {
            val end = block.startCharAbs + block.fullText.length
            if (absoluteOffset in block.startCharAbs..end) {
                return block.blockPage
            }
        }
        return windowBlocks.firstOrNull()?.blockPage ?: 1
    }

    private fun findPageForAbsoluteOffset(absoluteOffset: Int): Int {
        if (globalLines.isEmpty()) return 0
        var low = 0
        var high = globalLines.size - 1
        var targetLine = 0
        while (low <= high) {
            val mid = (low + high) / 2
            val lineStart = globalLines[mid].absoluteStartChar
            val lineEnd = if (mid + 1 < globalLines.size) globalLines[mid + 1].absoluteStartChar
            else {
                val lastBlock = globalLines[mid].cachedBlock
                lastBlock.startCharAbs + lastBlock.fullText.length
            }
            if (absoluteOffset >= lineEnd) {
                low = mid + 1
            } else if (absoluteOffset < lineStart) {
                high = mid - 1
            } else {
                targetLine = mid
                break
            }
        }
        if (low > high) targetLine = low.coerceIn(0, globalLines.size - 1)
        return targetLine / linesPerPage
    }

    // ── 显示当前页 ──
    private fun showCurrentPage() {
        if (!isInitialWindowLoaded || globalLines.isEmpty()) return
        val content = buildPageContent(currentPageIndex)
        _pageContent.value = content
        val firstLineIdx = currentPageIndex * linesPerPage
        if (firstLineIdx < globalLines.size) {
            _currentAbsoluteCharOffset.value = globalLines[firstLineIdx].absoluteStartChar
        }
        updatePageInfo()
        checkAndPreload()  // 每次显示后自动触发预加载检查
    }

    private fun buildPageContent(pageIndex: Int): CharSequence {
        val startLine = pageIndex * linesPerPage
        val endLine = min(startLine + linesPerPage, globalLines.size)
        if (startLine >= globalLines.size) return ""
        val firstBlock = globalLines[startLine].cachedBlock
        val layout = firstBlock.layout
        val blockStartChar = layout.getLineStart(globalLines[startLine].lineIndexInBlock)
        val lastLineInfo = globalLines[endLine - 1]
        val lastLayout = lastLineInfo.cachedBlock.layout
        val blockEndChar = if (lastLineInfo.lineIndexInBlock + 1 < lastLayout.lineCount) {
            lastLayout.getLineStart(lastLineInfo.lineIndexInBlock + 1)
        } else {
            lastLineInfo.cachedBlock.fullText.length
        }
        val pageStartAbs = globalLines[startLine].absoluteStartChar
        val pageEndAbs = if (endLine < globalLines.size) globalLines[endLine].absoluteStartChar
        else {
            val lastBlock = globalLines.last().cachedBlock
            lastBlock.startCharAbs + lastBlock.fullText.length
        }
        val sb = StringBuilder()
        for (block in windowBlocks) {
            val blockStart = block.startCharAbs
            val blockEnd = blockStart + block.fullText.length
            if (blockEnd <= pageStartAbs || blockStart >= pageEndAbs) continue
            val startInBlock = (pageStartAbs - blockStart).coerceAtLeast(0)
            val endInBlock = (pageEndAbs - blockStart).coerceAtMost(block.fullText.length)
            sb.append(block.fullText.substring(startInBlock, endInBlock))
        }
        val pageText = sb.toString()
        return applyChapterStyles(pageText, pageStartAbs)
    }

    private fun applyChapterStyles(text: String, pageAbsoluteStart: Int): CharSequence {
        val chapters = cachedChapters ?: return text
        if (chapters.isEmpty()) return text
        val spannable = SpannableString(text)
        for (ch in chapters) {
            val abs = ch.startCharOffset
            if (abs >= pageAbsoluteStart && abs < pageAbsoluteStart + text.length) {
                val start = abs - pageAbsoluteStart
                val len = min(ch.title.length, text.length - start)
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
        val curBlockPage = getBlockPageForCurrentGlobalPosition()
        val progress = if (totalServerBlocks > 0) ((curBlockPage - 1) * 100 / totalServerBlocks) else 0
        _pageInfo.value = PageInfo(curBlockPage, totalServerBlocks, progress)
        val totalSubPages = if (globalLines.isEmpty() || linesPerPage <= 0) 0
        else (globalLines.size + linesPerPage - 1) / linesPerPage
        _currentPageState.value = PageState(curBlockPage, currentPageIndex + 1, totalServerBlocks, totalSubPages)
    }

    private fun getBlockPageForCurrentGlobalPosition(): Int {
        if (globalLines.isEmpty()) return windowBlocks.firstOrNull()?.blockPage ?: 1
        val firstLineOfPage = globalLines.getOrElse(currentPageIndex * linesPerPage) { globalLines.first() }
        return firstLineOfPage.cachedBlock.blockPage
    }

    // ── 翻页操作 ──
    fun previousPage() {
        if (!isInitialWindowLoaded || globalLines.isEmpty()) return
        if (currentPageIndex > 0) {
            currentPageIndex--
            showCurrentPage()
        } else {
            val firstBlock = windowBlocks.first()
            val prevBlockPage = firstBlock.blockPage - 1
            if (prevBlockPage >= 1) {
                viewModelScope.launch {
                    slideWindowBackward(prevBlockPage)
                }
            }
        }
    }

    fun nextPage() {
        if (!isInitialWindowLoaded || globalLines.isEmpty()) return
        val totalPages = (globalLines.size + linesPerPage - 1) / linesPerPage
        if (currentPageIndex < totalPages - 1) {
            currentPageIndex++
            showCurrentPage()
        } else {
            val lastBlock = windowBlocks.last()
            val nextBlockPage = lastBlock.blockPage + 1
            if (nextBlockPage <= totalServerBlocks) {
                viewModelScope.launch {
                    slideWindowForward(nextBlockPage)
                }
            }
        }
    }

    // ── 章节管理 ──
    fun loadChapters() {
        if (isLoadingChapters) return
        isLoadingChapters = true
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { fetchChaptersFromServer() }
                cachedChapters = list.sortedBy { it.startCharOffset }
                _chapters.value = list
                _showChapterDialogEvent.value = list
                if (isInitialWindowLoaded) {
                    showCurrentPage()
                }
            } catch (e: Exception) {
                Log.e("ViewModel", "章节加载失败", e)
            } finally {
                isLoadingChapters = false
            }
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) {
        navigateToGlobalOffset(chapter.startCharOffset, centerBlockHint = chapter.serverPage)
    }

    fun peekNextPageContent(callback: (CharSequence?) -> Unit) {
        if (!isInitialWindowLoaded || globalLines.isEmpty()) {
            callback(null)
            return
        }
        val totalPages = (globalLines.size + linesPerPage - 1) / linesPerPage
        val nextPageIndex = currentPageIndex + 1
        if (nextPageIndex < totalPages) {
            val content = buildPageContent(nextPageIndex)
            callback(content)
        } else {
            callback(null)
        }
    }

    // ── 网络与解析 ──
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

    // ── 辅助方法 ──
    private fun invalidateAllLayouts() {
        val blocks = blockCache.values.toList()
        blockCache.clear()
        for (b in blocks) {
            val newLayout = buildStaticLayout(b.fullText)
            blockCache[b.blockPage] = b.copy(layout = newLayout)
        }
        if (isInitialWindowLoaded) {
            windowBlocks = blockCache.values
                .filter { it.blockPage in windowBlocks.map { blk -> blk.blockPage } }
                .sortedBy { it.blockPage }
            if (windowBlocks.isNotEmpty()) rebuildGlobalLines()
        }
    }

    private fun rebuildWindowAndShow() {
        val savedOffset = _currentAbsoluteCharOffset.value ?: 0
        rebuildGlobalLines()
        currentPageIndex = findPageForAbsoluteOffset(savedOffset)
        showCurrentPage()
        // 字体变化后窗口可能不够，需要检查预加载
        checkAndPreload()
    }
}