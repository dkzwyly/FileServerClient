package com.dkc.fileserverclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

object ThumbnailLoader {
    private const val TAG = "ThumbnailLoader"
    private val unsafeHttpClient: OkHttpClient by lazy {
        UnsafeHttpClient.createUnsafeOkHttpClient()
    }

    /**
     * 从服务器加载视频缩略图，如果不存在则返回null（客户端显示占位图）
     */
    suspend fun loadVideoThumbnail(
        serverUrl: String,
        videoPath: String,
        width: Int = 320,
        height: Int = 180
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val encodedPath = java.net.URLEncoder.encode(videoPath, "UTF-8")
            val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/video-thumbnail/$encodedPath?width=$width&height=$height"

            Log.d(TAG, "加载视频缩略图: $videoPath, URL: $thumbnailUrl")

            val request = Request.Builder()
                .url(thumbnailUrl)
                .get()
                .build()

            val response = unsafeHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val contentType = response.header("Content-Type", "")
                response.body?.byteStream()?.use { inputStream ->
                    if (contentType?.contains("svg") == true) {
                        // 收到的是SVG占位图，返回null让客户端显示默认占位图
                        Log.d(TAG, "收到占位图，不处理SVG: $videoPath")
                        return@withContext null
                    } else {
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            Log.d(TAG, "视频缩略图加载成功: $videoPath")
                            return@withContext bitmap
                        }
                    }
                }
            } else {
                Log.w(TAG, "视频缩略图请求失败: HTTP ${response.code}")
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "加载视频缩略图异常: ${e.message}", e)
            null
        }
    }

    /**
     * 预加载多个视频缩略图
     */
    suspend fun preloadVideoThumbnails(
        serverUrl: String,
        videoItems: List<FileSystemItem>,
        width: Int = 320,
        height: Int = 180
    ) {
        withContext(Dispatchers.IO) {
            videoItems.forEach { videoItem ->
                try {
                    loadVideoThumbnail(serverUrl, videoItem.path, width, height)
                } catch (e: Exception) {
                    Log.d(TAG, "预加载缩略图失败: ${videoItem.name}")
                }
            }
        }
    }

    /**
     * 检查缩略图是否存在（不实际加载）
     */
    suspend fun checkThumbnailExists(
        serverUrl: String,
        videoPath: String,
        width: Int = 320,
        height: Int = 180
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedPath = java.net.URLEncoder.encode(videoPath, "UTF-8")
            val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/video-thumbnail/$encodedPath?width=$width&height=$height"

            val request = Request.Builder()
                .url(thumbnailUrl)
                .head() // 使用HEAD请求检查是否存在
                .build()

            val response = unsafeHttpClient.newCall(request).execute()
            val contentType = response.header("Content-Type", "")

            response.isSuccessful && contentType?.contains("image/jpeg") == true
        } catch (e: Exception) {
            Log.e(TAG, "检查缩略图存在性异常: ${e.message}", e)
            false
        }
    }
}