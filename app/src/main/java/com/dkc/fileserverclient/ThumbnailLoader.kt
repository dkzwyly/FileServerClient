package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
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

    // ★ 由 200MB 提升到 20GB
    private const val MAX_CACHE_SIZE_MB = 20L * 1024L   // 20480MB = 20GB

    private const val CACHE_DIR_NAME = "thumbnails"

    private lateinit var cacheDir: File
    private lateinit var httpClient: OkHttpClient
    private lateinit var coilLoader: ImageLoader
    private var isInitialized = false

    private val pendingDownloads = ConcurrentHashMap<String, Deferred<File?>>()
    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 初始化，必须在使用前调用一次 */
    fun init(context: Context) {
        if (isInitialized) return

        // ★ 用 filesDir 而不是 cacheDir：cacheDir 会被系统在存储紧张时清空
        //    filesDir 是 App 私有持久目录，重启和系统清理都不会丢
        val baseDir = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }
        cacheDir = baseDir

        httpClient = UnsafeHttpClient.createUnsafeOkHttpClient().newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        coilLoader = ImageLoader.Builder(context.applicationContext)
            .okHttpClient(httpClient)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED) // 自己管磁盘缓存
            .respectCacheHeaders(false)
            .build()

        isInitialized = true
        Log.d(TAG, "初始化完成，缓存目录: ${cacheDir.absolutePath}")

        loaderScope.launch {
            delay(2000)
            trimCacheIfNeeded()
        }
    }

    // ======================= 公共 API =======================

    fun loadImageThumbnail(
        imageView: ImageView,
        imageItem: FileSystemItem,
        serverUrl: String,
        placeholderResId: Int = R.drawable.ic_image_placeholder,
        errorResId: Int = R.drawable.ic_image_placeholder
    ) {
        if (!isInitialized) init(imageView.context)

        // ★ 视频和图片走不同的端点/缓存类型
        if (imageItem.isVideo) {
            loadVideoThumbnail(
                imageView = imageView,
                serverUrl = serverUrl,
                videoPath = imageItem.path,
                placeholderResId = placeholderResId,
                errorResId = errorResId
            )
            return
        }

        val cacheKey = generateCacheKey(serverUrl, imageItem.path, "image")
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null) {
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
                    path = imageItem.path,
                    type = "image",
                    cacheKey = cacheKey,
                    urlSuffix = "thumbnail"
                )
                withContext(Dispatchers.Main) {
                    if (file != null) {
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

    suspend fun loadVideoThumbnailBitmap(
        serverUrl: String,
        videoPath: String,
        width: Int = 320,
        height: Int = 180
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized) return@withContext null

        val cacheKey = generateCacheKey(serverUrl, videoPath, "video", width, height)
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null) {
            return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)
        }

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

        if (cachedFile != null) {
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
                    if (file != null) {
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

    fun clearCache(serverUrl: String, filePath: String, type: String = "image", width: Int = 0, height: Int = 0) {
        val cacheKey = generateCacheKey(serverUrl, filePath, type, width, height)
        getCachedFile(cacheKey)?.delete()
    }

    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "所有缩略图缓存已清除")
    }

    /**
     * 缓存剪枝：超过 20GB 才删最旧的文件。
     * 20GB 对缩略图来说基本永远不会触发，所以正常情况下不会再因为容量被淘汰。
     */
    fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024
        if (totalSize <= maxSize) {
            Log.d(TAG, "缓存大小 ${totalSize / (1024 * 1024)} MB，未超限（上限 $MAX_CACHE_SIZE_MB MB）")
            return
        }

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
        // ★ 归一化 serverUrl：去掉尾斜杠，避免同一台服务器因尾斜杠差异造成 cacheKey 变化
        val normalizedServer = serverUrl.trimEnd('/')
        val parts = "$normalizedServer|$type|$filePath|${width}x$height"
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

                // ★ 服务端返回 SVG 占位图（视频缩略图尚未生成）：
                //   仍然缓存下来，避免每次滚动都请求；下次滚动会命中本地 SVG。
                //   但写盘时需要区分扩展名，避免把 SVG 内容写成 .jpg 造成解码失败。
                val contentType = response.header("Content-Type", "") ?: ""
                val isSvg = contentType.contains("svg", ignoreCase = true)

                val cacheFile = if (type == "video" && isSvg) {
                    File(cacheDir, cacheKey.replace(".jpg", ".svg"))
                } else {
                    File(cacheDir, cacheKey)
                }

                FileOutputStream(cacheFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                Log.d(TAG, "缩略图缓存成功: ${cacheFile.name}, 大小=${cacheFile.length()}, type=$type, isSvg=$isSvg")
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