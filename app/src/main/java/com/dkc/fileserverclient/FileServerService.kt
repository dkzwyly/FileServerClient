package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileServerService(private val context: Context) {

    private val client: OkHttpClient by lazy {
        createUnsafeOkHttpClient()
    }

    private val gson = Gson()

    @Suppress("CustomX509TrustManager")
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                @Suppress("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                @Suppress("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    suspend fun testConnection(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val healthUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/health"
            val request = Request.Builder().url(healthUrl).header("User-Agent", "FileServerClient/1.0").build()
            val response = client.newCall(request).execute()
            val isSuccessful = response.isSuccessful
            response.close()
            isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun formatServerUrl(url: String): String {
        var formattedUrl = url.trim()
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
        }
        if (!formattedUrl.contains(":") || formattedUrl.matches(Regex("https?://[^:]+$"))) {
            formattedUrl += if (formattedUrl.startsWith("https://")) ":443" else ":8080"
        }
        return formattedUrl
    }

    suspend fun getFileList(
        serverUrl: String,
        path: String = "",
        sortBy: String = "name",
        sortOrder: String = "asc"
    ): List<FileSystemItem> = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val baseUrl = formattedUrl.removeSuffix("/")
            val encodedPath = if (path.isNotEmpty()) {
                path.split("/").joinToString("/") { segment -> URLEncoder.encode(segment, "UTF-8") }
            } else ""

            val url = if (encodedPath.isEmpty()) {
                "$baseUrl/api/fileserver/list?sortBy=$sortBy&sortOrder=$sortOrder"
            } else {
                "$baseUrl/api/fileserver/list/$encodedPath?sortBy=$sortBy&sortOrder=$sortOrder"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FileServerClient/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                val apiResponse = gson.fromJson(json, ApiListResponse::class.java)

                val items = mutableListOf<FileSystemItem>()

                if (path.isNotEmpty() && path != "/") {
                    val parentPath = if (path.contains("/")) path.substringBeforeLast("/") else ""
                    items.add(FileSystemItem(
                        name = "..",
                        path = parentPath,
                        size = 0,
                        extension = "",
                        sizeFormatted = "",
                        lastModified = "",
                        isVideo = false,
                        isAudio = false,
                        mimeType = "inode/directory",
                        encoding = ""
                    ))
                }

                apiResponse.directories.forEach { dir ->
                    val dirName = dir.name.ifEmpty { dir.path.substringAfterLast('/').ifEmpty { "未命名目录" } }
                    items.add(FileSystemItem(
                        name = dirName,
                        path = dir.path,
                        size = 0,
                        extension = "",
                        sizeFormatted = "",
                        lastModified = "",
                        isVideo = false,
                        isAudio = false,
                        mimeType = "inode/directory",
                        encoding = ""
                    ))
                }

                apiResponse.files.forEach { file ->
                    val fileName = file.name.ifEmpty { file.path.substringAfterLast('/').ifEmpty { "未命名文件" } }
                    items.add(FileSystemItem(
                        name = fileName,
                        path = file.path,
                        size = file.size,
                        extension = file.extension,
                        sizeFormatted = file.sizeFormatted.ifEmpty { formatFileSize(file.size) },
                        lastModified = file.lastModified,
                        isVideo = file.isVideo,
                        isAudio = file.isAudio,
                        mimeType = file.mimeType,
                        encoding = file.encoding
                    ))
                }
                items
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("FileServerService", "获取文件列表失败", e)
            emptyList()
        }
    }

    suspend fun uploadFiles(
        serverUrl: String,
        files: List<Pair<File, String>>,
        targetPath: String = ""
    ): UploadResult = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext UploadResult(success = false, message = "没有选择要上传的文件")
        val validFiles = files.filter { (file, _) -> file.exists() && file.canRead() }
        if (validFiles.isEmpty()) return@withContext UploadResult(success = false, message = "没有有效的文件可上传")
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val uploadUrl = if (targetPath.isEmpty()) {
                "${formattedUrl.removeSuffix("/")}/api/fileserver/upload"
            } else {
                val encodedPath = targetPath.split("/").joinToString("/") { segment -> URLEncoder.encode(segment, "UTF-8") }
                "${formattedUrl.removeSuffix("/")}/api/fileserver/upload/$encodedPath"
            }
            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            validFiles.forEach { (file, originalName) ->
                multipartBuilder.addFormDataPart("files", originalName, file.asRequestBody("application/octet-stream".toMediaType()))
            }
            val requestBody = multipartBuilder.build()
            val request = Request.Builder().url(uploadUrl).post(requestBody).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                gson.fromJson(json, UploadResult::class.java)
            } else {
                UploadResult(success = false, message = "上传失败: ${response.code}")
            }
        } catch (e: Exception) {
            UploadResult(success = false, message = "上传异常: ${e.message}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes == 0L) return "0 B"
        val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
        var order = 0
        var len = bytes.toDouble()
        while (len >= 1024 && order < sizes.size - 1) {
            order++
            len /= 1024
        }
        return "%.2f ${sizes[order]}".format(len)
    }

    suspend fun deleteFile(serverUrl: String, filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val deleteUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/delete/$encodedPath"
            val request = Request.Builder().url(deleteUrl).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 歌词相关方法（保持不变） ====================
    data class LyricsMappingRequest(val songPath: String, val lyricsPath: String)
    data class LyricsFileInfo(val path: String, val name: String, val size: Long, val sizeFormatted: String, val modifiedTime: String)
    data class LyricsResponse(val type: String, val lyricsPath: String? = null, val fileName: String? = null,
                              val content: String? = null, val encoding: String? = null,
                              val files: List<LyricsFileInfo>? = null, val message: String? = null)
    data class LyricsMappingResponse(val songPath: String, val lyricsPath: String, val lyricsFileName: String, val exists: Boolean)

    suspend fun getLyrics(serverUrl: String, songPath: String): LyricsResponse = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedSongPath = URLEncoder.encode(songPath, "UTF-8")
            val lyricsUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/lyrics/$encodedSongPath"
            val request = Request.Builder().url(lyricsUrl).header("User-Agent", "FileServerClient/1.0").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                gson.fromJson(json, LyricsResponse::class.java)
            } else {
                LyricsResponse(type = "error", message = "获取歌词失败: ${response.code}")
            }
        } catch (e: Exception) {
            LyricsResponse(type = "error", message = "获取歌词异常: ${e.message}")
        }
    }

    suspend fun saveLyricsMapping(serverUrl: String, songPath: String, lyricsPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val mappingUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/lyrics/mapping"
            val requestBody = gson.toJson(LyricsMappingRequest(songPath, lyricsPath)).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(mappingUrl).post(requestBody).header("User-Agent", "FileServerClient/1.0").build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getLyricsMapping(serverUrl: String, songPath: String): LyricsMappingResponse? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedSongPath = URLEncoder.encode(songPath, "UTF-8")
            val mappingUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/lyrics/mapping/$encodedSongPath"
            val request = Request.Builder().url(mappingUrl).header("User-Agent", "FileServerClient/1.0").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                gson.fromJson(json, LyricsMappingResponse::class.java)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun markNoLyrics(serverUrl: String, songPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val mappingUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/lyrics/mapping"
            val requestBody = gson.toJson(LyricsMappingRequest(songPath, "NO_LYRICS")).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(mappingUrl).post(requestBody).header("User-Agent", "FileServerClient/1.0").build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getLyricsFiles(serverUrl: String, directory: String): List<LyricsFileInfo> = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedDirectory = URLEncoder.encode(directory, "UTF-8")
            val filesUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/lyrics/files/$encodedDirectory"
            val request = Request.Builder().url(filesUrl).header("User-Agent", "FileServerClient/1.0").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                val apiResponse = gson.fromJson(json, Map::class.java)
                val filesList = apiResponse["lyricsFiles"] as? List<Map<String, Any>> ?: emptyList()
                filesList.map { fileMap ->
                    LyricsFileInfo(
                        path = fileMap["path"] as? String ?: "",
                        name = fileMap["name"] as? String ?: "",
                        size = (fileMap["size"] as? Double ?: 0.0).toLong(),
                        sizeFormatted = fileMap["sizeFormatted"] as? String ?: "",
                        modifiedTime = fileMap["modifiedTime"] as? String ?: ""
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ==================== 歌曲元数据相关 API ====================
    data class SongMetadataResponse(
        val path: String,
        val fileName: String,
        val metadata: SongMetadata
    )

    data class SaveMetadataMappingRequest(
        val songPath: String,
        val title: String?,
        val artist: String?,
        val album: String?
    )

    suspend fun getSongMetadata(serverUrl: String, songPath: String): SongMetadata? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(songPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/metadata/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                val apiResponse = gson.fromJson(json, SongMetadataResponse::class.java)
                apiResponse.metadata
            } else null
        } catch (e: Exception) {
            Log.e("FileServerService", "获取歌曲元数据失败", e)
            null
        }
    }

    suspend fun saveSongMetadataMapping(
        serverUrl: String,
        songPath: String,
        title: String?,
        artist: String?,
        album: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/metadata/mapping"
            val body = gson.toJson(
                SaveMetadataMappingRequest(
                    songPath = songPath,
                    title = title,
                    artist = artist,
                    album = album
                )
            ).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("FileServerService", "保存歌曲元数据映射失败", e)
            false
        }
    }

    suspend fun deleteSongMetadataMapping(serverUrl: String, songPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(songPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/metadata/mapping/$encodedPath"
            val request = Request.Builder().url(url).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("FileServerService", "删除歌曲元数据映射失败", e)
            false
        }
    }

    suspend fun getAlbumCover(serverUrl: String, songPath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(songPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) {
            Log.e("FileServerService", "获取专辑封面失败", e)
            null
        }
    }

    suspend fun uploadAlbumCover(
        serverUrl: String,
        songPath: String,
        coverFile: File
    ): String? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/cover/upload"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("songPath", songPath)
                .addFormDataPart(
                    "coverFile",
                    coverFile.name,
                    coverFile.asRequestBody("image/*".toMediaType())
                )
                .build()
            val request = Request.Builder().url(url).post(requestBody).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                val result = gson.fromJson(json, Map::class.java)
                result["coverPath"] as? String
            } else null
        } catch (e: Exception) {
            Log.e("FileServerService", "上传专辑封面失败", e)
            null
        }
    }

    suspend fun deleteAlbumCover(serverUrl: String, songPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(songPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
            val request = Request.Builder().url(url).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("FileServerService", "删除专辑封面失败", e)
            false
        }
    }

    // ==================== 图片元数据重建 ====================
    suspend fun reindexPhotoMetadata(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/photo-metadata/reindex"
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create(null, ""))
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("FileServerService", "重建元数据请求失败", e)
            false
        }
    }
}