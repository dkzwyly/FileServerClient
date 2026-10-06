package com.dkc.fileserverclient.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    /** 已注册的导出器，按注册顺序保留 */
    private val providers = LinkedHashMap<String, Exportable>()

    /** 注册一个导出器（重复 key 会覆盖） */
    fun register(provider: Exportable) {
        providers[provider.exportKey] = provider
    }

    fun unregister(key: String) {
        providers.remove(key)
    }

    fun registeredKeys(): List<String> = providers.keys.toList()

    /**
     * 一键导出到用户选择的目标 Uri（建议 .zip）
     */
    suspend fun exportAll(context: Context, targetUri: Uri): Result =
        withContext(Dispatchers.IO) {
            try {
                val outputStream = context.contentResolver.openOutputStream(targetUri)
                    ?: return@withContext Result(false, "无法打开输出流")

                ZipOutputStream(outputStream).use { zip ->
                    // 1. 写入 manifest，便于以后识别版本与内容
                    val manifest = JSONObject().apply {
                        put("version", 1)
                        put("exportTime", System.currentTimeMillis())
                        put("providers", JSONArray(providers.keys.toList()))
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // 2. 逐个导出
                    for ((key, provider) in providers) {
                        try {
                            val bytes = provider.export(context)
                            zip.putNextEntry(ZipEntry(key))
                            zip.write(bytes)
                            zip.closeEntry()
                        } catch (e: Exception) {
                            // 单个 provider 失败不影响整体，写一个 .error 条目
                            zip.putNextEntry(ZipEntry("$key.error"))
                            zip.write((e.message ?: "unknown").toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                }
                Result(true, "导出成功，共 ${providers.size} 项")
            } catch (e: Exception) {
                Result(false, "导出失败: ${e.message}")
            }
        }

    /**
     * 一键从 zip 恢复
     */
    suspend fun restoreAll(context: Context, sourceUri: Uri): Result =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(sourceUri)
                    ?: return@withContext Result(false, "无法打开输入流")

                // 1. 一次性读出所有条目
                val entries = mutableMapOf<String, ByteArray>()
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            entries[entry.name] = readCurrentEntry(zip)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                // 2. 逐个恢复
                var successCount = 0
                var failCount = 0
                for ((key, provider) in providers) {
                    val data = entries[key] ?: continue
                    try {
                        if (provider.restore(context, data)) successCount++ else failCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                }
                Result(failCount == 0, "恢复完成: 成功 $successCount, 失败 $failCount")
            } catch (e: Exception) {
                Result(false, "恢复失败: ${e.message}")
            }
        }

    /** 读取 ZipInputStream 当前 entry 的全部字节 */
    private fun readCurrentEntry(zip: ZipInputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var n: Int
        while (true) {
            n = zip.read(buffer)
            if (n <= 0) break
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }

    data class Result(val success: Boolean, val message: String)

    private const val MANIFEST_ENTRY = "_manifest.json"
}