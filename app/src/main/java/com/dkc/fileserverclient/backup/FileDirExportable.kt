package com.dkc.fileserverclient.backup

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 通用 filesDir 子目录导出器（流式版本）。
 *
 * 目录里每个文件作为独立 entry 写入外层 zip，entry 名为：
 *   <exportKey>/<相对路径>
 *
 * 因此不会把所有文件读进内存，20GB 缩略图也能导出。
 */
class FileDirExportable(private val dirRelativePath: String) : Exportable {

    override val exportKey: String =
        "files_${dirRelativePath.replace('/', '_')}"

    override fun canHandle(entryName: String): Boolean =
        entryName.startsWith("$exportKey/")

    override suspend fun exportToZip(context: Context, zip: ZipOutputStream) {
        val dir = File(context.filesDir, dirRelativePath)
        if (!dir.exists() || !dir.isDirectory) return

        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                try {
                    val rel = file.relativeTo(dir).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry("$exportKey/$rel"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                } catch (e: Exception) {
                    // 单个文件失败不影响整体；补一个 .error entry 便于排查
                    try {
                        zip.putNextEntry(ZipEntry("$exportKey/${file.name}.error"))
                        zip.write((e.message ?: "unknown").toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    } catch (_: Exception) {}
                }
            }
    }

    override suspend fun restoreFromZip(
        context: Context,
        entryName: String,
        data: ByteArray
    ): Boolean {
        return try {
            val rel = entryName.removePrefix("$exportKey/")
            if (rel.isEmpty()) return false

            val dir = File(context.filesDir, dirRelativePath)
            val target = File(dir, rel)
            target.parentFile?.mkdirs()
            target.writeBytes(data)
            true
        } catch (e: Exception) {
            false
        }
    }
}