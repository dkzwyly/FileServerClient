package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.load
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一的缩略图加载器，支持图片和视频缩略图，带磁盘持久化缓存。
 * 使用：先在 Application 或 Activity 中调用 init(context) 初始化。
 */
object ThumbnailLoader {
    private const val TAG = "ThumbnailLoader"
    private const val MAX_CACHE_SIZE_MB = 200L
    private const val CACHE_DIR_NAME = "thumbnails"      // 统一缓存目录

    private lateinit var cacheDir: File
    private lateinit var httpClient: OkHttpClient
    private lateinit var coilLoader: ImageLoader
    private var isInitialized = false

    // 防止重复下载
    private val pendingDownloads = ConcurrentHashMap<String, Deferred<File?>>()
    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 初始化，必须在使用前调用一次
     */
    fun init(context: Context) {
        if (isInitialized) return
        cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

        httpClient = UnsafeHttpClient.createUnsafeOkHttpClient().newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        coilLoader = ImageLoader.Builder(context.applicationContext)
            .okHttpClient(httpClient)
            .build()

        isInitialized = true
        Log.d(TAG, "初始化完成，缓存目录: ${cacheDir.absolutePath}")

        // 异步检查并清理超出大小的缓存
        loaderScope.launch {
            delay(2000)
            trimCacheIfNeeded()
        }
    }

    // ======================= 公共 API =======================

    /**
     * 加载图片缩略图到 ImageView
     * @param imageView 目标 ImageView
     * @param imageItem 图片文件项
     * @param serverUrl 服务器地址
     * @param placeholderResId 占位图资源 ID
     * @param errorResId 错误占位图资源 ID
     */
    fun loadImageThumbnail(
        imageView: ImageView,
        imageItem: FileSystemItem,
        serverUrl: String,
        placeholderResId: Int = R.drawable.ic_image_placeholder,
        errorResId: Int = R.drawable.ic_image_placeholder
    ) {
        if (!isInitialized) init(imageView.context)

        val cacheKey = generateCacheKey(serverUrl, imageItem.path, "image")
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null && cachedFile.exists()) {
            // 缓存命中，直接加载本地文件
            imageView.load(cachedFile, coilLoader) {
                placeholder(placeholderResId)
                error(errorResId)
                crossfade(true)
            }
        } else {
            // 先显示占位图，异步下载并缓存
            imageView.setImageResource(placeholderResId)
            loaderScope.launch {
                val file = downloadThumbnail(
                    serverUrl = serverUrl,
                    path = imageItem.path,
                    type = "image",
                    cacheKey = cacheKey,
                    urlSuffix = "thumbnail"  // 图片缩略图接口
                )
                withContext(Dispatchers.Main) {
                    if (file != null && file.exists()) {
                        imageView.load(file, coilLoader) {
                            placeholder(placeholderResId)
                            error(errorResId)
                            crossfade(true)
                        }
                    } else {
                        imageView.setImageResource(errorResId)
                    }
                }
            }
        }
    }

    /**
     * 加载视频缩略图（返回 Bitmap，用于需要直接操作 Bitmap 的场景）
     * @param serverUrl 服务器地址
     * @param videoPath 视频路径
     * @param width 期望宽度
     * @param height 期望高度
     * @return Bitmap 或 null
     */
    suspend fun loadVideoThumbnailBitmap(
        serverUrl: String,
        videoPath: String,
        width: Int = 320,
        height: Int = 180
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext null

        val cacheKey = generateCacheKey(serverUrl, videoPath, "video", width, height)
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null && cachedFile.exists()) {
            return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)
        }

        // 下载并缓存
        val file = downloadThumbnail(
            serverUrl = serverUrl,
            path = videoPath,
            type = "video",
            cacheKey = cacheKey,
            urlSuffix = "video-thumbnail",
            width = width,
            height = height
        )
        return@withContext file?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    /**
     * 加载视频缩略图到 ImageView（自动缓存）
     */
    fun loadVideoThumbnail(
        imageView: ImageView,
        serverUrl: String,
        videoPath: String,
        width: Int = 320,
        height: Int = 180,
        placeholderResId: Int = R.drawable.ic_video_placeholder,
        errorResId: Int = R.drawable.ic_video_placeholder
    ) {
        if (!isInitialized) init(imageView.context)

        val cacheKey = generateCacheKey(serverUrl, videoPath, "video", width, height)
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null && cachedFile.exists()) {
            imageView.load(cachedFile, coilLoader) {
                placeholder(placeholderResId)
                error(errorResId)
                crossfade(true)
            }
        } else {
            imageView.setImageResource(placeholderResId)
            loaderScope.launch {
                val file = downloadThumbnail(
                    serverUrl = serverUrl,
                    path = videoPath,
                    type = "video",
                    cacheKey = cacheKey,
                    urlSuffix = "video-thumbnail",
                    width = width,
                    height = height
                )
                withContext(Dispatchers.Main) {
                    if (file != null && file.exists()) {
                        imageView.load(file, coilLoader) {
                            placeholder(placeholderResId)
                            error(errorResId)
                            crossfade(true)
                        }
                    } else {
                        imageView.setImageResource(errorResId)
                    }
                }
            }
        }
    }

    /**
     * 清除指定路径的缩略图缓存
     */
    fun clearCache(serverUrl: String, filePath: String, type: String = "image", width: Int = 0, height: Int = 0) {
        val cacheKey = generateCacheKey(serverUrl, filePath, type, width, height)
        getCachedFile(cacheKey)?.delete()
    }

    /**
     * 清除所有缩略图缓存
     */
    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "所有缩略图缓存已清除")
    }

    /**
     * 检查并清理超出大小限制的缓存
     */
    fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024
        if (totalSize <= maxSize) return

        val sortedFiles = files.sortedBy { it.lastModified() }
        for (file in sortedFiles) {
            if (totalSize <= maxSize) break
            totalSize -= file.length()
            file.delete()
        }
        Log.d(TAG, "缓存清理完成，当前大小: ${totalSize / (1024 * 1024)} MB")
    }

    // ======================= 私有方法 =======================

    private fun generateCacheKey(
        serverUrl: String,
        filePath: String,
        type: String,
        width: Int = 0,
        height: Int = 0
    ): String {
        val parts = "$serverUrl|$type|$filePath|${width}x$height"
        return md5(parts) + (if (type == "video") ".jpg" else ".thumb")
    }

    private fun getCachedFile(cacheKey: String): File? {
        val file = File(cacheDir, cacheKey)
        return if (file.exists() && file.length() > 0) file else null
    }

    private suspend fun downloadThumbnail(
        serverUrl: String,
        path: String,
        type: String,
        cacheKey: String,
        urlSuffix: String,
        width: Int = 0,
        height: Int = 0
    ): File? {
        // 合并相同请求
        pendingDownloads[cacheKey]?.let { return it.await() }

        val deferred = loaderScope.async {
            try {
                val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                var url = "${serverUrl.removeSuffix("/")}/api/fileserver/$urlSuffix/$encodedPath"
                if (width > 0 && height > 0) {
                    url += "?width=$width&height=$height"
                }

                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "下载缩略图失败: HTTP ${response.code}, path=$path")
                    return@async null
                }

                val body = response.body ?: return@async null
                val inputStream = body.byteStream()

                // 对于视频缩略图，服务器可能返回 SVG 占位图，此时不缓存
                val contentType = response.header("Content-Type", "")
                if (type == "video" && contentType?.contains("svg") == true) {
                    Log.d(TAG, "收到 SVG 占位图，不缓存: $path")
                    return@async null
                }

                val cacheFile = File(cacheDir, cacheKey)
                FileOutputStream(cacheFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                trimCacheIfNeeded()
                Log.d(TAG, "缩略图缓存成功: $cacheKey, 大小=${cacheFile.length()}")
                cacheFile
            } catch (e: Exception) {
                Log.e(TAG, "下载缩略图异常: $path", e)
                null
            } finally {
                pendingDownloads.remove(cacheKey)
            }
        }

        pendingDownloads[cacheKey] = deferred
        return deferred.await()
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}