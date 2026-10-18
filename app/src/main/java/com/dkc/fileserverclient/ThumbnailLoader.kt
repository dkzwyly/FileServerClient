package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object ThumbnailLoader {
    private const val TAG = "ThumbnailLoader"

    // 20GB 上限（沿用原有设计；变量名历史遗留，实际单位是 GB 级别）
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
            .components { add(SvgDecoder.Factory()) }
            .build()

        isInitialized = true
        Log.d(TAG, "init: 缓存目录=${cacheDir.absolutePath}")

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
        errorResId: Int = R.drawable.ic_image_placeholder,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (!isInitialized) init(imageView.context)
        Log.d(TAG, "loadImageThumbnail: path=${imageItem.path} isVideo=${imageItem.isVideo}")

        if (imageItem.isVideo) {
            loadVideoThumbnail(
                imageView, serverUrl, imageItem.path,
                placeholderResId = placeholderResId,
                errorResId = errorResId,
                onResult = onResult
            )
            return
        }
        loadImageThumbnailByPath(
            imageView, serverUrl, imageItem.path,
            placeholderResId, errorResId, onResult
        )
    }

    fun loadImageThumbnailByPath(
        imageView: ImageView,
        serverUrl: String,
        imagePath: String,
        placeholderResId: Int = R.drawable.ic_image_placeholder,
        errorResId: Int = R.drawable.ic_image_placeholder,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (!isInitialized) init(imageView.context)
        Log.d(TAG, "loadImageThumbnailByPath: serverUrl=[$serverUrl] imagePath=[$imagePath]")

        val cacheKey = generateCacheKey(serverUrl, imagePath, "image")
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null) {
            Log.d(TAG, "loadImageThumbnailByPath: 缓存命中 ${cachedFile.name} size=${cachedFile.length()}")
            loaderScope.launch { decodeAndSet(imageView, cachedFile, imagePath, onResult) }
        } else {
            Log.d(TAG, "loadImageThumbnailByPath: 缓存未命中，开始下载 path=[$imagePath]")
            imageView.setImageResource(placeholderResId)
            loaderScope.launch {
                val file = downloadThumbnail(serverUrl, imagePath, "image", cacheKey, "thumbnail")
                if (file == null) {
                    Log.w(TAG, "loadImageThumbnailByPath: 下载失败 path=[$imagePath]")
                    withContext(Dispatchers.Main) {
                        imageView.setImageResource(errorResId)
                        onResult?.invoke(false)
                    }
                    return@launch
                }
                decodeAndSet(imageView, file, imagePath, onResult)
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
        errorResId: Int = R.drawable.ic_video_placeholder,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (!isInitialized) init(imageView.context)
        Log.d(TAG, "loadVideoThumbnail: videoPath=[$videoPath] ${width}x$height")

        val cacheKey = generateCacheKey(serverUrl, videoPath, "video", width, height)
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null) {
            Log.d(TAG, "loadVideoThumbnail: 缓存命中 ${cachedFile.name} size=${cachedFile.length()}")
            loaderScope.launch { decodeAndSet(imageView, cachedFile, videoPath, onResult) }
        } else {
            Log.d(TAG, "loadVideoThumbnail: 缓存未命中，开始下载 path=[$videoPath]")
            imageView.setImageResource(placeholderResId)
            loaderScope.launch {
                val file = downloadThumbnail(
                    serverUrl, videoPath, "video", cacheKey,
                    "video-thumbnail", width, height
                )
                if (file == null) {
                    Log.w(TAG, "loadVideoThumbnail: 下载失败 path=[$videoPath]")
                    withContext(Dispatchers.Main) {
                        imageView.setImageResource(errorResId)
                        onResult?.invoke(false)
                    }
                    return@launch
                }
                decodeAndSet(imageView, file, videoPath, onResult)
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
            return@withContext if (cachedFile.extension.equals("svg", ignoreCase = true)) null
            else BitmapFactory.decodeFile(cachedFile.absolutePath)
        }

        val file = downloadThumbnail(
            serverUrl, videoPath, "video", cacheKey,
            "video-thumbnail", width, height
        ) ?: return@withContext null

        if (file.extension.equals("svg", ignoreCase = true)) null
        else BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * 精确删除某个文件对应的全部本地缩略图缓存。
     *
     * key 格式：<md5(server|path)>_<type>_<WxH>.<ext>
     * 前缀匹配 md5(server|path)_ 即可覆盖该文件的所有尺寸、图片/视频类型。
     */
    fun deleteByPath(serverUrl: String, path: String) {
        if (!isInitialized) return

        val prefix = md5("${serverUrl.trimEnd('/')}|$path") + "_"
        val files = cacheDir.listFiles() ?: return
        var count = 0
        files.forEach { file ->
            if (file.name.startsWith(prefix)) {
                if (file.delete()) {
                    count++
                    Log.d(TAG, "deleteByPath: 删除 ${file.name}")
                }
            }
        }
        Log.d(TAG, "deleteByPath: path=[$path] 共删除 $count 个缓存文件")
    }

    fun invalidateSvgCache() {
        if (!isInitialized) return
        var count = 0
        cacheDir.listFiles()?.forEach {
            if (it.extension.equals("svg", ignoreCase = true)) {
                it.delete(); count++
            }
        }
        coilLoader.memoryCache?.clear()
        Log.d(TAG, "invalidateSvgCache: 清空 $count 个 SVG")
    }

    fun clearAllCache() {
        if (!isInitialized) return
        var count = 0
        cacheDir.listFiles()?.forEach {
            if (it.delete()) count++
        }
        coilLoader.memoryCache?.clear()
        Log.d(TAG, "clearAllCache: 共删除 $count 个文件")
    }

    fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        val maxSize = MAX_CACHE_SIZE_MB * 1024 * 1024
        if (totalSize <= maxSize) return

        var deleted = 0
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (totalSize > maxSize) {
                totalSize -= f.length()
                if (f.delete()) deleted++
            }
        }
        Log.d(TAG, "trimCacheIfNeeded: 超过上限，删除 $deleted 个文件")
    }

    fun debugDumpCache() {
        if (!isInitialized) return
        val files = cacheDir.listFiles()
        Log.d(TAG, "debugDumpCache: count=${files?.size ?: 0}")
    }

    // ================================================================
    // 核心：suspend execute 解码 + 切 UI
    // ================================================================

    private suspend fun decodeAndSet(
        imageView: ImageView,
        file: File,
        logPath: String,
        onResult: ((Boolean) -> Unit)?
    ) {
        val request = ImageRequest.Builder(imageView.context)
            .data(file)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
            .build()

        val result = try {
            coilLoader.execute(request)
        } catch (e: Exception) {
            Log.e(TAG, "decodeAndSet: execute 异常 path=[$logPath]", e)
            null
        }

        withContext(Dispatchers.Main) {
            when (result) {
                is SuccessResult -> {
                    imageView.setImageDrawable(result.drawable)
                    Log.d(TAG, "decodeAndSet: SUCCESS path=[$logPath]")
                    onResult?.invoke(true)
                }
                is ErrorResult -> {
                    Log.w(TAG, "decodeAndSet: ERROR path=[$logPath] throwable=${result.throwable}")
                    onResult?.invoke(false)
                }
                else -> {
                    Log.w(TAG, "decodeAndSet: NULL path=[$logPath]")
                    onResult?.invoke(false)
                }
            }
        }
    }

    // ================================================================
    // 私有
    // ================================================================

    /**
     * 新 key 格式：<md5(server|path)>_<type>_<WxH>.<ext>
     * - 前缀 md5(server|path)_ 可用于 deleteByPath 精确清理
     * - 图片：image_0x0.thumb
     * - 视频：video_320x180.jpg / video_320x180.svg
     */
    private fun generateCacheKey(
        serverUrl: String,
        filePath: String,
        type: String,
        width: Int = 0,
        height: Int = 0
    ): String {
        val pathHash = md5("${serverUrl.trimEnd('/')}|$filePath")
        val ext = if (type == "video") ".jpg" else ".thumb"
        val key = "${pathHash}_${type}_${width}x${height}$ext"
        Log.d(TAG, "generateCacheKey: server=[$serverUrl] path=[$filePath] type=$type " +
                "size=${width}x$height -> key=$key")
        return key
    }

    private fun getCachedFile(cacheKey: String): File? {
        val primary = File(cacheDir, cacheKey)
        if (primary.exists() && primary.length() > 0) {
            Log.d(TAG, "getCachedFile: HIT ${primary.name} size=${primary.length()}")
            return primary
        }
        // 视频 svg 备选
        if (cacheKey.endsWith(".jpg")) {
            val svg = File(cacheDir, cacheKey.dropLast(4) + ".svg")
            if (svg.exists() && svg.length() > 0) {
                Log.d(TAG, "getCachedFile: HIT ${svg.name} size=${svg.length()}")
                return svg
            }
        }
        Log.d(TAG, "getCachedFile: MISS key=$cacheKey")
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
        pendingDownloads[cacheKey]?.let {
            Log.d(TAG, "downloadThumbnail: 复用 pending key=$cacheKey")
            return it.await()
        }

        val deferred = loaderScope.async {
            try {
                val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
                var url = "${serverUrl.removeSuffix("/")}/api/fileserver/$urlSuffix/$encodedPath"
                if (width > 0 && height > 0) url += "?width=$width&height=$height"

                Log.d(TAG, "downloadThumbnail: GET $url")
                val response = httpClient.newCall(
                    Request.Builder().url(url).get().build()
                ).execute()
                Log.d(TAG, "downloadThumbnail: HTTP ${response.code}")

                if (!response.isSuccessful) {
                    Log.w(TAG, "downloadThumbnail: 失败 HTTP ${response.code} path=[$path]")
                    return@async null
                }

                val body = response.body ?: return@async null
                val inputStream = body.byteStream()

                val contentType = response.header("Content-Type", "") ?: ""
                if (!contentType.startsWith("image/")) {
                    Log.w(TAG, "downloadThumbnail: Content-Type 不是图片 [$contentType]，放弃缓存")
                    inputStream.close()
                    return@async null
                }

                val isSvg = contentType.contains("svg", ignoreCase = true)
                val cacheFile = if (type == "video" && isSvg) {
                    File(cacheDir, cacheKey.dropLast(4) + ".svg")
                } else {
                    File(cacheDir, cacheKey)
                }

                FileOutputStream(cacheFile).use { inputStream.copyTo(it) }
                inputStream.close()
                Log.d(TAG, "downloadThumbnail: 写入 ${cacheFile.name} size=${cacheFile.length()}")
                cacheFile
            } catch (e: Exception) {
                Log.e(TAG, "downloadThumbnail: 异常 path=[$path]", e)
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