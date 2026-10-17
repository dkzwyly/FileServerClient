package com.dkc.fileserverclient.transfer

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException

/**
 * 基于本地 File 的流式上传体。
 * 每次从 inputStream 读 8KB 写 socket，内存占用恒定。
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (written: Long, total: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var uploaded = 0L
        var lastReported = 0L
        val reportInterval = (total / 100).coerceAtLeast(64 * 1024)

        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var read = input.read(buffer)
            while (read != -1) {
                sink.write(buffer, 0, read)
                uploaded += read

                if (uploaded - lastReported >= reportInterval || uploaded == total) {
                    onProgress(uploaded, total)
                    lastReported = uploaded
                }
                read = input.read(buffer)
            }
        }
        onProgress(total, total)
    }
}

/**
 * 基于 content Uri 的流式上传体（★ 新增）。
 * 直接读 ContentResolver.openInputStream，无需先 copy 到 cacheDir。
 *
 * @param totalBytes 从 MediaStore/ContentResolver 查到的大小；未知传 -1（OkHttp 会走 chunked）
 */
class UriProgressRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val totalBytes: Long,
    private val contentType: MediaType?,
    private val onProgress: (written: Long, total: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = if (totalBytes > 0) totalBytes else -1L

    override fun writeTo(sink: BufferedSink) {
        val known = totalBytes > 0
        val total = if (known) totalBytes else 0L
        var uploaded = 0L
        var lastReported = 0L
        val reportInterval = if (known)
            (total / 100).coerceAtLeast(64 * 1024)
        else
            256 * 1024

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开 URI: $uri")

        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } > 0) {
                sink.write(buffer, 0, read)
                uploaded += read
                if (uploaded - lastReported >= reportInterval ||
                    (known && uploaded == total)
                ) {
                    onProgress(uploaded, if (known) total else uploaded)
                    lastReported = uploaded
                }
            }
        }
        onProgress(uploaded, if (known) total else uploaded)
    }
}