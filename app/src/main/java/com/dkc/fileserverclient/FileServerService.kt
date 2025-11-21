package com.dkc.fileserverclient

import android.content.Context
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class FileServerService(private val context: Context) {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    suspend fun testConnection(serverUrl: String): Boolean {
        return try {
            val healthUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/health"
            val request = Request.Builder()
                .url(healthUrl)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFileList(serverUrl: String, path: String = ""): List<FileSystemItem> {
        return try {
            val url = "${serverUrl.removeSuffix("/")}/api/fileserver/list/${path.removePrefix("/")}"
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                val apiResponse = gson.fromJson(json, ApiListResponse::class.java)

                val items = mutableListOf<FileSystemItem>()

                // 添加上级目录
                if (path.isNotEmpty() && path != "/") {
                    val parentPath = File(path).parent ?: ""
                    items.add(FileSystemItem(
                        name = "..",
                        path = parentPath,
                        size = 0,
                        extension = "",
                        sizeFormatted = "",
                        lastModified = "",
                        isVideo = false,
                        isAudio = false,
                        mimeType = "",
                        encoding = ""
                    ))
                }

                // 添加目录
                apiResponse.directories.forEach { dir ->
                    items.add(FileSystemItem(
                        name = dir.name,
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

                // 添加文件
                apiResponse.files.forEach { file ->
                    items.add(FileSystemItem(
                        name = file.name,
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
            emptyList()
        }
    }

    suspend fun uploadFiles(serverUrl: String, files: List<File>, targetPath: String = ""): UploadResult {
        return try {
            var uploadUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/upload"
            if (targetPath.isNotEmpty()) {
                uploadUrl += "?path=${java.net.URLEncoder.encode(targetPath, "UTF-8")}"
            }

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)

            files.forEach { file ->
                multipartBuilder.addFormDataPart(
                    "files",
                    file.name,
                    file.asRequestBody("application/octet-stream".toMediaType())
                )
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .post(multipartBuilder.build())
                .build()

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
}