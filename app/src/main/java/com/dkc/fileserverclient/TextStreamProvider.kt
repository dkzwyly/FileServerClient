// TextStreamProvider.kt
package com.dkc.fileserverclient

import android.text.StaticLayout
import android.text.TextPaint
import android.text.Layout
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.min

class TextStreamProvider(
    private val fileName: String,
    private val fileUrl: String,
    private val filePath: String,
    private val httpClient: okhttp3.OkHttpClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 显示参数（由 Service 设置，可从 SharedPreferences 恢复）
    var textWidth: Int = 600           // 默认宽度，Service 初始化后用屏幕宽度覆盖
    var textPaint: TextPaint = TextPaint().apply { textSize = 40f } // 默认字体大小
    var lineSpacingExtra: Float = 0f
    var lineSpacingMultiplier: Float = 1f
    var linesPerPage: Int = 20

    // 章节数据
    var cachedChapters: List<TextPreviewViewModel.ChapterInfo> = emptyList()
        private set
    private var chapterStartOffsetSet = emptySet<Int>()

    // 块缓存和窗口
    private val blockCache = mutableMapOf<Int, BlockData>()
    private var windowBlocks = listOf<BlockData>()
    private var globalLines = listOf<LineInfo>()
    private var pageBreaks = listOf<Int>()
    private var currentPageIndex = 0
    private var totalServerBlocks = 1

    // 绝对偏移
    var currentAbsoluteCharOffset: Int = 0
        private set

    // 状态回调
    var onLoadingState: ((Boolean, String?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onChaptersReady: ((List<TextPreviewViewModel.ChapterInfo>) -> Unit)? = null
    var onPageContentReady: ((CharSequence) -> Unit)? = null

    // 内部数据类（与 ViewModel 相同）
    private data class BlockData(
        val fullText: String,
        val blockPage: Int,
        val totalBlockPages: Int,
        val startChar: Int,
        val endChar: Int
    )
    private data class LineInfo(
        val block: BlockData,
        val lineIndexInBlock: Int,
        val absoluteStartChar: Int
    )

    // 供外部使用的分页信息
    fun getTotalPages(): Int = pageBreaks.size
    fun getCurrentPage(): Int = currentPageIndex + 1
    fun getCurrentBlockPage(): Int {
        if (globalLines.isEmpty() || pageBreaks.isEmpty()) return 1
        val startLine = pageBreaks[currentPageIndex]
        val firstLine = globalLines.getOrElse(startLine) { globalLines.first() }
        return firstLine.block.blockPage
    }

    // 设置显示参数（字体、大小变化时调用）
    fun setDisplayParams(width: Int, paint: TextPaint, extra: Float, multiplier: Float) {
        this.textWidth = width
        this.textPaint = paint
        this.lineSpacingExtra = extra
        this.lineSpacingMultiplier = multiplier
        // 如果已加载过内容，需重建布局
        if (windowBlocks.isNotEmpty()) {
            invalidateLayouts()
        }
    }

    // 开始加载内容（从指定中心块开始）
    fun startLoading(centerBlockPage: Int = 1) {
        onLoadingState?.invoke(true, "加载中...")
        scope.launch {
            try {
                val pagesToLoad = ((centerBlockPage - 2)..(centerBlockPage + 2)).filter { it >= 1 }
                val newBlocks = pagesToLoad.map { fetchBlock(it) }
                windowBlocks = newBlocks.sortedBy { it.blockPage }
                rebuildGlobalLines()
                recalculatePageBreaks()
                currentPageIndex = 0
                showCurrentPage()
                onLoadingState?.invoke(false, null)
                checkAndPreload()
            } catch (e: Exception) {
                onError?.invoke("加载失败: ${e.message}")
                onLoadingState?.invoke(false, null)
            }
        }
    }

    // 恢复阅读位置
    fun restorePosition(blockPage: Int, absoluteCharOffset: Int) {
        scope.launch {
            try {
                val pagesToLoad = ((blockPage - 2)..(blockPage + 2)).filter { it >= 1 }
                windowBlocks = pagesToLoad.map { fetchBlock(it) }.sortedBy { it.blockPage }
                rebuildGlobalLines()
                recalculatePageBreaks()
                currentPageIndex = findPageForAbsoluteOffset(absoluteCharOffset)
                showCurrentPage()
                checkAndPreload()
            } catch (e: Exception) {
                onError?.invoke("恢复位置失败: ${e.message}")
            }
        }
    }

    fun nextPage(): Boolean {
        if (pageBreaks.isEmpty()) return false
        var next = currentPageIndex + 1
        while (next < pageBreaks.size && isPageBlank(next)) next++
        if (next < pageBreaks.size) {
            currentPageIndex = next
            showCurrentPage()
            return true
        } else {
            // 尝试加载后续块
            val lastBlock = windowBlocks.lastOrNull() ?: return false
            if (lastBlock.blockPage < totalServerBlocks) {
                scope.launch {
                    try {
                        val newBlock = fetchBlock(lastBlock.blockPage + 1)
                        windowBlocks = windowBlocks.drop(1) + newBlock
                        rebuildGlobalLines()
                        recalculatePageBreaks()
                        val target = findPageForAbsoluteOffset(currentAbsoluteCharOffset)
                        currentPageIndex = min(target + 1, pageBreaks.size - 1)
                        showCurrentPage()
                        checkAndPreload()
                    } catch (e: Exception) {
                        onError?.invoke("加载下一块失败: ${e.message}")
                    }
                }
            }
            return false
        }
    }

    fun previousPage(): Boolean {
        if (pageBreaks.isEmpty()) return false
        var prev = currentPageIndex - 1
        while (prev >= 0 && isPageBlank(prev)) prev--
        if (prev >= 0) {
            currentPageIndex = prev
            showCurrentPage()
            return true
        } else {
            val firstBlock = windowBlocks.firstOrNull() ?: return false
            if (firstBlock.blockPage > 1) {
                scope.launch {
                    try {
                        val newBlock = fetchBlock(firstBlock.blockPage - 1)
                        windowBlocks = listOf(newBlock) + windowBlocks.dropLast(1)
                        rebuildGlobalLines()
                        recalculatePageBreaks()
                        val target = findPageForAbsoluteOffset(currentAbsoluteCharOffset)
                        currentPageIndex = maxOf(target - 1, 0)
                        showCurrentPage()
                        checkAndPreload()
                    } catch (e: Exception) {
                        onError?.invoke("加载上一块失败: ${e.message}")
                    }
                }
            }
            return false
        }
    }

    fun peekNextPageContent(): CharSequence? {
        if (pageBreaks.isEmpty()) return null
        var nextIdx = currentPageIndex + 1
        while (nextIdx < pageBreaks.size && isPageBlank(nextIdx)) nextIdx++
        if (nextIdx >= pageBreaks.size) return null
        val startLine = pageBreaks[nextIdx]
        val endLine = if (nextIdx + 1 < pageBreaks.size) pageBreaks[nextIdx + 1] else globalLines.size
        return buildPageContent(startLine, endLine)
    }

    fun loadChapters() {
        scope.launch {
            try {
                val list = fetchChaptersFromServer()
                cachedChapters = list.sortedBy { it.startCharOffset }
                chapterStartOffsetSet = list.map { it.startCharOffset }.toSet()
                onChaptersReady?.invoke(list)
                if (windowBlocks.isNotEmpty()) {
                    recalculatePageBreaks()
                    showCurrentPage()
                }
            } catch (e: Exception) {
                Log.e("TextStreamProvider", "章节加载失败", e)
            }
        }
    }

    fun jumpToChapter(chapter: TextPreviewViewModel.ChapterInfo) {
        val targetBlock = chapter.serverPage
        scope.launch {
            try {
                val pagesToLoad = ((targetBlock - 2)..(targetBlock + 2)).filter { it >= 1 }
                windowBlocks = pagesToLoad.map { fetchBlock(it) }.sortedBy { it.blockPage }
                rebuildGlobalLines()
                recalculatePageBreaks()
                currentPageIndex = findPageForAbsoluteOffset(chapter.startCharOffset)
                showCurrentPage()
                checkAndPreload()
            } catch (e: Exception) {
                onError?.invoke("章节跳转失败: ${e.message}")
            }
        }
    }

    // 释放资源
    fun release() {
        scope.cancel()
    }

    // ── 内部方法 ──

    private suspend fun fetchBlock(page: Int): BlockData {
        blockCache[page]?.let { return it }
        val url = "$fileUrl${if (fileUrl.contains("?")) "&" else "?"}page=$page"
        val json = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.string() ?: throw Exception("空响应")
        }
        val obj = JSONObject(json)
        val content = obj.getString("content")
        val pagination = obj.getJSONObject("pagination")
        val blockData = BlockData(
            fullText = content,
            blockPage = pagination.getInt("currentPage"),
            totalBlockPages = pagination.getInt("totalPages"),
            startChar = pagination.optInt("startChar", 0),
            endChar = pagination.optInt("endChar", content.length)
        )
        totalServerBlocks = blockData.totalBlockPages
        blockCache[page] = blockData
        trimCache()
        return blockData
    }

    private fun trimCache() {
        if (windowBlocks.isEmpty()) return
        val minPage = windowBlocks.minOf { it.blockPage } - 2
        val maxPage = windowBlocks.maxOf { it.blockPage } + 2
        blockCache.keys.filter { it < minPage || it > maxPage }.forEach { blockCache.remove(it) }
    }

    private fun buildStaticLayout(text: String): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setIncludePad(true)
            .build()
    }

    private fun rebuildGlobalLines() {
        val lines = mutableListOf<LineInfo>()
        for (block in windowBlocks) {
            val layout = buildStaticLayout(block.fullText)
            for (i in 0 until layout.lineCount) {
                val lineStart = layout.getLineStart(i)
                lines.add(LineInfo(block, i, block.startChar + lineStart))
            }
        }
        globalLines = lines
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

    private fun isPageBlank(pageIndex: Int): Boolean {
        if (pageIndex < 0 || pageIndex >= pageBreaks.size) return true
        val startLine = pageBreaks[pageIndex]
        val endLine = if (pageIndex + 1 < pageBreaks.size) pageBreaks[pageIndex + 1] else globalLines.size
        for (i in startLine until endLine) {
            val line = globalLines[i]
            val layout = buildStaticLayout(line.block.fullText)
            val lineStart = layout.getLineStart(line.lineIndexInBlock)
            val lineEnd = layout.getLineEnd(line.lineIndexInBlock)
            if (line.block.fullText.substring(lineStart, lineEnd).isNotBlank()) return false
        }
        return true
    }

    private fun findPageForAbsoluteOffset(offset: Int): Int {
        if (globalLines.isEmpty() || pageBreaks.isEmpty()) return 0
        val lineIdx = globalLines.indexOfFirst { it.absoluteStartChar > offset }
        val actualLine = if (lineIdx == -1) globalLines.size - 1 else maxOf(lineIdx - 1, 0)
        var low = 0
        var high = pageBreaks.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            if (actualLine < pageBreaks[mid]) high = mid - 1
            else if (mid + 1 < pageBreaks.size && actualLine >= pageBreaks[mid + 1]) low = mid + 1
            else return mid
        }
        return 0
    }

    private fun showCurrentPage() {
        if (pageBreaks.isEmpty()) return
        while (currentPageIndex in 0 until pageBreaks.size && isPageBlank(currentPageIndex)) {
            val next = (currentPageIndex + 1 until pageBreaks.size).firstOrNull { !isPageBlank(it) }
            if (next != null) currentPageIndex = next else break
        }
        val startLine = pageBreaks[currentPageIndex]
        val endLine = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size
        val content = buildPageContent(startLine, endLine)
        currentAbsoluteCharOffset = globalLines[startLine].absoluteStartChar
        onPageContentReady?.invoke(content)
    }

    private fun buildPageContent(startLine: Int, endLine: Int): CharSequence {
        if (startLine >= globalLines.size) return ""
        val pageStartAbs = globalLines[startLine].absoluteStartChar
        val pageEndAbs = if (endLine < globalLines.size) globalLines[endLine].absoluteStartChar
        else {
            val lastBlock = globalLines.last().block
            lastBlock.startChar + lastBlock.fullText.length
        }
        val sb = StringBuilder()
        for (block in windowBlocks) {
            val blockStart = block.startChar
            val blockEnd = blockStart + block.fullText.length
            if (blockEnd <= pageStartAbs || blockStart >= pageEndAbs) continue
            val s = (pageStartAbs - blockStart).coerceAtLeast(0)
            val e = (pageEndAbs - blockStart).coerceAtMost(block.fullText.length)
            sb.append(block.fullText.substring(s, e))
        }
        return applyChapterStyles(sb.toString(), pageStartAbs)
    }

    private fun applyChapterStyles(text: String, pageStart: Int): CharSequence {
        if (cachedChapters.isEmpty()) return text
        val spannable = android.text.SpannableString(text)
        for (ch in cachedChapters) {
            val abs = ch.startCharOffset
            if (abs in pageStart until pageStart + text.length) {
                val start = abs - pageStart
                val end = min(start + ch.title.length, text.length)
                spannable.setSpan(android.text.style.AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.RelativeSizeSpan(1.5f), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return spannable
    }

    private suspend fun fetchChaptersFromServer(): List<TextPreviewViewModel.ChapterInfo> {
        val baseUrl = if (fileUrl.contains("/preview/")) fileUrl.substringBefore("/preview/")
        else fileUrl.substringBeforeLast("/")
        val encoded = URLEncoder.encode(fileName, "UTF-8")
        val url = "$baseUrl/chapters/$encoded"
        val json = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).addHeader("Accept", "application/json").build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("章节请求失败")
            response.body?.string() ?: ""
        }
        return parseChaptersJson(json)
    }

    private fun parseChaptersJson(json: String): List<TextPreviewViewModel.ChapterInfo> {
        val list = mutableListOf<TextPreviewViewModel.ChapterInfo>()
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("chapters") ?: obj.optJSONObject("data")?.optJSONArray("chapters") ?: return list
        for (i in 0 until arr.length()) {
            val ch = arr.getJSONObject(i)
            list.add(TextPreviewViewModel.ChapterInfo(
                title = ch.optString("title", "未知"),
                serverPage = ch.optInt("page", 1),
                lineNumber = ch.optInt("lineNumber", 0),
                startCharOffset = ch.optInt("startCharOffset", 0)
            ))
        }
        return list
    }

    private fun checkAndPreload() {
        if (windowBlocks.isEmpty()) return
        val firstBlock = windowBlocks.first()
        val lastBlock = windowBlocks.last()
        val curPageStartLine = pageBreaks.getOrElse(currentPageIndex) { 0 }
        val curPageEndLine = if (currentPageIndex + 1 < pageBreaks.size) pageBreaks[currentPageIndex + 1] else globalLines.size

        // 头部预加载
        if (curPageStartLine < linesPerPage && firstBlock.blockPage > 1) {
            val prev = firstBlock.blockPage - 1
            if (!blockCache.containsKey(prev)) {
                scope.launch {
                    fetchBlock(prev)
                    if (windowBlocks.firstOrNull()?.blockPage == firstBlock.blockPage) {
                        windowBlocks = listOf(blockCache[prev]!!) + windowBlocks
                        rebuildGlobalLines()
                        recalculatePageBreaks()
                        val offset = currentAbsoluteCharOffset
                        currentPageIndex = findPageForAbsoluteOffset(offset)
                        showCurrentPage()
                    }
                }
            }
        }
        // 尾部预加载
        val distanceToEnd = globalLines.size - curPageEndLine
        if (distanceToEnd < linesPerPage && lastBlock.blockPage < totalServerBlocks) {
            val next = lastBlock.blockPage + 1
            if (!blockCache.containsKey(next)) {
                scope.launch {
                    fetchBlock(next)
                    if (windowBlocks.lastOrNull()?.blockPage == lastBlock.blockPage) {
                        windowBlocks = windowBlocks + blockCache[next]!!
                        rebuildGlobalLines()
                        recalculatePageBreaks()
                        showCurrentPage()
                    }
                }
            }
        }
    }

    private fun invalidateLayouts() {
        rebuildGlobalLines()
        recalculatePageBreaks()
        if (pageBreaks.isNotEmpty()) {
            currentPageIndex = findPageForAbsoluteOffset(currentAbsoluteCharOffset)
            showCurrentPage()
        }
    }
}