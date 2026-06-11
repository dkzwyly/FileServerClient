package com.dkc.fileserverclient

import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.min

class TextPaginationManager(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "TextPaginationManager"
        private const val WINDOW_PADDING = 2
    }

    interface Callback {
        fun onPageContentChanged(content: CharSequence, state: PageState)
        fun onError(message: String)
        fun onLoading(loading: Boolean, message: String?)
        fun onChaptersReady(chapters: List<ChapterInfo>)
    }

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

    var isInitialized = false
        private set

    private var callback: Callback? = null
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun setCallback(callback: Callback?) { this.callback = callback }

    fun init(fileName: String, fileUrl: String, filePath: String,
             width: Int, paint: Paint, extra: Float, multiplier: Float, linesPerPage: Int) {
        Log.d(TAG, "init: $fileName")
        this.fileName = fileName
        this.fileUrl = fileUrl
        this.filePath = filePath
        textWidth = width
        textPaint = TextPaint(paint)
        lineSpacingExtra = extra
        lineSpacingMultiplier = multiplier
        this.linesPerPage = linesPerPage.coerceAtLeast(2)
        isInitialized = true
        loadWindow(centerBlockPage = 1)
    }

    fun updateDisplayParams(width: Int, paint: Paint, extra: Float, multiplier: Float, linesPerPage: Int) {
        textWidth = width
        textPaint = TextPaint(paint)
        lineSpacingExtra = extra
        lineSpacingMultiplier = multiplier
        this.linesPerPage = linesPerPage.coerceAtLeast(2)
        if (isInitialized && windowBlocks.isNotEmpty()) {
            invalidateAllLayouts()
            rebuildWindowAndShow()
        }
    }

    fun restorePosition(blockPage: Int, absoluteCharOffset: Int) {
        if (!isInitialized) {
            scope.launch {
                while (!isInitialized) delay(50)
                withContext(Dispatchers.Main) { restorePosition(blockPage, absoluteCharOffset) }
            }
            return
        }
        loadWindow(centerBlockPage = blockPage, targetOffset = absoluteCharOffset)
    }

    fun nextPage(): Boolean {
        if (!isInitialized || pageBreaks.isEmpty()) return false
        var target = currentPageIndex + 1
        while (target < pageBreaks.size && isPageBlank(target)) target++
        return if (target < pageBreaks.size) {
            currentPageIndex = target
            showCurrentPage()
            true
        } else {
            val last = windowBlocks.lastOrNull() ?: return false
            if (last.blockPage < totalServerBlocks) scope.launch { slideWindowForward(last.blockPage + 1) }
            false
        }
    }

    fun previousPage(): Boolean {
        if (!isInitialized || pageBreaks.isEmpty()) return false
        var target = currentPageIndex - 1
        while (target >= 0 && isPageBlank(target)) target--
        return if (target >= 0) {
            currentPageIndex = target
            showCurrentPage()
            true
        } else {
            val first = windowBlocks.firstOrNull() ?: return false
            if (first.blockPage > 1) scope.launch { slideWindowBackward(first.blockPage - 1) }
            false
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) = restorePosition(chapter.serverPage, chapter.startCharOffset)

    fun loadChapters() {
        if (!isInitialized) return
        scope.launch {
            try {
                val chapters = fetchChaptersFromServer()
                cachedChapters = chapters.sortedBy { it.startCharOffset }
                chapterStartOffsetSet = chapters.map { it.startCharOffset }.toSet()
                mainHandler.post {
                    callback?.onChaptersReady(chapters)
                    if (windowBlocks.isNotEmpty()) { recalculatePageBreaks(); showCurrentPage() }
                }
            } catch (e: Exception) { Log.e(TAG, "章节加载失败", e) }
        }
    }

    fun getCurrentPageContent(): String {
        if (!isInitialized || globalLines.isEmpty() || pageBreaks.isEmpty()) return ""
        val start = pageBreaks[currentPageIndex]
        val end = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size
        return buildPageContent(start, end).toString()
    }

    fun getCurrentPageState(): PageState? {
        if (!isInitialized || globalLines.isEmpty() || pageBreaks.isEmpty()) return null
        val firstLine = globalLines[pageBreaks[currentPageIndex]]
        return PageState(firstLine.cachedBlock.blockPage, currentPageIndex + 1, totalServerBlocks, pageBreaks.size, firstLine.absoluteStartChar)
    }

    fun release() { scope.cancel() }

    fun resetForNewFile() {
        Log.d(TAG, "resetForNewFile")
        scope.coroutineContext.cancelChildren()
        isInitialized = false
        blockCache.clear()
        windowBlocks = emptyList()
        globalLines = emptyList()
        pageBreaks = emptyList()
        currentPageIndex = 0
        totalServerBlocks = 1
        cachedChapters = null
        chapterStartOffsetSet = emptySet()
        fileName = ""
        fileUrl = ""
        filePath = ""
    }

    // ── 内部实现 ──
    private fun loadWindow(centerBlockPage: Int, targetOffset: Int? = null) {
        callback?.onLoading(true, "加载中...")
        scope.launch {
            try {
                val pages = ((centerBlockPage - WINDOW_PADDING)..(centerBlockPage + WINDOW_PADDING)).filter { it >= 1 }.toSet()
                val blocks = pages.map { blockCache[it] ?: fetchAndCacheBlock(it) }
                windowBlocks = blocks.sortedBy { it.blockPage }
                rebuildGlobalLines()
                recalculatePageBreaks()
                currentPageIndex = targetOffset?.let { findPageForAbsoluteOffset(it) } ?: 0
                withContext(Dispatchers.Main) { showCurrentPage(); checkAndPreload(); callback?.onLoading(false, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback?.onError("加载失败: ${e.message}"); callback?.onLoading(false, null) }
            }
        }
    }

    private suspend fun fetchAndCacheBlock(page: Int): CachedBlock {
        val url = buildBlockUrl(page)
        Log.d(TAG, "网络请求: $url")
        val json = fetchJson(url)
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
        if (globalLines.isEmpty()) { pageBreaks = emptyList(); return }
        val breaks = mutableListOf<Int>()
        var start = 0
        while (start < globalLines.size) {
            var end = min(start + linesPerPage, globalLines.size)
            for (i in (start + 1) until end) {
                if (chapterStartOffsetSet.contains(globalLines[i].absoluteStartChar)) { end = i; break }
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
            val lineText = line.cachedBlock.fullText.substring(layout.getLineStart(line.lineIndexInBlock), layout.getLineEnd(line.lineIndexInBlock))
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

    private fun showCurrentPage() {
        if (globalLines.isEmpty() || pageBreaks.isEmpty()) return
        while (currentPageIndex in pageBreaks.indices && isPageBlank(currentPageIndex)) {
            val next = (currentPageIndex + 1 until pageBreaks.size).firstOrNull { !isPageBlank(it) }
            if (next != null) currentPageIndex = next else break
        }
        val startLine = pageBreaks[currentPageIndex]
        val endLine = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size
        val content = buildPageContent(startLine, endLine)
        val state = getCurrentPageState() ?: return
        mainHandler.post { callback?.onPageContentChanged(content, state) }
    }

    private fun buildPageContent(startLine: Int, endLine: Int): CharSequence {
        val pageStartAbs = globalLines[startLine].absoluteStartChar
        val pageEndAbs = if (endLine < globalLines.size) globalLines[endLine].absoluteStartChar
        else globalLines.last().cachedBlock.startCharAbs + globalLines.last().cachedBlock.fullText.length
        val sb = StringBuilder()
        for (block in windowBlocks) {
            val blockStart = block.startCharAbs; val blockEnd = blockStart + block.fullText.length
            if (blockEnd <= pageStartAbs || blockStart >= pageEndAbs) continue
            val s = (pageStartAbs - blockStart).coerceAtLeast(0)
            val e = (pageEndAbs - blockStart).coerceAtMost(block.fullText.length)
            sb.append(block.fullText.substring(s, e))
        }
        return applyChapterStyles(sb.toString(), pageStartAbs)
    }

    private fun applyChapterStyles(text: String, pageStartAbs: Int): CharSequence {
        val chapters = cachedChapters ?: return text
        val spannable = SpannableString(text)
        for (ch in chapters) {
            val abs = ch.startCharOffset
            if (abs in pageStartAbs until pageStartAbs + text.length) {
                val start = abs - pageStartAbs
                val end = min(start + ch.title.length, text.length)
                spannable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(1.5f), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    private fun checkAndPreload() {
        if (globalLines.isEmpty()) return
        val first = windowBlocks.firstOrNull() ?: return
        val last = windowBlocks.lastOrNull() ?: return
        val curStart = pageBreaks.getOrElse(currentPageIndex) { 0 }
        val curEnd = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size

        if (curStart < linesPerPage && first.blockPage > 1) {
            val prev = first.blockPage - 1
            if (!blockCache.containsKey(prev)) scope.launch {
                fetchAndCacheBlock(prev)
                if (windowBlocks.firstOrNull()?.blockPage == first.blockPage) withContext(Dispatchers.Main) {
                    windowBlocks = listOf(blockCache[prev]!!) + windowBlocks
                    rebuildGlobalLines(); recalculatePageBreaks()
                    val off = getCurrentPageState()?.absoluteCharOffset ?: 0
                    currentPageIndex = findPageForAbsoluteOffset(off); showCurrentPage()
                }
            }
        }

        val dist = globalLines.size - curEnd
        if (dist < linesPerPage && last.blockPage < totalServerBlocks) {
            val next = last.blockPage + 1
            if (!blockCache.containsKey(next)) scope.launch {
                fetchAndCacheBlock(next)
                if (windowBlocks.lastOrNull()?.blockPage == last.blockPage) withContext(Dispatchers.Main) {
                    windowBlocks = windowBlocks + blockCache[next]!!
                    rebuildGlobalLines(); recalculatePageBreaks(); showCurrentPage()
                }
            }
        }
    }

    private suspend fun slideWindowForward(nextPage: Int) {
        val newBlock = blockCache[nextPage] ?: fetchAndCacheBlock(nextPage)
        windowBlocks = windowBlocks.drop(1) + newBlock
        rebuildGlobalLines(); recalculatePageBreaks()
        currentPageIndex = min(findPageForAbsoluteOffset(getCurrentPageState()?.absoluteCharOffset ?: 0) + 1, pageBreaks.size - 1)
        showCurrentPage(); checkAndPreload()
    }

    private suspend fun slideWindowBackward(prevPage: Int) {
        val newBlock = blockCache[prevPage] ?: fetchAndCacheBlock(prevPage)
        windowBlocks = listOf(newBlock) + windowBlocks.dropLast(1)
        rebuildGlobalLines(); recalculatePageBreaks()
        currentPageIndex = maxOf(findPageForAbsoluteOffset(getCurrentPageState()?.absoluteCharOffset ?: 0) - 1, 0)
        showCurrentPage(); checkAndPreload()
    }

    private fun invalidateAllLayouts() {
        if (!::textPaint.isInitialized) return
        val blocks = blockCache.values.toList()
        blockCache.clear()
        for (b in blocks) blockCache[b.blockPage] = b.copy(layout = buildStaticLayout(b.fullText))
        windowBlocks = windowBlocks.mapNotNull { blockCache[it.blockPage] }
        if (windowBlocks.isNotEmpty()) { rebuildGlobalLines(); recalculatePageBreaks() }
    }

    private fun rebuildWindowAndShow() {
        val savedOffset = getCurrentPageState()?.absoluteCharOffset ?: 0
        rebuildGlobalLines(); recalculatePageBreaks()
        currentPageIndex = findPageForAbsoluteOffset(savedOffset)
        showCurrentPage(); checkAndPreload()
    }

    private fun trimCache() {
        if (windowBlocks.isEmpty()) return
        val min = windowBlocks.minOf { it.blockPage } - WINDOW_PADDING
        val max = windowBlocks.maxOf { it.blockPage } + WINDOW_PADDING
        blockCache.keys.filter { it < min || it > max }.forEach { blockCache.remove(it) }
    }

    private fun buildBlockUrl(page: Int) =
        "${fileUrl}${if (fileUrl.contains("?")) "&" else "?"}page=$page"

    private suspend fun fetchJson(url: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchJson: $url")
        val resp = httpClient.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        resp.body?.string() ?: throw Exception("空响应")
    }

    private fun parseBlockResponse(json: String): BlockData {
        val obj = JSONObject(json)
        val pag = obj.getJSONObject("pagination")
        return BlockData(obj.getString("content"), pag.getInt("currentPage"), pag.getInt("totalPages"),
            pag.optInt("startChar", 0), pag.optInt("endChar", 0))
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
}