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
            // 本地文件解码：磁盘缓存由我们自己的 cacheDir 管理（DISABLED），
            // 但内存缓存一定要 ENABLED，快速滑回时能直接命中、不重复解码
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

        // ★ 关键：把本次请求的 path 打到 tag 上，所有异步回调先比对
        val requestToken = imagePath
        imageView.tag = requestToken

        val cacheKey = generateCacheKey(serverUrl, imagePath, "image")
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null) {
            loaderScope.launch {
                decodeAndSet(imageView, cachedFile, requestToken, onResult)
            }
        } else {
            // ★ 只在当前无内容时才显示占位，避免复用 ViewHolder 时
            //   出现「旧图 → 占位 → 新图」的两次刷新，视觉上更平滑
            if (imageView.drawable == null) {
                imageView.setImageResource(placeholderResId)
            }
            loaderScope.launch {
                val file = downloadThumbnail(serverUrl, imagePath, "image", cacheKey, "thumbnail")
                if (file == null) {
                    withContext(Dispatchers.Main) {
                        // ★ tag 不匹配说明 ViewHolder 已复用到别的 item，丢弃
                        if (imageView.tag == requestToken) {
                            imageView.setImageResource(errorResId)
                            onResult?.invoke(false)
                        }
                    }
                    return@launch
                }
                decodeAndSet(imageView, file, requestToken, onResult)
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

        val requestToken = videoPath
        imageView.tag = requestToken

        val cacheKey = generateCacheKey(serverUrl, videoPath, "video", width, height)
        val cachedFile = getCachedFile(cacheKey)

        if (cachedFile != null) {
            loaderScope.launch {
                decodeAndSet(imageView, cachedFile, requestToken, onResult)
            }
        } else {
            if (imageView.drawable == null) {
                imageView.setImageResource(placeholderResId)
            }
            loaderScope.launch {
                val file = downloadThumbnail(
                    serverUrl, videoPath, "video", cacheKey,
                    "video-thumbnail", width, height
                )
                if (file == null) {
                    withContext(Dispatchers.Main) {
                        if (imageView.tag == requestToken) {
                            imageView.setImageResource(errorResId)
                            onResult?.invoke(false)
                        }
                    }
                    return@launch
                }
                decodeAndSet(imageView, file, requestToken, onResult)
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

    fun deleteByPath(serverUrl: String, path: String) {
        if (!isInitialized) return

        val prefix = md5("${serverUrl.trimEnd('/')}|$path") + "_"
        val files = cacheDir.listFiles() ?: return
        var count = 0
        files.forEach { file ->
            if (file.name.startsWith(prefix)) {
                if (file.delete()) count++
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
    // 核心：解码 + 切 UI（★ 带 tag 校验）
    // ================================================================

    private suspend fun decodeAndSet(
        imageView: ImageView,
        file: File,
        requestToken: String,
        onResult: ((Boolean) -> Unit)?
    ) {
        val request = ImageRequest.Builder(imageView.context)
            .data(file)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()

        val result = try {
            coilLoader.execute(request)
        } catch (e: Exception) {
            Log.e(TAG, "decodeAndSet: execute 异常 token=[$requestToken]", e)
            null
        }

        withContext(Dispatchers.Main) {
            // ★★ 复用竞态拦截：tag 变了说明 ViewHolder 已绑定别的 item
            if (imageView.tag != requestToken) {
                Log.d(TAG, "decodeAndSet: 丢弃过期结果 token=[$requestToken] " +
                        "当前 tag=[${imageView.tag}]")
                return@withContext
            }

            when (result) {
                is SuccessResult -> {
                    imageView.setImageDrawable(result.drawable)
                    onResult?.invoke(true)
                }
                is ErrorResult -> {
                    Log.w(TAG, "decodeAndSet: ERROR token=[$requestToken] ${result.throwable}")
                    onResult?.invoke(false)
                }
                else -> {
                    onResult?.invoke(false)
                }
            }
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
        val pathHash = md5("${serverUrl.trimEnd('/')}|$filePath")
        val ext = if (type == "video") ".jpg" else ".thumb"
        return "${pathHash}_${type}_${width}x${height}$ext"
    }

    private fun getCachedFile(cacheKey: String): File? {
        val primary = File(cacheDir, cacheKey)
        if (primary.exists() && primary.length() > 0) return primary

        if (cacheKey.endsWith(".jpg")) {
            val svg = File(cacheDir, cacheKey.dropLast(4) + ".svg")
            if (svg.exists() && svg.length() > 0) return svg
        }
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

                val response = httpClient.newCall(
                    Request.Builder().url(url).get().build()
                ).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "downloadThumbnail: 失败 HTTP ${response.code} path=[$path]")
                    return@async null
                }

                val body = response.body ?: return@async null
                val inputStream = body.byteStream()

                val contentType = response.header("Content-Type", "") ?: ""
                if (!contentType.startsWith("image/")) {
                    Log.w(TAG, "downloadThumbnail: Content-Type 非图片 [$contentType]")
                    inputStream.close()
                    return@async null
                }

                val isSvg = contentType.contains("svg", ignoreCase = true)
                val cacheFile = if (type == "video" && isSvg) {
                    File(cacheDir, cacheKey.dropLast(4) + ".svg")
                } else {
                    File(cacheDir, cacheKey)
                }

                // 原子写入：先写 .tmp 再 rename，防止半截文件被复用
                val tmpFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                FileOutputStream(tmpFile).use { inputStream.copyTo(it) }
                inputStream.close()
                if (!tmpFile.renameTo(cacheFile)) {
                    // rename 失败时退化为直接写
                    tmpFile.copyTo(cacheFile, overwrite = true)
                    tmpFile.delete()
                }
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