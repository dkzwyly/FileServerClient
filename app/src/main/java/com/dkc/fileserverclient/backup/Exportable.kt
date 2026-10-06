package com.dkc.fileserverclient.backup

import android.content.Context

/**
 * 任何需要参与备份/恢复的数据源实现此接口。
 * 每个实现返回一段字节，BackupManager 会把它们统一打包成一个 zip。
 */
interface Exportable {

    /** 唯一标识，会作为 zip 里的文件名。建议 `类别_名称`，例如 `prefs_audio_cache` */
    val exportKey: String

    /** 导出数据为字节 */
    suspend fun export(context: Context): ByteArray

    /** 从字节恢复数据，返回是否成功 */
    suspend fun restore(context: Context, data: ByteArray): Boolean
}