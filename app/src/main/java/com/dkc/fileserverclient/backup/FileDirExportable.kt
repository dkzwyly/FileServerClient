package com.dkc.fileserverclient.backup

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 通用 filesDir 子目录导出器。
 * 内部会再打一层 zip，目录内所有文件按相对路径保存。
 */
class FileDirExportable(private val dirRelativePath: String) : Exportable {

    override val exportKey: String = "files_${dirRelativePath.replace('/', '_')}"

    override suspend fun export(context: Context): ByteArray {
        val dir = File(context.filesDir, dirRelativePath)
        if (!dir.exists() || !dir.isDirectory) return ByteArray(0)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.relativeTo(dir).path.replace('\\', '/')
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return baos.toByteArray()
    }

    override suspend fun restore(context: Context, data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        return try {
            val dir = File(context.filesDir, dirRelativePath)
            dir.mkdirs()
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val target = File(dir, entry.name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}