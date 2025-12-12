// AudioMetadataParser.kt - 这是一个将来用于解析音频元数据的类
package com.dkc.fileserverclient.utils

import android.media.MediaMetadataRetriever
import java.io.File

object AudioMetadataParser {

    data class AudioMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val year: String? = null,
        val duration: Long = 0
    )

    /**
     * 从本地文件解析音频元数据
     */
    fun parseLocalFile(filePath: String): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)

            AudioMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            )
        } catch (e: Exception) {
            AudioMetadata()
        } finally {
            retriever.release()
        }
    }

    /**
     * 从网络URL解析音频元数据（需要下载部分文件）
     * 注意：这个方法需要网络权限，且可能需要异步执行
     */
    fun parseRemoteFile(url: String): AudioMetadata {
        // 这里需要实现从网络流中读取元数据的逻辑
        // 可能涉及到下载文件头部信息
        return AudioMetadata()
    }

    /**
     * 格式化艺术家和专辑信息
     */
    fun formatArtistAlbum(artist: String?, album: String?): String {
        return when {
            artist != null && album != null -> "$artist · $album"
            artist != null -> artist
            album != null -> album
            else -> "未知艺术家 · 未知专辑"
        }
    }
}