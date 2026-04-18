package com.dkc.fileserverclient

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌曲元数据管理器，负责缓存和获取/保存歌曲元数据及封面
 */
class SongMetadataManager(
    private val context: Context,
    private val fileServerService: FileServerService
) {
    // 缓存：path -> SongMetadata
    private val metadataCache = mutableMapOf<String, SongMetadata>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    /**
     * 获取元数据（优先缓存，否则从服务器拉取）
     */
    suspend fun getMetadata(serverUrl: String, songPath: String): SongMetadata? {
        // 检查缓存
        metadataCache[songPath]?.let { return it }

        // 从服务器获取
        val metadata = fileServerService.getSongMetadata(serverUrl, songPath)
        if (metadata != null) {
            metadataCache[songPath] = metadata
        }
        return metadata
    }

    /**
     * 保存用户自定义元数据
     */
    suspend fun saveMetadata(
        serverUrl: String,
        songPath: String,
        title: String?,
        artist: String?,
        album: String?
    ): Boolean {
        val success = fileServerService.saveSongMetadataMapping(serverUrl, songPath, title, artist, album)
        if (success) {
            // 更新缓存
            val existing = metadataCache[songPath] ?: SongMetadata()
            metadataCache[songPath] = existing.copy(
                title = title ?: existing.title,
                artist = artist ?: existing.artist,
                album = album ?: existing.album
            )
        }
        return success
    }

    /**
     * 删除自定义元数据（恢复为文件内嵌元数据）
     */
    suspend fun deleteMetadata(serverUrl: String, songPath: String): Boolean {
        val success = fileServerService.deleteSongMetadataMapping(serverUrl, songPath)
        if (success) {
            metadataCache.remove(songPath)
        }
        return success
    }

    /**
     * 获取封面 URL（如果没有自定义封面，返回 null）
     * 注意：封面需要单独请求，这里直接返回一个可访问的 URL 格式
     */
    fun getCoverUrl(serverUrl: String, songPath: String): String? {
        val encodedPath = java.net.URLEncoder.encode(songPath, "UTF-8")
        return "${serverUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        metadataCache.clear()
    }
}