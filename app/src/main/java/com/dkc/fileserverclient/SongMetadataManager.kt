package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

class SongMetadataManager(
    private val context: Context,
    private val fileServerService: FileServerService
) {
    // 缓存：原始路径 -> SongMetadata
    private val metadataCache = mutableMapOf<String, SongMetadata>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    /**
     * 获取单个元数据（优先缓存，否则从服务器拉取）
     */
    suspend fun getMetadata(serverUrl: String, songPath: String): SongMetadata? {
        // 检查缓存
        metadataCache[songPath]?.let {
            Log.d("MetaCache", "命中缓存: $songPath")
            return it
        }

        // 从服务器获取
        Log.d("MetaCache", "请求单个元数据: $songPath")
        val metadata = fileServerService.getSongMetadata(serverUrl, songPath)
        if (metadata != null) {
            metadataCache[songPath] = metadata
        }
        return metadata
    }

    /**
     * 批量获取元数据（智能过滤，只请求未缓存路径）
     * @param paths 原始路径列表（未编码，如 "data/音乐/xxx.mp3"）
     */
    suspend fun getBatchMetadata(serverUrl: String, paths: List<String>): Map<String, SongMetadata> {
        // 1. 分离已缓存和未缓存路径
        val cached = mutableMapOf<String, SongMetadata>()
        val uncachedPaths = mutableListOf<String>()

        for (path in paths) {
            metadataCache[path]?.let {
                cached[path] = it
            } ?: uncachedPaths.add(path)
        }

        // 如果全都在缓存中，直接返回
        if (uncachedPaths.isEmpty()) {
            Log.d("MetaCache", "批量请求全部命中缓存，跳过网络请求")
            return cached
        }

        // 2. 只请求未缓存的路径
        Log.d("MetaCache", "批量请求未缓存路径: ${uncachedPaths.size} 条")
        val serverResult = fileServerService.getBatchSongMetadata(serverUrl, uncachedPaths)

        // 3. 将服务器返回的数据写入缓存（key 统一为原始路径）
        for ((rawKey, meta) in serverResult) {
            // 服务器返回的 key 就是你传入的 uncachedPaths 中的元素，无需额外解码
            metadataCache[rawKey] = meta
            cached[rawKey] = meta
        }

        // 对于服务器未返回的路径（极少情况），不再单独请求，返回 cached 即可
        return cached
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
     * 获取封面 URL（智能时间戳：自定义封面不加时间戳，内嵌封面不加）
     * 既然封面已经本地化且不会变，一律不加时间戳以充分利用 Coil 缓存
     */
    fun getCoverUrl(serverUrl: String, songPath: String, addTimestamp: Boolean = false): String {
        val encodedPath = URLEncoder.encode(songPath, "UTF-8")
        return "${serverUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
    }

    /**
     * 上传自定义封面
     */
    suspend fun uploadCover(
        serverUrl: String,
        songPath: String,
        coverFile: File
    ): Boolean {
        val coverPath = fileServerService.uploadAlbumCover(serverUrl, songPath, coverFile)
        if (coverPath != null) {
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
     */
    suspend fun deleteCover(serverUrl: String, songPath: String): Boolean {
        val success = fileServerService.deleteAlbumCover(serverUrl, songPath)
        if (success) {
            val refreshed = fileServerService.getSongMetadata(serverUrl, songPath)
            if (refreshed != null) {
                metadataCache[songPath] = refreshed
            } else {
                metadataCache[songPath]?.copy(hasCover = false, customCoverPath = null)
            }
        }
        return success
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        metadataCache.clear()
    }
}