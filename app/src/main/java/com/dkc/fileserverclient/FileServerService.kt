package com.dkc.fileserverclient

import android.content.ContentValues.TAG
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileServerService(private val context: Context) {

    private val TAG = "FileServerService"   // 修正：添加 TAG 常量

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

    // ========== 文件列表 ==========
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
            Log.e(TAG, "获取文件列表失败", e)
            emptyList()
        }
    }

    // ========== 上传 ==========
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

    // ========== 删除文件 ==========
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

    // ========== 创建目录 ==========
    suspend fun createDirectory(serverUrl: String, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/directory/$encodedPath"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "创建目录失败", e)
            false
        }
    }

    // ========== 删除目录 ==========
    suspend fun deleteDirectory(serverUrl: String, directoryPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = directoryPath.split("/")
                .joinToString("/") { segment -> URLEncoder.encode(segment, "UTF-8") }
            val deleteUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/directory/$encodedPath"

            val request = Request.Builder()
                .url(deleteUrl)
                .delete()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "删除文件夹失败: $directoryPath", e)
            false
        }
    }

    // ========== 重命名 ==========
    suspend fun renameItem(
        serverUrl: String,
        oldPath: String,
        newName: String
    ): OperationResult = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedOld = URLEncoder.encode(oldPath, "UTF-8")
            val encodedNew = URLEncoder.encode(newName, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/rename?oldPath=$encodedOld&newName=$encodedNew"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                OperationResult(true)
            } else {
                val errorBody = response.body?.string()
                val message = parseErrorMessage(errorBody) ?: "重命名失败 (HTTP ${response.code})"
                OperationResult(false, message)
            }
        } catch (e: Exception) {
            OperationResult(false, "网络异常: ${e.message}")
        }
    }

    // ========== 移动 ==========
    suspend fun moveItem(
        serverUrl: String,
        sourcePath: String,
        destPath: String
    ): OperationResult = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedSrc = URLEncoder.encode(sourcePath, "UTF-8")
            val encodedDst = URLEncoder.encode(destPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/move?sourcePath=$encodedSrc&destPath=$encodedDst"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                OperationResult(true)
            } else {
                val errorBody = response.body?.string()
                val message = parseErrorMessage(errorBody) ?: "移动失败 (HTTP ${response.code})"
                OperationResult(false, message)
            }
        } catch (e: Exception) {
            OperationResult(false, "网络异常: ${e.message}")
        }
    }

    // ========== 复制 ==========
    suspend fun copyItem(
        serverUrl: String,
        sourcePath: String,
        destPath: String
    ): OperationResult = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedSrc = URLEncoder.encode(sourcePath, "UTF-8")
            val encodedDst = URLEncoder.encode(destPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/copy?sourcePath=$encodedSrc&destPath=$encodedDst"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                OperationResult(true)
            } else {
                val errorBody = response.body?.string()
                val message = parseErrorMessage(errorBody) ?: "复制失败 (HTTP ${response.code})"
                OperationResult(false, message)
            }
        } catch (e: Exception) {
            OperationResult(false, "网络异常: ${e.message}")
        }
    }

    private fun parseErrorMessage(json: String?): String? {
        if (json.isNullOrEmpty()) return null
        return try {
            val jsonObject = Gson().fromJson(json, JsonObject::class.java)
            jsonObject.get("error")?.asString
                ?: jsonObject.get("message")?.asString
                ?: jsonObject.get("Message")?.asString
        } catch (e: Exception) {
            null
        }
    }

    // ========== 歌词相关（原有） ==========
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

    // ========== 删除歌词映射（新增） ==========
    suspend fun deleteLyricsMapping(serverUrl: String, songPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(songPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/lyrics/mapping/$encodedPath"
            val request = Request.Builder().url(url).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ========== 歌曲元数据相关（原有） ==========
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
            Log.e(TAG, "获取歌曲元数据失败", e)
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
            Log.e(TAG, "保存歌曲元数据映射失败", e)
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
            Log.e(TAG, "删除歌曲元数据映射失败", e)
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
            Log.e(TAG, "获取专辑封面失败", e)
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
            Log.e(TAG, "上传专辑封面失败", e)
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
            Log.e(TAG, "删除专辑封面失败", e)
            false
        }
    }

    // ========== 音频全量重建索引（新增） ==========
    suspend fun reindexAllAudioMetadata(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/metadata/reindex"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "重建音频元数据索引失败", e)
            false
        }
    }

    // ========== 批量歌曲元数据（原有） ==========
    suspend fun getBatchSongMetadata(
        serverUrl: String,
        paths: List<String>
    ): Map<String, SongMetadata> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptyMap()
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/song/metadata/batch"

            val encodedPaths = paths.map { URLEncoder.encode(it, "UTF-8") }
            val jsonBody = gson.toJson(encodedPaths)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: "{}"
                val type = object : TypeToken<Map<String, SongMetadata>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } else {
                Log.e(TAG, "获取批量歌曲元数据失败: ${response.code}")
                emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取批量歌曲元数据异常", e)
            emptyMap()
        }
    }

    // ========== 批量拍摄日期（原有） ==========
    suspend fun getBatchDateTaken(
        serverUrl: String,
        paths: List<String>
    ): Map<String, String?> = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext emptyMap()
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/photo-metadata/batch"

            val jsonBody = gson.toJson(paths)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: "{}"
                val type = object : TypeToken<Map<String, String?>>() {}.type
                gson.fromJson(json, type)
            } else {
                Log.e(TAG, "获取批量拍摄日期失败: ${response.code}")
                emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取批量拍摄日期异常", e)
            emptyMap()
        }
    }

    // ========== 图片元数据重建（原有） ==========
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
            Log.e(TAG, "重建图片元数据请求失败", e)
            false
        }
    }

    // ========== 照片搜索（新增） ==========
    suspend fun searchPhotos(
        serverUrl: String,
        directory: String? = null,
        sortBy: String = "dateTaken",
        sortAscending: Boolean = true,
        startDate: String? = null,
        endDate: String? = null,
        minLat: Double? = null,
        maxLat: Double? = null,
        minLng: Double? = null,
        maxLng: Double? = null,
        page: Int = 1,
        pageSize: Int = 100
    ): PhotoSearchResult? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val baseUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/photo-metadata/search"
            val params = mutableListOf<String>()
            directory?.let { params.add("directory=${URLEncoder.encode(it, "UTF-8")}") }
            params.add("sortBy=$sortBy")
            params.add("sortAscending=$sortAscending")
            startDate?.let { params.add("startDate=${URLEncoder.encode(it, "UTF-8")}") }
            endDate?.let { params.add("endDate=${URLEncoder.encode(it, "UTF-8")}") }
            minLat?.let { params.add("minLat=$it") }
            maxLat?.let { params.add("maxLat=$it") }
            minLng?.let { params.add("minLng=$it") }
            maxLng?.let { params.add("maxLng=$it") }
            params.add("page=$page")
            params.add("pageSize=$pageSize")

            val url = if (params.isNotEmpty()) "$baseUrl?${params.joinToString("&")}" else baseUrl
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, PhotoSearchResult::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "搜索照片失败", e)
            null
        }
    }

    // ========== 刷新单张图片元数据（新增） ==========
    suspend fun refreshPhotoMetadata(serverUrl: String, photoPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(photoPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/photo-metadata/refresh/$encodedPath"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "刷新照片元数据失败", e)
            false
        }
    }

    // ========== 影视库（原有） ==========
    suspend fun getVideoLibrary(serverUrl: String): VideoLibraryNode? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/video-library"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FileServerClient/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, VideoLibraryNode::class.java)
            } else {
                Log.e(TAG, "获取影视库失败: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取影视库异常", e)
            null
        }
    }

    // ========== 下载文件（新增） ==========
    suspend fun downloadFile(serverUrl: String, filePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/download/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "下载文件失败", e)
            null
        }
    }

    // ========== 预览文件（文本，返回 JSON 字符串） ==========
    suspend fun previewFile(serverUrl: String, filePath: String, page: Int = 1): String? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath?page=$page"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            Log.e(TAG, "预览文件失败", e)
            null
        }
    }

    // ========== 流式传输（返回字节流） ==========
    suspend fun streamFile(serverUrl: String, filePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/stream/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "流式传输文件失败", e)
            null
        }
    }

    // ========== 图片缩略图 ==========
    suspend fun getImageThumbnail(serverUrl: String, imagePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(imagePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "获取图片缩略图失败", e)
            null
        }
    }

    // ========== 回收站相关 ==========
    suspend fun getTrashList(serverUrl: String): List<TrashRecord> = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/trash/list"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: "{}"
                val root = gson.fromJson(json, JsonObject::class.java)
                val itemsArray = root.getAsJsonArray("items")
                itemsArray.map { gson.fromJson(it, TrashRecord::class.java) }
            } else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "获取回收站列表失败", e)
            emptyList()
        }
    }

    suspend fun restoreFromTrash(serverUrl: String, recordId: String, targetDir: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            var url = "${formattedUrl.removeSuffix("/")}/api/fileserver/trash/restore?recordId=${URLEncoder.encode(recordId, "UTF-8")}"
            targetDir?.let { url += "&targetDir=${URLEncoder.encode(it, "UTF-8")}" }
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "恢复回收站文件失败", e)
            false
        }
    }

    suspend fun permanentDeleteFromTrash(serverUrl: String, recordId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/trash/permanent?recordId=${URLEncoder.encode(recordId, "UTF-8")}"
            val request = Request.Builder().url(url).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "永久删除回收站文件失败", e)
            false
        }
    }

    suspend fun emptyTrash(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/trash/empty"
            val request = Request.Builder().url(url).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "清空回收站失败", e)
            false
        }
    }

    // ========== 任务状态查询 ==========
    suspend fun getTaskStatus(serverUrl: String, taskId: String): TaskStatus? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/task/$taskId"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, TaskStatus::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取任务状态失败", e)
            null
        }
    }

    // ========== 章节相关 ==========
    suspend fun getChapters(serverUrl: String, filePath: String): ChapterIndex? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/chapters/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, ChapterIndex::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取章节失败", e)
            null
        }
    }

    suspend fun rebuildChapters(serverUrl: String, filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/chapters/rebuild/$encodedPath"
            val request = Request.Builder().url(url).post(RequestBody.create(null, "")).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "重建章节索引失败", e)
            false
        }
    }

    suspend fun getChapterIndexInfo(serverUrl: String, filePath: String): ChapterIndexInfo? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/chapters/info/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                val root = gson.fromJson(json, JsonObject::class.java)
                root.get("info")?.let { gson.fromJson(it, ChapterIndexInfo::class.java) }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取章节索引信息失败", e)
            null
        }
    }

    suspend fun deleteChapterIndex(serverUrl: String, filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(filePath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/chapters/$encodedPath"
            val request = Request.Builder().url(url).delete().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "删除章节索引失败", e)
            false
        }
    }

    suspend fun getAllChapterIndexes(serverUrl: String): List<ChapterIndexInfo> = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/chapters/admin/all"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: "{}"
                val root = gson.fromJson(json, JsonObject::class.java)
                val array = root.getAsJsonArray("indexFiles")
                array.map { gson.fromJson(it, ChapterIndexInfo::class.java) }
            } else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "获取所有章节索引失败", e)
            emptyList()
        }
    }

    // ========== 视频缩略图相关 ==========
    suspend fun generateVideoThumbnail(serverUrl: String, request: VideoThumbnailRequest): VideoThumbnailResult? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/video-thumbnail/generate"
            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder().url(url).post(body).build()
            val response = client.newCall(httpRequest).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, VideoThumbnailResult::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "生成视频缩略图失败", e)
            null
        }
    }

    suspend fun getVideoThumbnail(serverUrl: String, videoPath: String, width: Int = 320, height: Int = 180): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(videoPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/video-thumbnail/$encodedPath?width=$width&height=$height"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "获取视频缩略图失败", e)
            null
        }
    }

    suspend fun getVideoThumbnailStatus(serverUrl: String): ThumbnailGenerationStatus? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/video-thumbnail/status"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, ThumbnailGenerationStatus::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取视频缩略图生成状态失败", e)
            null
        }
    }

    suspend fun getVideoInfo(serverUrl: String, videoPath: String): VideoInfo? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val encodedPath = URLEncoder.encode(videoPath, "UTF-8")
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/video-info/$encodedPath"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, VideoInfo::class.java)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取视频信息失败", e)
            null
        }
    }

    // ========== 服务器状态（新增） ==========
    suspend fun getServerStatus(serverUrl: String): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/status"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                gson.fromJson(json, Map::class.java) as? Map<String, Any>
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "获取服务器状态失败", e)
            null
        }
    }


}