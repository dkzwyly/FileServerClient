package com.dkc.fileserverclient

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

    // LiveData for UI state
    private val _pageContent = MutableLiveData<String>()
    val pageContent: LiveData<String> get() = _pageContent

    private val _pageInfo = MutableLiveData<PageInfo>()
    val pageInfo: LiveData<PageInfo> get() = _pageInfo

    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> get() = _loadingState

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> get() = _errorMessage

    private val _chapters = MutableLiveData<List<ChapterInfo>>()
    val chapters: LiveData<List<ChapterInfo>> get() = _chapters

    private val _currentPageState = MutableLiveData<PageState>()
    val currentPageState: LiveData<PageState> get() = _currentPageState

    // Internal state
    private var currentServerPage = 1
    private var totalServerPages = 1
    private var currentClientPage = 1
    private var totalClientPages = 1
    private var linesPerPage = 20
    private var fullContent = ""

    // 文件信息
    lateinit var fileName: String
        private set
    lateinit var fileUrl: String
        private set
    private lateinit var filePath: String

    // 历史记录恢复标志
    private var isHistoryRestored = false

    private val httpClient = UnsafeHttpClient.createUnsafeOkHttpClient()

    // Data classes
    data class PageInfo(
        val currentPage: Int,
        val totalPages: Int,
        val progress: Int
    )

    data class LoadingState(
        val isLoading: Boolean,
        val message: String? = null
    )

    data class ChapterInfo(
        val title: String,
        val serverPage: Int,
        val clientPage: Int,
        val lineNumber: Int
    )

    data class PageState(
        val serverPage: Int,
        val clientPage: Int,
        val totalServerPages: Int,
        val totalClientPages: Int
    )

    // Public methods
    fun initialize(fileName: String, fileUrl: String, filePath: String) {
        this.fileName = fileName
        this.fileUrl = fileUrl
        this.filePath = filePath
        Log.d("TextPreviewViewModel", "初始化: fileName=$fileName, fileUrl=$fileUrl")
    }

    fun restoreFromHistory(serverPage: Int, clientPage: Int) {
        Log.d("TextPreviewViewModel", "恢复历史记录: 服务器页=$serverPage, 客户端页=$clientPage")
        currentServerPage = serverPage
        currentClientPage = clientPage
        isHistoryRestored = true
    }

    fun getCurrentPageState(): PageState? {
        return if (this::fileName.isInitialized) {
            PageState(
                serverPage = currentServerPage,
                clientPage = currentClientPage,
                totalServerPages = totalServerPages,
                totalClientPages = totalClientPages
            )
        } else {
            null
        }
    }

    fun loadTextContent(linesPerPage: Int) {
        this.linesPerPage = linesPerPage

        // 如果已经恢复了历史记录，直接从历史位置加载
        if (isHistoryRestored) {
            Log.d("TextPreviewViewModel", "从历史记录位置加载: 服务器页=$currentServerPage, 客户端页=$currentClientPage")
            loadServerPage()
        } else {
            // 否则从第一页开始
            currentServerPage = 1
            currentClientPage = 1
            loadServerPage()
        }
    }

    fun previousPage() {
        if (currentClientPage > 1) {
            currentClientPage--
            updateClientPage()
            updateCurrentPageState()
        } else if (currentServerPage > 1) {
            currentServerPage--
            currentClientPage = 1
            loadServerPage()
        } else {
            Log.d("TextPreviewViewModel", "已经是第一页了")
        }
    }

    fun nextPage() {
        if (currentClientPage < totalClientPages) {
            currentClientPage++
            updateClientPage()
            updateCurrentPageState()
        } else if (currentServerPage < totalServerPages) {
            currentServerPage++
            currentClientPage = 1
            loadServerPage()
        } else {
            Log.d("TextPreviewViewModel", "已经是最后一页了")
        }
    }

    fun loadChapters() {
        viewModelScope.launch {
            _loadingState.value = LoadingState(true, "正在加载章节...")
            try {
                val chaptersList = withContext(Dispatchers.IO) {
                    loadChaptersFromServer()
                }
                _chapters.value = chaptersList
                _loadingState.value = LoadingState(false)
                Log.d("TextPreviewViewModel", "加载章节成功: ${chaptersList.size} 个章节")
            } catch (e: Exception) {
                _errorMessage.value = "章节加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
                Log.e("TextPreviewViewModel", "加载章节失败", e)
            }
        }
    }

    fun jumpToChapter(chapter: ChapterInfo) {
        currentServerPage = chapter.serverPage
        currentClientPage = chapter.clientPage
        Log.d("TextPreviewViewModel", "跳转到章节: ${chapter.title}, 服务器页=$currentServerPage, 客户端页=$currentClientPage")
        loadServerPage()
    }

    // Private methods
    private fun loadServerPage() {
        Log.d("TextPreviewViewModel", "加载服务器页面: 服务器页=$currentServerPage")
        _loadingState.value = LoadingState(true, "正在加载...")

        viewModelScope.launch {
            try {
                val url = buildTextUrlWithPagination()
                Log.d("TextPreviewViewModel", "请求URL: $url")

                val textData = withContext(Dispatchers.IO) {
                    loadTextFromServer(url)
                }

                val jsonObject = JSONObject(textData)
                val content = jsonObject.optString("content", "")
                val pagination = jsonObject.optJSONObject("pagination")

                updatePaginationInfo(pagination)
                fullContent = content

                performClientPaging()
                updateCurrentPageState()
                _loadingState.value = LoadingState(false)
                Log.d("TextPreviewViewModel", "加载服务器页面成功")

            } catch (e: Exception) {
                _errorMessage.value = "文本加载失败: ${e.message}"
                _loadingState.value = LoadingState(false)
                Log.e("TextPreviewViewModel", "加载服务器页面失败", e)
            }
        }
    }

    private suspend fun loadTextFromServer(url: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }

            response.body?.string() ?: throw Exception("响应体为空")
        }
    }

    private fun buildTextUrlWithPagination(): String {
        return if (fileUrl.contains("?")) {
            "${fileUrl}&page=$currentServerPage"
        } else {
            "${fileUrl}?page=$currentServerPage"
        }
    }

    private fun updatePaginationInfo(pagination: JSONObject?) {
        pagination?.let {
            currentServerPage = it.optInt("currentPage", 1)
            totalServerPages = it.optInt("totalPages", 1)
            Log.d("TextPreviewViewModel", "更新分页信息: 当前服务器页=$currentServerPage, 总服务器页=$totalServerPages")
        }
    }

    private fun performClientPaging() {
        if (fullContent.isEmpty()) {
            _pageContent.value = ""
            Log.w("TextPreviewViewModel", "内容为空")
            return
        }

        try {
            val lines = fullContent.split("\n")
            totalClientPages = if (linesPerPage > 0) {
                (lines.size + linesPerPage - 1) / linesPerPage
            } else {
                18
            }

            currentClientPage = currentClientPage.coerceIn(1, totalClientPages)
            showCurrentClientPage(lines)
            updatePageInfo()
            Log.d("TextPreviewViewModel", "客户端分页完成: 当前客户端页=$currentClientPage, 总客户端页=$totalClientPages, 每页行数=$linesPerPage")

        } catch (e: Exception) {
            _errorMessage.value = "分页失败: ${e.message}"
            Log.e("TextPreviewViewModel", "分页失败", e)
        }
    }

    private fun showCurrentClientPage(lines: List<String>) {
        val startIndex = (currentClientPage - 1) * linesPerPage
        val endIndex = (startIndex + linesPerPage).coerceAtMost(lines.size)

        val pageLines = if (startIndex < lines.size) {
            lines.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        _pageContent.value = pageLines.joinToString("\n")
    }

    private fun updateClientPage() {
        if (fullContent.isNotEmpty()) {
            val lines = fullContent.split("\n")
            showCurrentClientPage(lines)
            updatePageInfo()
        }
    }

    private fun updateCurrentPageState() {
        _currentPageState.value = PageState(
            serverPage = currentServerPage,
            clientPage = currentClientPage,
            totalServerPages = totalServerPages,
            totalClientPages = totalClientPages
        )
    }

    private fun updatePageInfo() {
        val progress = calculateReadingProgress()
        _pageInfo.value = PageInfo(
            currentPage = currentClientPage,
            totalPages = totalClientPages,
            progress = progress
        )
    }

    private fun calculateReadingProgress(): Int {
        return if (totalServerPages > 0) {
            val serverProgress = ((currentServerPage - 1).toFloat() / totalServerPages * 100).toInt()
            serverProgress.coerceIn(0, 100)
        } else if (totalClientPages > 0) {
            val clientProgress = (currentClientPage.toFloat() / totalClientPages * 100).toInt()
            clientProgress.coerceAtMost(100)
        } else {
            0
        }
    }

    private suspend fun loadChaptersFromServer(): List<ChapterInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = if (fileUrl.contains("/preview/")) {
                    fileUrl.substringBefore("/preview/")
                } else {
                    // 如果没有preview路径，尝试提取基础URL
                    val urlParts = fileUrl.split("/api/")
                    if (urlParts.size > 1) {
                        "${urlParts[0]}/api"
                    } else {
                        fileUrl.substringBeforeLast("/")
                    }
                }

                val fileName = if (fileUrl.contains("/preview/")) {
                    fileUrl.substringAfter("/preview/").substringBefore("?")
                } else {
                    this@TextPreviewViewModel.fileName
                }

                val encodedFileName = URLEncoder.encode(fileName, "UTF-8")
                val chaptersUrl = "$baseUrl/chapters/$encodedFileName"

                Log.d("TextPreviewViewModel", "请求章节URL: $chaptersUrl")

                val request = Request.Builder()
                    .url(chaptersUrl)
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w("TextPreviewViewModel", "章节请求失败: HTTP ${response.code}")
                    return@withContext emptyList()
                }

                val jsonData = response.body?.string() ?: return@withContext emptyList()
                Log.d("TextPreviewViewModel", "章节响应: $jsonData")

                val jsonObject = JSONObject(jsonData)
                parseChaptersFromJson(jsonObject)
            } catch (e: Exception) {
                Log.e("TextPreviewViewModel", "加载章节失败", e)
                emptyList()
            }
        }
    }

    private fun parseChaptersFromJson(jsonObject: JSONObject): List<ChapterInfo> {
        val chapters = mutableListOf<ChapterInfo>()

        try {
            // 尝试不同的响应格式
            var chaptersArray = jsonObject.optJSONArray("chapters")
            if (chaptersArray == null) {
                val data = jsonObject.optJSONObject("data")
                chaptersArray = data?.optJSONArray("chapters")
            }

            if (chaptersArray != null) {
                for (i in 0 until chaptersArray.length()) {
                    try {
                        val chapterObj = chaptersArray.getJSONObject(i)
                        val title = chapterObj.optString("title", "未知章节")
                        val serverPage = chapterObj.optInt("page", 1)
                        val lineNumber = chapterObj.optInt("lineNumber", 0)
                        val clientPage = calculateClientPageFromLineNumber(lineNumber)

                        chapters.add(ChapterInfo(title, serverPage, clientPage, lineNumber))
                        Log.d("TextPreviewViewModel", "解析章节: $title, 服务器页=$serverPage, 行号=$lineNumber")
                    } catch (e: Exception) {
                        Log.e("TextPreviewViewModel", "解析单个章节失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TextPreviewViewModel", "解析章节JSON失败", e)
        }

        Log.d("TextPreviewViewModel", "成功解析 ${chapters.size} 个章节")
        return chapters
    }

    private fun calculateClientPageFromLineNumber(lineNumber: Int): Int {
        val relativeLineNumber = lineNumber % 1000
        val calculatedPage = (relativeLineNumber / linesPerPage) + 1
        return calculatedPage.coerceAtLeast(1)
    }
}