package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.load
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object ThumbnailLoader {
    private const val TAG = "ThumbnailLoader"

    private const val MAX_CACHE_SIZE_MB = 20L * 1024L
    private const val CACHE_DIR_NAME = "thumbnails"

    private lateinit var cacheDir: File
    private lateinit var httpClient: OkHttpClient
    private lateinit var coilLoader: ImageLoader
    private var isInitialized = false

    private val pendingDownloads = ConcurrentHashMap<String, Deferred<File?>>()
    private val loaderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        if (isInitialized) return

        cacheDir = File(context.filesDir, CACHE_DIR_NAME).apply { mkdirs() }

        httpClient = UnsafeHttpClient.createUnsafeOkHttpClient().newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        coilLoader = ImageLoader.Builder(context.applicationContext)
            .okHttpClient(httpClient)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .respectCacheHeaders(false)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()

        isInitialized = true
        Log.d(TAG, "初始化完成，缓存目录: ${cacheDir.absolutePath}")

        loaderScope.launch {
            delay(2000)
            trimCacheIfNeeded()
        }
    }

    // ================================================================
    // 公共 API
    // ================================================================

    fun loadImageThumbnail(
        imageView: ImageView,
        imageItem: FileSystemItem,
        serverUrl: String,
        placeholderResId: Int = R.drawable.ic_image_placeholder,
        errorResId: Int = R.drawable.ic_image_placeholder
    ) {
        if (!isInitialized) init(imageView.context)

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
                crossfade(false)
                memoryCacheKey(cachedFile.absolutePath)
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
                            crossfade(false)
                            memoryCacheKey(file.absolutePath)
                        }
                    } else {
                        imageView.setImageResource(errorResId)
                    }
                }
            }
        }
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
                crossfade(false)
                memoryCacheKey(cachedFile.absolutePath)
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
                            crossfade(false)
                            memoryCacheKey(file.absolutePath)
                        }
                    } else {
                        imageView.setImageResource(errorResId)
                    }
                }
            }
        }
    }

    /**
     * 返回视频缩略图 Bitmap。
     * - 有 jpg → Bitmap
     * - 有 svg → null（SVG 不能用 BitmapFactory 解码，但保留缓存）
     * - 都没 → 下载一次，再按上面判断
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
        if (cachedFile != null) {
            return@withContext if (cachedFile.extension.equals("svg", ignoreCase = true)) {
                null
            } else {
                BitmapFactory.decodeFile(cachedFile.absolutePath)
            }
        }

        val file = downloadThumbnail(
            serverUrl = serverUrl,
            path = videoPath,
            type = "video",
            cacheKey = cacheKey,
            urlSuffix = "video-thumbnail",
            width = width,
            height = height
        ) ?: return@withContext null

        if (file.extension.equals("svg", ignoreCase = true)) {
            null
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }

    /**
     * 清空所有 SVG 占位图缓存（下拉刷新时调用）。
     * 保留 .jpg / .thumb，因为真实缩略图不会变。
     * 下次展示会重新请求，服务端 ffmpeg 完成的话就能拿到真实缩略图。
     */
    fun invalidateSvgCache() {
        if (!isInitialized) return
        var count = 0
        cacheDir.listFiles()?.forEach { file ->
            if (file.extension.equals("svg", ignoreCase = true)) {
                file.delete()
                count++
            }
        }
        coilLoader.memoryCache?.clear()
        Log.d(TAG, "已清空 $count 个 SVG 缓存")
    }

    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        coilLoader.memoryCache?.clear()
    }

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
    }

    // ================================================================
    // 私有
    // ================================================================

    private fun generateCacheKey(
        serverUrl: String,
        filePath: String,
        type: String,
        width: Int = 0,
        height: Int = 0
    ): String {
        val normalizedServer = serverUrl.trimEnd('/')
        val parts = "$normalizedServer|$type|$filePath|${width}x$height"
        return md5(parts) + (if (type == "video") ".jpg" else ".thumb")
    }

    /**
     * 有 jpg 返回 jpg，没 jpg 返回 svg，都没有返回 null。
     * 不再做 TTL 判断。
     */
    private fun getCachedFile(cacheKey: String): File? {
        val jpg = File(cacheDir, cacheKey)
        if (jpg.exists() && jpg.length() > 0) return jpg

        val svg = File(cacheDir, cacheKey.replace(".jpg", ".svg"))
        if (svg.exists() && svg.length() > 0) return svg

        return null
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
                if (width > 0 && height > 0) url += "?width=$width&height=$height"

                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "下载缩略图失败: HTTP ${response.code}, path=$path")
                    return@async null
                }

                val body = response.body ?: return@async null
                val inputStream = body.byteStream()

                val contentType = response.header("Content-Type", "") ?: ""
                val isSvg = contentType.contains("svg", ignoreCase = true)

                val cacheFile = if (type == "video" && isSvg) {
                    File(cacheDir, cacheKey.replace(".jpg", ".svg"))
                } else {
                    File(cacheDir, cacheKey)
                }

                FileOutputStream(cacheFile).use { output -> inputStream.copyTo(output) }
                inputStream.close()

                Log.d(TAG, "缩略图缓存成功: ${cacheFile.name}, type=$type, isSvg=$isSvg")
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