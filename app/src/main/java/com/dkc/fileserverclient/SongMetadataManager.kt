package com.dkc.fileserverclient

import android.content.Context
import java.io.File
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
     * 获取封面 URL（添加时间戳参数避免缓存）
     * @param addTimestamp 是否添加时间戳，默认 true 用于绕过浏览器/Coil 缓存
     */
    fun getCoverUrl(serverUrl: String, songPath: String, addTimestamp: Boolean = true): String {
        val encodedPath = java.net.URLEncoder.encode(songPath, "UTF-8")
        var url = "${serverUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
        if (addTimestamp) {
            url += "?t=${System.currentTimeMillis()}"
        }
        return url
    }

    /**
     * 上传自定义封面
     * @return 是否成功，成功时会更新缓存中的封面信息
     */
    suspend fun uploadCover(
        serverUrl: String,
        songPath: String,
        coverFile: File
    ): Boolean {
        val coverPath = fileServerService.uploadAlbumCover(serverUrl, songPath, coverFile)
        if (coverPath != null) {
            // 更新缓存
            val existing = metadataCache[songPath] ?: SongMetadata()
            metadataCache[songPath] = existing.copy(
                hasCover = true,
                customCoverPath = coverPath
            )
            return true
        }
        return false
    }

    /**
     * 删除自定义封面
     * @return 是否成功，成功时更新缓存（清除自定义封面标志）
     */
    suspend fun deleteCover(serverUrl: String, songPath: String): Boolean {
        val success = fileServerService.deleteAlbumCover(serverUrl, songPath)
        if (success) {
            val existing = metadataCache[songPath] ?: SongMetadata()
            metadataCache[songPath] = existing.copy(
                hasCover = false,
                customCoverPath = null
            )
            // 删除后服务端会恢复使用内嵌封面，重新获取一次元数据以同步真实状态
            val refreshed = fileServerService.getSongMetadata(serverUrl, songPath)
            if (refreshed != null) {
                metadataCache[songPath] = refreshed
            }
        }
        return success
    }
    suspend fun getBatchMetadata(serverUrl: String, paths: List<String>): Map<String, SongMetadata> {
        return withContext(Dispatchers.IO) {
            val metadataMap = fileServerService.getBatchSongMetadata(serverUrl, paths)
            // 更新缓存
            for ((encodedPath, meta) in metadataMap) {
                // 解码路径以便后续用原始路径做 key（可选，看你的缓存策略）
                val decodedPath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
                metadataCache[decodedPath] = meta
            }
            metadataMap
        }
    }
    /**
     * 清除缓存
     */
    fun clearCache() {
        metadataCache.clear()
    }
}