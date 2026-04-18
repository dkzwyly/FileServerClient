package com.dkc.fileserverclient

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 歌曲元数据（对应服务器返回的 JSON）
 */
@Parcelize
data class SongMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val hasCover: Boolean = false,
    val customCoverPath: String? = null
) : Parcelable