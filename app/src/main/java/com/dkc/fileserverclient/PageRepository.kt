package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.*
import java.net.URLEncoder
import kotlin.math.min

class PageRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "PageRepository"
        private const val WINDOW_PADDING = 2

        @Volatile
        private var INSTANCE: PageRepository? = null

        fun getInstance(context: Context): PageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PageRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    // ── 公开状态流 ──
    data class PageUIData(
        val content: CharSequence,
        val state: PageState
    )

    data class PageState(
        val blockPage: Int,
        val subPage: Int,
        val totalBlockPages: Int,
        val totalSubPages: Int,
        val absoluteCharOffset: Int
    )

    data class ChapterInfo(
        val title: String,
        val serverPage: Int,
        val lineNumber: Int,
        val startCharOffset: Int
    )

    data class LoadingState(val isLoading: Boolean, val message: String? = null)

    private val _pageContent = MutableStateFlow<PageUIData?>(null)
    val pageContentFlow: StateFlow<PageUIData?> = _pageContent.asStateFlow()

    private val _loadingState = MutableStateFlow(LoadingState(false))
    val loadingStateFlow: StateFlow<LoadingState> = _loadingState.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    val chaptersFlow: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents = _errorEvents.asSharedFlow()

    // ── 内部状态 ──
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var httpClient: OkHttpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    private var fileName = ""
    private var fileUrl = ""
    private var filePath = ""

    private var textWidth = 0
    private lateinit var textPaint: TextPaint
    private var lineSpacingExtra = 0f
    private var lineSpacingMultiplier = 1f
    private var linesPerPage = 20

    private val blockCache = mutableMapOf<Int, CachedBlock>()
    private var windowBlocks = listOf<CachedBlock>()
    private var globalLines = listOf<LineInfo>()
    private var pageBreaks = listOf<Int>()
    private var currentPageIndex = 0
    private var totalServerBlocks = 1

    private var cachedChapters: List<ChapterInfo>? = null
    private var chapterStartOffsetSet = emptySet<Int>()

    private var readingHistoryFile: File? = null
    private var historySaveJob: Job? = null

    // ── 私有数据结构 ──
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

    private data class BlockData(
        val fullText: String,
        val blockPage: Int,
        val totalBlockPages: Int,
        val startChar: Int,
        val endChar: Int
    )

    // ── 公开 API ──
    fun setupFile(name: String, url: String, path: String) {
        if (fileName != name) {
            resetForNewFile(name, url, path)
        }
    }

    fun updateDisplayParams(width: Int, paint: Paint, extra: Float, multiplier: Float, lines: Int) {
        val needRelayout = (textWidth != width || textPaint.textSize != paint.textSize ||
                lineSpacingExtra != extra || lineSpacingMultiplier != multiplier)
        textWidth = width
        textPaint = TextPaint(paint)
        lineSpacingExtra = extra
        lineSpacingMultiplier = multiplier
        linesPerPage = lines.coerceAtLeast(2)

        if (needRelayout && windowBlocks.isNotEmpty()) {
            scope.launch {
                invalidateAllLayouts()
                rebuildGlobalLines()
                recalculatePageBreaks()
                updateCurrentPage()
            }
        } else if (windowBlocks.isEmpty() && fileName.isNotEmpty()) {
            initialize()
        }
    }

    suspend fun nextPage() {
        if (!isInitialized()) return
        var target = currentPageIndex + 1
        while (target < pageBreaks.size && isPageBlank(target)) target++
        if (target < pageBreaks.size) {
            currentPageIndex = target
            updateCurrentPage()
        } else {
            val last = windowBlocks.lastOrNull() ?: return
            if (last.blockPage < totalServerBlocks) {
                slideWindowForward(last.blockPage + 1)
            }
        }
    }

    suspend fun previousPage() {
        if (!isInitialized()) return
        var target = currentPageIndex - 1
        while (target >= 0 && isPageBlank(target)) target--
        if (target >= 0) {
            currentPageIndex = target
            updateCurrentPage()
        } else {
            val first = windowBlocks.firstOrNull() ?: return
            if (first.blockPage > 1) {
                slideWindowBackward(first.blockPage - 1)
            }
        }
    }

    suspend fun restorePosition(blockPage: Int, absoluteOffset: Int) {
        if (!isInitialized()) {
            delay(100)
            if (!isInitialized()) return
        }
        loadWindow(centerBlockPage = blockPage, targetOffset = absoluteOffset)
    }

    suspend fun jumpToChapter(chapter: ChapterInfo) {
        restorePosition(chapter.serverPage, chapter.startCharOffset)
    }

    fun loadChapters() {
        scope.launch {
            try {
                val list = fetchChaptersFromServer()
                cachedChapters = list.sortedBy { it.startCharOffset }
                chapterStartOffsetSet = list.map { it.startCharOffset }.toSet()
                _chapters.value = list
                if (isInitialized()) {
                    recalculatePageBreaks()
                    updateCurrentPage()
                }
            } catch (e: Exception) {
                _errorEvents.emit("章节加载失败: ${e.message}")
            }
        }
    }

    fun getCurrentPageContent(): String {
        if (!isInitialized()) return ""
        val state = _pageContent.value?.state ?: return ""
        return buildPageContentForState(state).toString()
    }

    fun getCurrentPageState(): PageState? = _pageContent.value?.state

    fun release() {
        scope.cancel()
    }

    // ── 内部初始化 ──
    private fun isInitialized() = windowBlocks.isNotEmpty() && globalLines.isNotEmpty()

    private fun resetForNewFile(name: String, url: String, path: String) {
        fileName = name
        fileUrl = url
        filePath = path
        blockCache.clear()
        windowBlocks = emptyList()
        globalLines = emptyList()
        pageBreaks = emptyList()
        currentPageIndex = 0
        totalServerBlocks = 1
        cachedChapters = null
        chapterStartOffsetSet = emptySet()

        val historyDir = File(appContext.filesDir, "reading_history")
        if (!historyDir.exists()) historyDir.mkdirs()
        val safeName = name.replace("[^a-zA-Z0-9]".toRegex(), "_")
        readingHistoryFile = File(historyDir, "history_${safeName}.dat")

        initialize()
    }

    private fun initialize() {
        scope.launch {
            val history = readHistory()
            val centerBlock = history?.blockPage ?: 1
            val offset = history?.absoluteCharOffset
            loadWindow(centerBlockPage = centerBlock, targetOffset = offset)
        }
    }

    private suspend fun loadWindow(centerBlockPage: Int, targetOffset: Int? = null) {
        _loadingState.value = LoadingState(true, "加载中...")
        try {
            val pages = ((centerBlockPage - WINDOW_PADDING)..(centerBlockPage + WINDOW_PADDING))
                .filter { it >= 1 }.toSet()
            val blocks = pages.map { page ->
                withContext(Dispatchers.IO) {
                    async { blockCache[page] ?: fetchAndCacheBlock(page) }
                }
            }.awaitAll()
            windowBlocks = blocks.sortedBy { it.blockPage }
            rebuildGlobalLines()
            recalculatePageBreaks()
            currentPageIndex = targetOffset?.let { findPageForAbsoluteOffset(it) } ?: 0
            updateCurrentPage()
            checkAndPreload()
            saveHistory()
        } catch (e: Exception) {
            _errorEvents.emit("加载失败: ${e.message}")
        } finally {
            _loadingState.value = LoadingState(false)
        }
    }

    private suspend fun fetchAndCacheBlock(page: Int): CachedBlock {
        val url = buildBlockUrl(page)
        Log.d(TAG, "请求: $url")
        val json = withContext(Dispatchers.IO) { fetchJson(url) }
        val data = parseBlockResponse(json)
        totalServerBlocks = data.totalBlockPages
        val layout = withContext(Dispatchers.Default) { buildStaticLayout(data.fullText) }
        val block = CachedBlock(data.blockPage, data.fullText, data.startChar, layout)
        blockCache[page] = block
        trimCache()
        return block
    }

    private fun buildStaticLayout(text: String): StaticLayout {
        if (!::textPaint.isInitialized) textPaint = TextPaint().apply { textSize = 40f }
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()
    }

    private fun rebuildGlobalLines() {
        globalLines = windowBlocks.flatMap { block ->
            (0 until block.layout.lineCount).map { i ->
                LineInfo(block, i, block.startCharAbs + block.layout.getLineStart(i))
            }
        }
    }

    private fun recalculatePageBreaks() {
        if (globalLines.isEmpty()) {
            pageBreaks = emptyList()
            return
        }
        val breaks = mutableListOf<Int>()
        var start = 0
        while (start < globalLines.size) {
            var end = min(start + linesPerPage, globalLines.size)
            for (i in (start + 1) until end) {
                if (chapterStartOffsetSet.contains(globalLines[i].absoluteStartChar)) {
                    end = i
                    break
                }
            }
            breaks.add(start)
            start = end
        }
        pageBreaks = breaks
    }

    private fun isPageBlank(idx: Int): Boolean {
        if (idx < 0 || idx >= pageBreaks.size) return true
        val start = pageBreaks[idx]
        val end = if (idx + 1 < pageBreaks.size) pageBreaks[idx + 1] else globalLines.size
        for (i in start until end) {
            val line = globalLines[i]
            val layout = line.cachedBlock.layout
            val lineText = line.cachedBlock.fullText.substring(
                layout.getLineStart(line.lineIndexInBlock),
                layout.getLineEnd(line.lineIndexInBlock)
            )
            if (lineText.isNotBlank()) return false
        }
        return true
    }

    private fun findPageForAbsoluteOffset(offset: Int): Int {
        if (globalLines.isEmpty() || pageBreaks.isEmpty()) return 0
        val lineIdx = findLineForAbsoluteOffset(offset)
        var low = 0
        var high = pageBreaks.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            when {
                lineIdx < pageBreaks[mid] -> high = mid - 1
                mid + 1 < pageBreaks.size && lineIdx >= pageBreaks[mid + 1] -> low = mid + 1
                else -> return mid
            }
        }
        return 0
    }

    private fun findLineForAbsoluteOffset(offset: Int): Int {
        var low = 0
        var high = globalLines.size - 1
        var target = 0
        while (low <= high) {
            val mid = (low + high) / 2
            val start = globalLines[mid].absoluteStartChar
            val end = if (mid + 1 < globalLines.size) globalLines[mid + 1].absoluteStartChar
            else globalLines[mid].cachedBlock.startCharAbs + globalLines[mid].cachedBlock.fullText.length
            when {
                offset >= end -> low = mid + 1
                offset < start -> high = mid - 1
                else -> {
                    target = mid
                    break
                }
            }
        }
        if (low > high) target = low.coerceIn(0, globalLines.size - 1)
        return target
    }

    private suspend fun slideWindowForward(nextPage: Int) {
        val newBlock = blockCache[nextPage] ?: fetchAndCacheBlock(nextPage)
        windowBlocks = windowBlocks.drop(1) + newBlock
        rebuildGlobalLines()
        recalculatePageBreaks()
        currentPageIndex = min(
            findPageForAbsoluteOffset(_pageContent.value?.state?.absoluteCharOffset ?: 0) + 1,
            pageBreaks.size - 1
        )
        updateCurrentPage()
        checkAndPreload()
    }

    private suspend fun slideWindowBackward(prevPage: Int) {
        val newBlock = blockCache[prevPage] ?: fetchAndCacheBlock(prevPage)
        windowBlocks = listOf(newBlock) + windowBlocks.dropLast(1)
        rebuildGlobalLines()
        recalculatePageBreaks()
        currentPageIndex = maxOf(
            findPageForAbsoluteOffset(_pageContent.value?.state?.absoluteCharOffset ?: 0) - 1,
            0
        )
        updateCurrentPage()
        checkAndPreload()
    }

    private fun updateCurrentPage() {
        if (!isInitialized()) return
        while (currentPageIndex in pageBreaks.indices && isPageBlank(currentPageIndex)) {
            val next = (currentPageIndex + 1 until pageBreaks.size).firstOrNull { !isPageBlank(it) }
            if (next != null) currentPageIndex = next else break
        }
        val state = buildCurrentPageState() ?: return
        val content = buildPageContentForState(state)
        _pageContent.value = PageUIData(content, state)
        saveHistory()
    }

    private fun buildCurrentPageState(): PageState? {
        if (globalLines.isEmpty() || pageBreaks.isEmpty()) return null
        val startLine = pageBreaks[currentPageIndex]
        val firstLine = globalLines[startLine]
        return PageState(
            blockPage = firstLine.cachedBlock.blockPage,
            subPage = currentPageIndex + 1,
            totalBlockPages = totalServerBlocks,
            totalSubPages = pageBreaks.size,
            absoluteCharOffset = firstLine.absoluteStartChar
        )
    }

    private fun buildPageContentForState(state: PageState): CharSequence {
        if (globalLines.isEmpty() || pageBreaks.isEmpty()) return ""
        val startLine = pageBreaks[currentPageIndex]
        val endLine = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size
        val pageStartAbs = globalLines[startLine].absoluteStartChar
        val pageEndAbs = if (endLine < globalLines.size) globalLines[endLine].absoluteStartChar
        else globalLines.last().cachedBlock.startCharAbs + globalLines.last().cachedBlock.fullText.length
        val sb = StringBuilder()
        for (block in windowBlocks) {
            val blockStart = block.startCharAbs
            val blockEnd = blockStart + block.fullText.length
            if (blockEnd <= pageStartAbs || blockStart >= pageEndAbs) continue
            val s = (pageStartAbs - blockStart).coerceAtLeast(0)
            val e = (pageEndAbs - blockStart).coerceAtMost(block.fullText.length)
            sb.append(block.fullText.substring(s, e))
        }
        return applyChapterStyles(sb.toString(), pageStartAbs)
    }

    private fun applyChapterStyles(text: String, pageAbsoluteStart: Int): CharSequence {
        val chapters = cachedChapters ?: return text
        val spannable = SpannableString(text)
        for (ch in chapters) {
            val abs = ch.startCharOffset
            if (abs >= pageAbsoluteStart && abs < pageAbsoluteStart + text.length) {
                val start = abs - pageAbsoluteStart
                val end = min(start + ch.title.length, text.length)
                spannable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(1.5f), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    private suspend fun checkAndPreload() {
        if (!isInitialized()) return
        val first = windowBlocks.firstOrNull() ?: return
        val last = windowBlocks.lastOrNull() ?: return
        val curStart = pageBreaks.getOrElse(currentPageIndex) { 0 }
        val curEnd = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size

        if (curStart < linesPerPage && first.blockPage > 1) {
            val prev = first.blockPage - 1
            if (!blockCache.containsKey(prev)) {
                fetchAndCacheBlock(prev)
                if (windowBlocks.firstOrNull()?.blockPage == first.blockPage) {
                    withContext(Dispatchers.Main) {
                        windowBlocks = listOf(blockCache[prev]!!) + windowBlocks
                        rebuildGlobalLines()
                        recalculatePageBreaks()
                        val off = _pageContent.value?.state?.absoluteCharOffset ?: 0
                        currentPageIndex = findPageForAbsoluteOffset(off)
                        updateCurrentPage()
                    }
                }
            }
        }

        val dist = globalLines.size - curEnd
        if (dist < linesPerPage && last.blockPage < totalServerBlocks) {
            val next = last.blockPage + 1
            if (!blockCache.containsKey(next)) {
                fetchAndCacheBlock(next)
                if (windowBlocks.lastOrNull()?.blockPage == last.blockPage) {
                    withContext(Dispatchers.Main) {
                        windowBlocks = windowBlocks + blockCache[next]!!
                        rebuildGlobalLines()
                        recalculatePageBreaks()
                        updateCurrentPage()
                    }
                }
            }
        }
    }

    private fun invalidateAllLayouts() {
        if (!::textPaint.isInitialized) return
        val blocks = blockCache.values.toList()
        blockCache.clear()
        for (b in blocks) {
            blockCache[b.blockPage] = b.copy(layout = buildStaticLayout(b.fullText))
        }
        windowBlocks = windowBlocks.mapNotNull { blockCache[it.blockPage] }
        if (windowBlocks.isNotEmpty()) {
            rebuildGlobalLines()
            recalculatePageBreaks()
        }
    }

    private fun trimCache() {
        if (windowBlocks.isEmpty()) return
        val min = windowBlocks.minOf { it.blockPage } - WINDOW_PADDING
        val max = windowBlocks.maxOf { it.blockPage } + WINDOW_PADDING
        blockCache.keys.filter { it < min || it > max }.forEach { blockCache.remove(it) }
    }

    private fun buildBlockUrl(page: Int): String =
        "${fileUrl}${if (fileUrl.contains("?")) "&" else "?"}page=$page"

    private suspend fun fetchJson(url: String) = withContext(Dispatchers.IO) {
        val resp = httpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        resp.body?.string() ?: throw Exception("空响应")
    }

    private fun parseBlockResponse(json: String): BlockData {
        val obj = JSONObject(json)
        val pag = obj.getJSONObject("pagination")
        return BlockData(
            obj.getString("content"),
            pag.getInt("currentPage"),
            pag.getInt("totalPages"),
            pag.optInt("startChar", 0),
            pag.optInt("endChar", 0)
        )
    }

    private suspend fun fetchChaptersFromServer(): List<ChapterInfo> = withContext(Dispatchers.IO) {
        val identifier = if (fileUrl.contains("/preview/")) fileUrl.substringAfter("/preview/").substringBefore("?") else filePath
        val base = if (fileUrl.contains("/preview/")) fileUrl.substringBefore("/preview/") else fileUrl.substringBeforeLast("/")
        val json = fetchJson("$base/chapters/${URLEncoder.encode(identifier, "UTF-8")}")
        parseChaptersJson(json)
    }

    private fun parseChaptersJson(json: String): List<ChapterInfo> {
        val list = mutableListOf<ChapterInfo>()
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("chapters") ?: obj.optJSONObject("data")?.optJSONArray("chapters") ?: return list
        for (i in 0 until arr.length()) {
            val ch = arr.getJSONObject(i)
            list.add(ChapterInfo(ch.optString("title"), ch.optInt("page", 1), ch.optInt("lineNumber", 0), ch.optInt("startCharOffset", 0)))
        }
        return list
    }

    // ── 历史持久化（异步，防抖） ──
    private fun saveHistory() {
        historySaveJob?.cancel()
        historySaveJob = scope.launch(Dispatchers.IO) {
            delay(300) // 防抖
            try {
                readingHistoryFile?.let { file ->
                    val state = _pageContent.value?.state ?: return@launch
                    val history = ReadingHistory(fileName, fileUrl, state.blockPage, state.absoluteCharOffset, System.currentTimeMillis())
                    ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(history) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存进度失败", e)
            }
        }
    }

    private suspend fun readHistory(): ReadingHistory? = withContext(Dispatchers.IO) {
        try {
            readingHistoryFile?.takeIf { it.exists() }?.let {
                ObjectInputStream(FileInputStream(it)).use { it.readObject() as? ReadingHistory }
            }
        } catch (e: Exception) {
            null
        }
    }
}

data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val blockPage: Int,
    val absoluteCharOffset: Int,
    val timestamp: Long
) : Serializable