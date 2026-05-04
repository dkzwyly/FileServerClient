// CoverImageStorage.kt
package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 封面图片本地存储管理器
 * 下载服务器封面并持久化到应用私有目录，后续加载直接使用本地文件
 */
object CoverImageStorage {

    private const val TAG = "CoverImageStorage"
    private const val COVER_DIR = "audio_covers"

    private lateinit var appContext: Context
    private lateinit var httpClient: OkHttpClient

    // 避免对同一 URL 重复下载
    private val downloadJobs = mutableMapOf<String, Job>()

    /**
     * 初始化（必须在 Application 或首次使用前调用）
     */
    fun init(context: Context, client: OkHttpClient) {
        appContext = context.applicationContext
        httpClient = client
        getCoverDir().mkdirs()
    }

    /**
     * 获取本地文件对象（无论是否存在）
     */
    fun getLocalFile(trackId: String, coverUrl: String): File {
        val fileName = getFileName(trackId, coverUrl)
        return File(getCoverDir(), fileName)
    }

    /**
     * 本地是否已存在该封面
     */
    fun isCoverCached(trackId: String, coverUrl: String): Boolean =
        getLocalFile(trackId, coverUrl).exists()

    /**
     * 异步下载封面并保存，通过回调通知结果
     * @return 是否提交了下载任务（false 表示文件已存在或已在下载中）
     */
    fun downloadCover(
        trackId: String,
        coverUrl: String,
        scope: CoroutineScope,
        onResult: (File?) -> Unit
    ): Boolean {
        val localFile = getLocalFile(trackId, coverUrl)
        if (localFile.exists()) {
            onResult(localFile)
            return false
        }

        val jobKey = coverUrl
        if (downloadJobs.containsKey(jobKey)) return false

        val job = scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(coverUrl).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(localFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) { onResult(localFile) }
                } else {
                    withContext(Dispatchers.Main) { onResult(null) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "下载封面失败: $coverUrl", e)
                withContext(Dispatchers.Main) { onResult(null) }
            } finally {
                downloadJobs.remove(jobKey)
            }
        }
        downloadJobs[jobKey] = job
        return true
    }

    /**
     * 清除所有封面文件
     */
    fun clearAllCovers() {
        getCoverDir().listFiles()?.forEach { it.delete() }
    }

    private fun getCoverDir(): File = File(appContext.filesDir, COVER_DIR)

    private fun getFileName(trackId: String, coverUrl: String): String {
        // 使用 trackId + coverUrl 的 MD5 作为文件名，确保唯一性
        val input = "$trackId$coverUrl"
        val hash = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) } + ".jpg"
    }
}