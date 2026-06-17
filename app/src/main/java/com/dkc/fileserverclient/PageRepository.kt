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
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.*
import java.net.URLEncoder
import kotlin.math.min

class PageRepository private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "PageRepository"
        private const val WINDOW_SIZE = 5
        private const val WINDOW_HALF = 2

        @Volatile
        private var INSTANCE: PageRepository? = null

        fun getInstance(context: Context): PageRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PageRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    // ── 公开状态流 ──
    data class PageUIData(val content: CharSequence, val state: PageState)
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

    private val _chaptersLoadedEvent = MutableSharedFlow<Unit>()
    val chaptersLoadedEvent: SharedFlow<Unit> = _chaptersLoadedEvent.asSharedFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

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
    private var isLoadingChapters = false

    // 用于取消正在进行的加载任务（键为 blockPage）
    private val loadingJobs = mutableMapOf<Int, Job>()

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
        if (fileName != name) resetForNewFile(name, url, path)
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
                adjustWindow()
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
        }
    }

    suspend fun previousPage() {
        if (!isInitialized()) return
        var target = currentPageIndex - 1
        while (target >= 0 && isPageBlank(target)) target--
        if (target >= 0) {
            currentPageIndex = target
            updateCurrentPage()
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
        if (isLoadingChapters) return
        isLoadingChapters = true
        scope.launch {
            try {
                val list = fetchChaptersFromServer()
                cachedChapters = list.sortedBy { it.startCharOffset }
                chapterStartOffsetSet = list.map { it.startCharOffset }.toSet()
                _chapters.value = list
                _chaptersLoadedEvent.emit(Unit)
                if (isInitialized()) {
                    recalculatePageBreaks()
                    updateCurrentPage()
                }
            } catch (e: Exception) {
                _errorEvents.emit("章节加载失败: ${e.message}")
            } finally {
                isLoadingChapters = false
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
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
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
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()

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
            val pagesToLoad = ((centerBlockPage - WINDOW_HALF).coerceAtLeast(1) ..
                    (centerBlockPage + WINDOW_HALF)).filter { it >= 1 }
            pagesToLoad.forEach { page ->
                if (!blockCache.containsKey(page)) fetchAndCacheBlock(page)
            }
            windowBlocks = pagesToLoad.mapNotNull { blockCache[it] }.sortedBy { it.blockPage }
            rebuildGlobalLines()
            recalculatePageBreaks()
            currentPageIndex = targetOffset?.let { findPageForAbsoluteOffset(it) } ?: 0
            updateCurrentPage()
            adjustWindow()
        } catch (e: Exception) {
            _errorEvents.emit("加载失败: ${e.message}")
        } finally {
            _loadingState.value = LoadingState(false)
        }
    }

    // 带重试的网络请求
    private suspend fun fetchJsonWithRetry(url: String, maxRetries: Int = 3): String {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return fetchJson(url)
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "fetchJson 失败 (第 ${attempt + 1} 次), url=$url", e)
                if (attempt < maxRetries - 1) {
                    delay(1000L * (attempt + 1)) // 1s, 2s 退避
                }
            }
        }
        throw lastException ?: Exception("网络请求失败")
    }

    private suspend fun fetchAndCacheBlock(page: Int): CachedBlock {
        val url = buildBlockUrl(page)
        val json = fetchJsonWithRetry(url)   // 使用带重试的方法
        val data = parseBlockResponse(json)
        totalServerBlocks = data.totalBlockPages
        val layout = withContext(Dispatchers.Default) { buildStaticLayout(data.fullText) }
        val block = CachedBlock(data.blockPage, data.fullText, data.startChar, layout)
        blockCache[page] = block
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
        if (globalLines.isEmpty()) { pageBreaks = emptyList(); return }
        val breaks = mutableListOf<Int>()
        var start = 0
        while (start < globalLines.size) {
            var end = min(start + linesPerPage, globalLines.size)
            for (i in (start + 1) until end) {
                if (chapterStartOffsetSet.contains(globalLines[i].absoluteStartChar)) {
                    end = i; break
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
        var low = 0; var high = pageBreaks.size - 1
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
        var low = 0; var high = globalLines.size - 1; var target = 0
        while (low <= high) {
            val mid = (low + high) / 2
            val start = globalLines[mid].absoluteStartChar
            val end = if (mid + 1 < globalLines.size) globalLines[mid + 1].absoluteStartChar
            else globalLines[mid].cachedBlock.startCharAbs + globalLines[mid].cachedBlock.fullText.length
            when {
                offset >= end -> low = mid + 1
                offset < start -> high = mid - 1
                else -> { target = mid; break }
            }
        }
        if (low > high) target = low.coerceIn(0, globalLines.size - 1)
        return target
    }

    // ── 窗口管理 ──
    private suspend fun adjustWindow() {
        val currentBlock = _pageContent.value?.state?.blockPage ?: return
        val minBlock = (currentBlock - WINDOW_HALF).coerceAtLeast(1)
        val maxBlock = minBlock + WINDOW_SIZE - 1
        val idealRange = (minBlock..maxBlock).toSet()

        val existingPages = windowBlocks.map { it.blockPage }.toSet()
        val toRemove = existingPages - idealRange
        val toAdd = idealRange - existingPages

        loadingJobs.keys.filter { it !in idealRange }.forEach { loadingJobs[it]?.cancel(); loadingJobs.remove(it) }

        if (toRemove.isNotEmpty()) {
            windowBlocks = windowBlocks.filter { it.blockPage !in toRemove }
            toRemove.forEach { blockCache.remove(it) }
        }

        if (toAdd.isNotEmpty()) {
            val jobs = toAdd.map { page ->
                page to scope.async {
                    try {
                        fetchAndCacheBlock(page)
                        page
                    } catch (e: Exception) {
                        Log.e(TAG, "预加载块 $page 失败: ${e.message}")
                        null
                    }
                }
            }
            jobs.forEach { (page, job) ->
                loadingJobs[page] = job
                job.invokeOnCompletion { loadingJobs.remove(page) }
            }
            val results = jobs.map { (_, job) -> job.await() }
            val newBlocks = results.filterNotNull().mapNotNull { blockCache[it] }
            windowBlocks = (windowBlocks + newBlocks).distinctBy { it.blockPage }.sortedBy { it.blockPage }
        }

        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            rebuildGlobalLines()
            recalculatePageBreaks()
            val currentOffset = _pageContent.value?.state?.absoluteCharOffset ?: return
            currentPageIndex = findPageForAbsoluteOffset(currentOffset)
            refreshPageDisplay()
        }
    }

    private fun updateCurrentPage() {
        while (currentPageIndex in pageBreaks.indices && isPageBlank(currentPageIndex)) {
            val next = (currentPageIndex + 1 until pageBreaks.size).firstOrNull { !isPageBlank(it) }
            if (next != null) currentPageIndex = next else break
        }
        val state = buildCurrentPageState() ?: return
        val content = buildPageContentForState(state)
        _pageContent.value = PageUIData(content, state)
        saveHistory()

        triggerMidBlockPreload(state)
        scope.launch { adjustWindow() }
    }

    private fun refreshPageDisplay() {
        while (currentPageIndex in pageBreaks.indices && isPageBlank(currentPageIndex)) {
            val next = (currentPageIndex + 1 until pageBreaks.size).firstOrNull { !isPageBlank(it) }
            if (next != null) currentPageIndex = next else break
        }
        val state = buildCurrentPageState() ?: return
        val content = buildPageContentForState(state)
        _pageContent.value = PageUIData(content, state)
        saveHistory()
    }

    private fun triggerMidBlockPreload(state: PageState) {
        val currentBlock = windowBlocks.firstOrNull { it.blockPage == state.blockPage } ?: return
        if (state.blockPage >= totalServerBlocks) return
        val nextPage = state.blockPage + 1
        if (blockCache.containsKey(nextPage) || loadingJobs.containsKey(nextPage)) return

        val progressInBlock = (state.absoluteCharOffset - currentBlock.startCharAbs).toFloat() /
                currentBlock.fullText.length.coerceAtLeast(1)
        if (progressInBlock >= 0.5f) {
            Log.d(TAG, "块内进度 ${(progressInBlock * 100).toInt()}%，预加载块 $nextPage")
            scope.launch {
                fetchAndCacheBlock(nextPage)
                adjustWindow()
            }
        }
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

    private fun invalidateAllLayouts() {
        if (!::textPaint.isInitialized) return
        val blocks = blockCache.values.toList()
        blockCache.clear()
        for (b in blocks) {
            blockCache[b.blockPage] = b.copy(layout = buildStaticLayout(b.fullText))
        }
        windowBlocks = windowBlocks.mapNotNull { blockCache[it.blockPage] }
    }

    // ── 网络与存储工具 ──
    private fun buildBlockUrl(page: Int) =
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

    private fun saveHistory() {
        historySaveJob?.cancel()
        historySaveJob = scope.launch(Dispatchers.IO) {
            delay(300)
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
        } catch (e: Exception) { null }
    }
}

data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val blockPage: Int,
    val absoluteCharOffset: Int,
    val timestamp: Long
) : Serializable