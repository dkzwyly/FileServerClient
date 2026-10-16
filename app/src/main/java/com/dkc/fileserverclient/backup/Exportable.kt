package com.dkc.fileserverclient.backup

import android.content.Context
import java.util.zip.ZipOutputStream

/**
 * 任何需要参与备份/恢复的数据源实现此接口。
 *
 * 接口改成流式：provider 直接把内容写进外层 ZipOutputStream，
 * 避免把全部数据先堆到内存里（缩略图缓存可能有几十 GB）。
 */
interface Exportable {

    /** 唯一标识，同时作为 zip entry 名的前缀，例如 `prefs_audio_cache`、`files_thumbnails` */
    val exportKey: String

    /** 导入时判断这个 zip entry 是否归本 provider 处理 */
    fun canHandle(entryName: String): Boolean

    /**
     * 流式导出：直接往 zip 里 putNextEntry / write / closeEntry。
     * 可以写多个 entry，用 exportKey 作为前缀。
     * BackupManager 保证只在单线程 Dispatchers.IO 上调用。
     */
    suspend fun exportToZip(context: Context, zip: ZipOutputStream)

    /**
     * 恢复一个 entry。
     * BackupManager 会把当前 entry 读成 byte array 传进来，
     * 单个文件通常不大（缩略图几十 KB 到几 MB），不会 OOM。
     */
    suspend fun restoreFromZip(context: Context, entryName: String, data: ByteArray): Boolean
}