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

    // 当前使用的合并文本块（拼接当前大页与下一个大页）
    private var combinedBlock: CombinedBlock? = null
    // 当前显示的子页索引（基于 combinedBlock 的子页边界）
    private var currentSubPage = 1
    // 子页边界列表（基于合并文本）
    private val subPageBoundaries = mutableListOf<Pair<Int, Int>>()

    private var restoredBlockPage = 1
    private var restoredSubPage = 1
    private var isHistoryRestored = false

    private var pendingCharOffset: Int? = null

    private var cachedChapters: List<ChapterInfo>? = null

    // 预加载相关
    private var nextBlockAfterCombined: BlockData? = null   // 当下一个合并块中的第二个大页
    private var isPreloading = false

    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    // 数据类
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
    // 合并块：由当前大页和下一个大页拼接而成
    data class CombinedBlock(
        val firstBlock: BlockData,      // 当前大页
        val secondBlock: BlockData?,    // 下一个大页（如果存在）
        val fullText: String,           // 拼接後的全文
        val firstBlockEndChar: Int      // 第一个块在 fullText 中的结束字符索引（用于判断是否跨页）
    )

    // ---------- 公开初始化 ----------
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
                layout.height <= maxHeight -> { best = mid; low = mid + 1 }
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
            combinedBlock?.let { rebuildSubPages(it) }
        }
    }

    fun onFontSizeChanged(newLinesPerPage: Int) {
        if (combinedBlock == null) return
        val currentStartChar = getCurrentVisibleStartChar()
        linesPerPage = newLinesPerPage.coerceAtLeast(2)
        combinedBlock?.let { block ->
            rebuildSubPages(block)
            currentSubPage = findSubPageForCharOffset(block.fullText, currentStartChar)
            showCurrentSubPage()
        }
    }

    private fun getCurrentVisibleStartChar(): Int {
        val block = combinedBlock ?: return 0
        val (startLine, _) = subPageBoundaries.getOrElse(currentSubPage - 1) { return 0 }
        val layout = buildStaticLayout(block.fullText)
        return layout.getLineStart(startLine)
    }

    fun restoreFromHistory(blockPage: Int, subPage: Int) {
        restoredBlockPage = blockPage
        restoredSubPage = subPage
        isHistoryRestored = true
    }

    fun getCurrentPageState(): PageState? {
        val com = combinedBlock ?: return null
        return PageState(com.firstBlock.blockPage, currentSubPage, com.firstBlock.totalBlockPages, subPageBoundaries.size)
    }

    fun loadTextContent() {
        if (textWidth <= 0 || textPaint == null) return
        val targetPage = if (isHistoryRestored) restoredBlockPage else 1
        loadCombinedBlock(targetPage)
    }

    fun previousPage() {
        val com = combinedBlock ?: return
        if (currentSubPage > 1) {
            currentSubPage--
            showCurrentSubPage()
            // 检查是否完全退回到上一大页
            if (isSubPageBeforeFirstBlock()) {
                // 需要加载前一个大页
                loadCombinedBlock(com.firstBlock.blockPage - 1, goToLastSubPage = true)
            }
        } else if (com.firstBlock.blockPage > 1) {
            loadCombinedBlock(com.firstBlock.blockPage - 1, goToLastSubPage = true)
        }
    }

    fun nextPage() {
        val com = combinedBlock ?: return
        if (currentSubPage < subPageBoundaries.size) {
            currentSubPage++
            showCurrentSubPage()
        } else if (com.secondBlock != null && com.firstBlock.blockPage < com.firstBlock.totalBlockPages) {
            // 已经消耗了第一个块，跨到下一个块
            if (isSubPageBeyondFirstBlock()) {
                // 当前子页的起点已经在第二个块内，这时应切换到下一个合并块
                loadCombinedBlock(com.firstBlock.blockPage + 1, goToFirstSubPage = true)
            }
        } else if (com.firstBlock.blockPage < com.firstBlock.totalBlockPages) {
            // 没有第二个块，但还有后续页
            loadCombinedBlock(com.firstBlock.blockPage + 1, goToFirstSubPage = true)
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
                if (combinedBlock != null) showCurrentSubPage()
            } catch (e: Exception) {
                _errorMessage.value = "章节加载失败: ${e.message}"
            } finally {
                _loadingState.value = LoadingState(false)
            }
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) {
        pendingCharOffset = chapter.startCharOffset
        // 根据章节所在服务器页号加载对应的合并块
        loadCombinedBlock(chapter.serverPage, goToFirstSubPage = false)
    }

    // ---------- 内部 ----------
    private fun loadCombinedBlock(page: Int, goToFirstSubPage: Boolean = false, goToLastSubPage: Boolean = false) {
        _loadingState.value = LoadingState(true, "正在加载...")
        viewModelScope.launch {
            try {
                // 加载请求的页面作为第一块
                val firstBlock = fetchBlock(page)
                var secondBlock: BlockData? = null
                // 如果下一页存在，加载下一页作为第二块
                if (page < firstBlock.totalBlockPages) {
                    try {
                        secondBlock = fetchBlock(page + 1)
                    } catch (e: Exception) {
                        Log.e("ViewModel", "预加载下一页失败", e)
                    }
                }

                // 拼接全文
                val fullText = if (secondBlock != null) {
                    firstBlock.fullText + secondBlock.fullText
                } else {
                    firstBlock.fullText
                }
                val combined = CombinedBlock(
                    firstBlock = firstBlock,
                    secondBlock = secondBlock,
                    fullText = fullText,
                    firstBlockEndChar = firstBlock.fullText.length
                )

                combinedBlock = combined
                // 等待章节数据就绪
                ensureChaptersLoaded()

                // 重新分页
                rebuildSubPages(combined)

                // 确定子页
                val targetOffset = pendingCharOffset
                pendingCharOffset = null
                currentSubPage = when {
                    targetOffset != null -> findSubPageForCharOffset(fullText, targetOffset)
                    goToFirstSubPage -> 1
                    goToLastSubPage -> subPageBoundaries.size
                    isHistoryRestored && page == restoredBlockPage -> {
                        restoredSubPage.coerceIn(1, subPageBoundaries.size).also { isHistoryRestored = false }
                    }
                    else -> 1
                }

                showCurrentSubPage()
                _loadingState.value = LoadingState(false)

                // 如果第一页之后还有内容，确保第二块存在；否则清除预加载
                // 不再需要 explicit preload 因为已经用了
            } catch (e: Exception) {
                _errorMessage.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
                Log.e("ViewModel", "loadCombinedBlock error", e)
            }
        }
    }

    private suspend fun fetchBlock(page: Int): BlockData {
        val url = buildBlockUrl(page)
        val json = fetchJson(url)
        return parseBlockResponse(json)
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

    // ─── 分页（基于合并文本，标题断页） ───
    private fun rebuildSubPages(combined: CombinedBlock) {
        subPageBoundaries.clear()
        if (textWidth <= 0 || textPaint == null || combined.fullText.isEmpty()) return

        val layout = buildStaticLayout(combined.fullText)
        val totalLines = layout.lineCount
        if (totalLines == 0) return

        // 获取所有章节标题在合并文本中的起始行
        val chapterStartLines = mutableSetOf<Int>()
        if (cachedChapters != null) {
            val baseOffset = combined.firstBlock.startChar   // 第一个块的绝对起始偏移
            val firstLen = combined.firstBlock.fullText.length
            for (ch in cachedChapters!!) {
                val absOffset = ch.startCharOffset
                // 检查是否在当前合并文本的范围内
                val localOffset = if (absOffset >= baseOffset && absOffset < baseOffset + combined.fullText.length) {
                    absOffset - baseOffset
                } else {
                    // 章节可能在第二块中，但 baseOffset 已经是第一块的绝对偏移
                    continue
                }
                if (localOffset < 0 || localOffset >= combined.fullText.length) continue
                val line = layout.getLineForOffset(localOffset)
                chapterStartLines.add(line)
            }
        }

        var startLine = 0
        while (startLine < totalLines) {
            var endLine = minOf(startLine + linesPerPage, totalLines)

            // 处理标题断页：如果 endLine 正好是标题行，则将标题留给下一页
            if (endLine < totalLines && chapterStartLines.contains(endLine)) {
                if (endLine > startLine + 1) endLine--
            }
            // 如果标题行在页面中间，则提前断开
            for (line in startLine until endLine) {
                if (chapterStartLines.contains(line) && line != startLine) {
                    endLine = line
                    break
                }
            }
            // 孤行处理
            if (endLine < totalLines && totalLines - endLine < 2 && startLine + 1 < endLine) {
                endLine--
            }
            subPageBoundaries.add(Pair(startLine, endLine))
            startLine = endLine
        }
    }

    private fun findSubPageForCharOffset(fullText: String, absCharOffset: Int): Int {
        if (fullText.isEmpty() || subPageBoundaries.isEmpty()) return 1
        val layout = buildStaticLayout(fullText)
        val line = layout.getLineForOffset(absCharOffset.coerceIn(0, fullText.length))
        for ((i, bounds) in subPageBoundaries.withIndex()) {
            if (line in bounds.first until bounds.second) return i + 1
        }
        return 1
    }

    // 判断当前子页是否完全位于第一块之前（即需要回退到前一个大页）
    private fun isSubPageBeforeFirstBlock(): Boolean {
        val com = combinedBlock ?: return false
        val (startLine, _) = subPageBoundaries.getOrNull(currentSubPage - 1) ?: return false
        val layout = buildStaticLayout(com.fullText)
        val startChar = layout.getLineStart(startLine)
        return startChar < 0 // 不会发生，实际上需要判断 startChar 是否小于 firstBlock.fullText.length
    }

    // 判断当前子页是否已经越过了第一块的末尾
    private fun isSubPageBeyondFirstBlock(): Boolean {
        val com = combinedBlock ?: return false
        val (startLine, _) = subPageBoundaries.getOrNull(currentSubPage - 1) ?: return false
        val layout = buildStaticLayout(com.fullText)
        val startChar = layout.getLineStart(startLine)
        return startChar >= com.firstBlockEndChar
    }

    // ─── 显示当前子页 ───
    private fun showCurrentSubPage() {
        val com = combinedBlock ?: return
        if (subPageBoundaries.isEmpty()) {
            _pageContent.value = com.fullText
            updatePageInfo()
            _currentAbsoluteCharOffset.value = com.firstBlock.startChar
            return
        }
        val (startLine, endLine) = subPageBoundaries.getOrElse(currentSubPage - 1) {
            currentSubPage = 1
            subPageBoundaries.first()
        }
        val layout = buildStaticLayout(com.fullText)
        val startChar = layout.getLineStart(startLine)
        val endChar = if (endLine >= layout.lineCount) com.fullText.length else layout.getLineStart(endLine)
        val pageText = com.fullText.substring(startChar, minOf(endChar, com.fullText.length))

        // 应用章节标题富文本
        val spannable = applyChapterStyles(com.fullText, pageText, startChar, com.firstBlock.startChar)
        _pageContent.value = spannable
        updatePageInfo()
        _currentAbsoluteCharOffset.value = com.firstBlock.startChar + startChar
    }

    private fun applyChapterStyles(
        fullText: String, pageText: String, pageStartInFull: Int, baseAbsoluteOffset: Int
    ): CharSequence {
        val chapters = cachedChapters ?: return pageText
        val spannable = SpannableString(pageText)
        for (ch in chapters) {
            val localOffset = ch.startCharOffset - baseAbsoluteOffset
            if (localOffset < pageStartInFull || localOffset >= pageStartInFull + pageText.length) continue
            val startInPage = localOffset - pageStartInFull
            val titleLen = minOf(ch.title.length, pageText.length - startInPage)
            if (titleLen <= 0) continue
            val endInPage = startInPage + titleLen
            spannable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), startInPage, endInPage, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(1.5f), startInPage, endInPage, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), startInPage, endInPage, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun updatePageInfo() {
        val com = combinedBlock ?: return
        val totalPages = com.firstBlock.totalBlockPages
        val progress = if (totalPages > 0) ((com.firstBlock.blockPage - 1) * 100 / totalPages) else 0
        _pageInfo.value = PageInfo(com.firstBlock.blockPage, totalPages, progress)
        _currentPageState.value = PageState(com.firstBlock.blockPage, currentSubPage, totalPages, subPageBoundaries.size)
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