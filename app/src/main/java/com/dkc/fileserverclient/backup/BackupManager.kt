package com.dkc.fileserverclient.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    private const val TAG = "BackupManager"
    private const val MANIFEST_ENTRY = "_manifest.json"

    /** 已注册的导出器，按注册顺序保留 */
    private val providers = LinkedHashMap<String, Exportable>()

    fun register(provider: Exportable) {
        providers[provider.exportKey] = provider
    }

    fun unregister(key: String) {
        providers.remove(key)
    }

    fun registeredKeys(): List<String> = providers.keys.toList()

    // ==========================================================
    // 导出
    // ==========================================================

    /**
     * 一键导出到用户选择的目标 Uri。
     * 全程流式写入，不会把数据一次性读进内存。
     */
    suspend fun exportAll(context: Context, targetUri: Uri): Result =
        withContext(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(targetUri)
                    ?: return@withContext Result(false, "无法打开输出流")

                ZipOutputStream(BufferedOutputStream(outputStream, 64 * 1024)).use { zip ->
                    // 1. 先写 manifest
                    val manifest = JSONObject().apply {
                        put("version", 2)
                        put("exportTime", System.currentTimeMillis())
                        put("providers", JSONArray(providers.keys.toList()))
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // 2. 每个 provider 直接往 zip 里流式写
                    for ((key, provider) in providers) {
                        try {
                            Log.d(TAG, "开始导出: $key")
                            provider.exportToZip(context, zip)
                            Log.d(TAG, "导出完成: $key")
                        } catch (e: Exception) {
                            Log.e(TAG, "导出失败: $key", e)
                            try {
                                zip.putNextEntry(ZipEntry("$key.error"))
                                zip.write((e.message ?: "unknown").toByteArray(Charsets.UTF_8))
                                zip.closeEntry()
                            } catch (_: Exception) {}
                        }
                    }
                }
                Result(true, "导出成功，共 ${providers.size} 项")
            } catch (e: Exception) {
                Log.e(TAG, "导出异常", e)
                Result(false, "导出失败: ${e.message}")
            }
        }

    // ==========================================================
    // 恢复
    // ==========================================================

    /**
     * 从 zip 恢复。逐 entry 读出，交给对应 provider。
     */
    suspend fun restoreAll(context: Context, sourceUri: Uri): Result =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext Result(false, "无法打开输入流")

                var successCount = 0
                var failCount = 0

                ZipInputStream(BufferedInputStream(inputStream, 64 * 1024)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        try {
                            val name = entry.name
                            if (!entry.isDirectory && name != MANIFEST_ENTRY) {
                                val provider = providers.values.firstOrNull { it.canHandle(name) }
                                if (provider != null) {
                                    val data = readCurrentEntry(zip)
                                    val ok = try {
                                        provider.restoreFromZip(context, name, data)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "恢复失败: $name", e)
                                        false
                                    }
                                    if (ok) successCount++ else failCount++
                                }
                            }
                        } finally {
                            try { zip.closeEntry() } catch (_: Exception) {}
                        }
                        entry = zip.nextEntry
                    }
                }

                Result(failCount == 0, "恢复完成: 成功 $successCount, 失败 $failCount")
            } catch (e: Exception) {
                Log.e(TAG, "恢复异常", e)
                Result(false, "恢复失败: ${e.message}")
            }
        }

    /** 读当前 entry 的全部字节（单个文件级别，不会 OOM） */
    private fun readCurrentEntry(zip: ZipInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var n: Int
        while (true) {
            n = zip.read(buffer)
            if (n <= 0) break
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }

    data class Result(val success: Boolean, val message: String)
}