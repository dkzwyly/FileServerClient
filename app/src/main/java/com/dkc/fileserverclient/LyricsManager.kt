package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.*
import java.util.*

// 歌词相关数据类
data class LyricsLine(
    val time: Long,
    val text: String
)

data class LyricsData(
    val lines: List<LyricsLine>,
    val title: String = "",
    val artist: String = "",
    val album: String = ""
)

class LyricsManager(
    private val context: Context,
    private val handler: Handler,
    private val coroutineScope: CoroutineScope
) {
    private var lyricsData: LyricsData? = null
    private var isLyricsLoading = false
    private var lyricsLoadJob: Job? = null
    private var lyricsUpdateRunnable: Runnable? = null

    // 状态回调接口
    interface LyricsStateListener {
        fun onLyricsLoaded(data: LyricsData?, title: String?)
        fun onLyricsUpdated(currentLine: String?, nextLine: String?)
        fun onLyricsError(message: String)
        fun onLyricsFileSelected(files: List<FileServerService.LyricsFileInfo>)
        fun onNoLyrics()
    }

    private var listener: LyricsStateListener? = null

    fun setListener(listener: LyricsStateListener) {
        this.listener = listener
    }

    // 加载歌词
    fun loadLyrics(
        serverUrl: String,
        songPath: String,
        fileName: String = ""
    ) {
        Log.d("LyricsManager", "loadLyrics called")

        // 取消之前的加载任务
        lyricsLoadJob?.cancel()
        stopLyricsUpdates()

        // 重置加载状态
        isLyricsLoading = false

        lyricsLoadJob = coroutineScope.launch {
            try {
                isLyricsLoading = true
                Log.d("LyricsManager", "开始加载歌词...")

                val fileServerService = FileServerService(context)

                // 首先检查是否有映射关系
                val mapping = fileServerService.getLyricsMapping(serverUrl, songPath)
                Log.d("LyricsManager", "歌词映射结果: $mapping")

                if (mapping != null && mapping.lyricsPath == "NO_LYRICS") {
                    // 已标记为无歌词
                    withContext(Dispatchers.Main) {
                        listener?.onNoLyrics()
                        isLyricsLoading = false
                    }
                    return@launch
                }

                val lyricsResponse = fileServerService.getLyrics(serverUrl, songPath)
                Log.d("LyricsManager", "歌词响应类型: ${lyricsResponse.type}")

                withContext(Dispatchers.Main) {
                    when (lyricsResponse.type) {
                        "lyrics_content" -> {
                            lyricsResponse.content?.let { content ->
                                Log.d("LyricsManager", "解析歌词内容，长度: ${content.length}")
                                parseLyricsContent(content, fileName)
                            } ?: run {
                                listener?.onLyricsError("歌词内容为空")
                            }
                        }
                        "available_files" -> {
                            lyricsResponse.files?.let { files ->
                                Log.d("LyricsManager", "显示歌词文件选择，文件数: ${files.size}")
                                listener?.onLyricsFileSelected(files)
                            }
                        }
                        "no_lyrics" -> {
                            listener?.onNoLyrics()
                        }
                        else -> {
                            listener?.onLyricsError("未找到歌词文件")
                        }
                    }
                    isLyricsLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("LyricsManager", "加载歌词失败", e)
                    listener?.onLyricsError("歌词加载失败: ${e.message}")
                    isLyricsLoading = false
                }
            }
        }
    }

    private fun parseLyricsContent(content: String, fileName: String) {
        val lines = mutableListOf<LyricsLine>()
        var title = ""
        var artist = ""
        var album = ""

        content.split("\n").forEach { line ->
            when {
                line.startsWith("[ti:") -> {
                    title = line.substring(4, line.length - 1).trim()
                }
                line.startsWith("[ar:") -> {
                    artist = line.substring(4, line.length - 1).trim()
                }
                line.startsWith("[al:") -> {
                    album = line.substring(4, line.length - 1).trim()
                }
                line.matches(Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\].+")) -> {
                    val timeMatch = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]").find(line)
                    if (timeMatch != null) {
                        val (minutes, seconds, milliseconds) = timeMatch.destructured
                        val time = minutes.toLong() * 60000 +
                                seconds.toLong() * 1000 +
                                milliseconds.padEnd(3, '0').take(3).toLong()

                        val text = line.substring(timeMatch.range.last + 1).trim()
                        if (text.isNotEmpty()) {
                            lines.add(LyricsLine(time, text))
                        }
                    }
                }
                line.matches(Regex("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{2,3}\\].+")) -> {
                    val timeMatch = Regex("\\[(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{2,3})\\]").find(line)
                    if (timeMatch != null) {
                        val (hours, minutes, seconds, milliseconds) = timeMatch.destructured
                        val time = hours.toLong() * 3600000 +
                                minutes.toLong() * 60000 +
                                seconds.toLong() * 1000 +
                                milliseconds.padEnd(3, '0').take(3).toLong()

                        val text = line.substring(timeMatch.range.last + 1).trim()
                        if (text.isNotEmpty()) {
                            lines.add(LyricsLine(time, text))
                        }
                    }
                }
            }
        }

        // 按时间排序
        lines.sortBy { it.time }

        // 如果没有解析到标题，使用文件名
        if (title.isEmpty() && fileName.isNotEmpty()) {
            title = fileName.substringBeforeLast(".")
        }

        lyricsData = LyricsData(lines, title, artist, album)

        // 构建标题文本
        val titleText = buildString {
            if (title.isNotEmpty()) append(title)
            if (artist.isNotEmpty()) {
                if (isNotEmpty()) append(" - ")
                append(artist)
            }
            if (album.isNotEmpty()) {
                if (isNotEmpty()) append(" (")
                append(album)
                if (isNotEmpty()) append(")")
            }
        }

        listener?.onLyricsLoaded(lyricsData, titleText)
    }

    fun startLyricsUpdates() {
        // 如果已经在更新，先停止
        stopLyricsUpdates()

        lyricsUpdateRunnable = Runnable { updateLyricsDisplay() }

        // 立即执行第一次更新
        handler.post(lyricsUpdateRunnable!!)

        Log.d("LyricsManager", "开始歌词更新")
    }

    fun stopLyricsUpdates() {
        lyricsUpdateRunnable?.let {
            handler.removeCallbacks(it)
            lyricsUpdateRunnable = null
        }

        Log.d("LyricsManager", "停止歌词更新")
    }

    private fun updateLyricsDisplay() {
        lyricsData?.let { data ->
            listener?.let { listener ->
                try {
                    // 获取当前时间（由监听器提供）
                    val currentTime = (listener as? TimeProvider)?.getCurrentTime() ?: 0L

                    // 检查时间是否有效（不为0）
                    if (currentTime <= 0L) {
                        // 如果时间无效，延迟后重试
                        lyricsUpdateRunnable?.let {
                            handler.postDelayed(it, 500L)
                        }
                        return
                    }

                    val currentLine = findCurrentLyricsLine(data.lines, currentTime)
                    val nextLine = findNextLyricsLine(data.lines, currentTime)

                    // 关键：在主线程更新UI
                    handler.post {
                        (listener as? LyricsStateListener)?.onLyricsUpdated(
                            currentLine?.text,
                            nextLine?.text
                        )
                    }

                    // 根据是否在播放决定更新频率（由监听器提供）
                    val isPlaying = (listener as? PlayStateProvider)?.isPlaying() ?: true
                    val updateDelay = if (isPlaying) 100L else 500L

                    // 确保Runnable不为null再post
                    lyricsUpdateRunnable?.let {
                        handler.postDelayed(it, updateDelay)
                    }
                } catch (e: Exception) {
                    Log.e("LyricsManager", "更新歌词显示异常", e)
                    // 出现异常时停止更新
                    stopLyricsUpdates()
                }
            }
        } ?: run {
            // 如果没有歌词数据，停止更新
            Log.d("LyricsManager", "没有歌词数据，停止更新")
            stopLyricsUpdates()
        }
    }

    private fun findCurrentLyricsLine(lines: List<LyricsLine>, currentTime: Long): LyricsLine? {
        for (i in lines.indices.reversed()) {
            if (currentTime >= lines[i].time) {
                return lines[i]
            }
        }
        return null
    }

    private fun findNextLyricsLine(lines: List<LyricsLine>, currentTime: Long): LyricsLine? {
        for (i in lines.indices) {
            if (currentTime < lines[i].time) {
                return lines[i]
            }
        }
        return null
    }

    fun getLyricsData(): LyricsData? = lyricsData

    fun clear() {
        lyricsLoadJob?.cancel()
        stopLyricsUpdates()

        // 只在销毁时清空歌词数据
        lyricsData = null

        isLyricsLoading = false

        Log.d("LyricsManager", "清理歌词管理器")
    }

    // 辅助接口
    interface TimeProvider {
        fun getCurrentTime(): Long
    }

    interface PlayStateProvider {
        fun isPlaying(): Boolean
    }

    // 标记为无歌词
    suspend fun markNoLyrics(serverUrl: String, songPath: String): Boolean {
        return try {
            val fileServerService = FileServerService(context)
            fileServerService.markNoLyrics(serverUrl, songPath)
        } catch (e: Exception) {
            false
        }
    }

    // 获取目录下的歌词文件
    suspend fun getLyricsFiles(serverUrl: String, directory: String): List<FileServerService.LyricsFileInfo> {
        return try {
            val fileServerService = FileServerService(context)
            fileServerService.getLyricsFiles(serverUrl, directory)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 保存歌词映射
    suspend fun saveLyricsMapping(
        serverUrl: String,
        songPath: String,
        lyricsPath: String
    ): Boolean {
        return try {
            val fileServerService = FileServerService(context)
            fileServerService.saveLyricsMapping(serverUrl, songPath, lyricsPath)
        } catch (e: Exception) {
            false
        }
    }
}