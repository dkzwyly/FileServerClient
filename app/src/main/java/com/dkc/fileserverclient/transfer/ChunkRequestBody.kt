package com.dkc.fileserverclient.transfer

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.FileInputStream
import java.io.IOException

class ChunkRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val offset: Long,
    private val length: Long,
    private val contentType: MediaType?,
    /** 本分片已写字节数（0..length），用于外部聚合进度；可空 */
    private val onBytesWritten: ((Long) -> Unit)? = null
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("无法打开 URI: $uri")

        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                fis.channel.position(offset)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                var written = 0L
                var lastReport = 0L
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                    written += read
                    if (written - lastReport >= 64 * 1024) {
                        onBytesWritten?.invoke(written)
                        lastReport = written
                    }
                }
                if (remaining > 0) {
                    throw IOException("分片读取不完整: 还差 $remaining 字节")
                }
                onBytesWritten?.invoke(length)
            }
        }
    }
}