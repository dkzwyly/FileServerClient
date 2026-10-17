package com.dkc.fileserverclient.sync

import android.net.Uri

/**
 * 本地媒体（图片或视频）。
 * 字段被 ImageSyncActivity 的扫描、哈希、比对、同步流程使用。
 */
data class LocalMedia(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val bucketName: String,
    val isVideo: Boolean,
    val duration: Long = 0L
) {
    /** SHA-256，计算完成后填入 */
    var hash: String? = null

    /** 服务端已有相同 hash */
    var alreadyOnServer: Boolean = false

    /** 秒传成功 */
    var quickUploaded: Boolean = false

    /** 已加入上传队列 */
    var uploadQueued: Boolean = false

    /** 错误信息 */
    var errorMessage: String? = null
}