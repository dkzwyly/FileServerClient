package com.dkc.fileserverclient.transfer

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

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