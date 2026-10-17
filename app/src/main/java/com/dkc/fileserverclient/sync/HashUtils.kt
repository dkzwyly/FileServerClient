package com.dkc.fileserverclient.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object HashUtils {

    private const val TAG = "HashUtils"
    private const val BUFFER_SIZE = 1024 * 1024

    /**
     * 计算 content:// URI 的 SHA-256。
     */
    suspend fun sha256(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "计算哈希失败: $uri", e)
            null
        }
    }

    /**
     * 计算本地 File 的 SHA-256。
     */
    suspend fun sha256(file: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "计算哈希失败: ${file.absolutePath}", e)
            null
        }
    }
}