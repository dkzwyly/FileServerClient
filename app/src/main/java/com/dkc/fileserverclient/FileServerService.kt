package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
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
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    // 信任所有客户端证书（用于测试环境）
                }

                @Suppress("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    // 信任所有服务器证书（用于测试环境）
                }

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

            Log.d("FileServerService", "测试连接: $healthUrl")

            val request = Request.Builder()
                .url(healthUrl)
                .header("User-Agent", "FileServerClient/1.0")
                .build()

            val response = client.newCall(request).execute()
            val isSuccessful = response.isSuccessful

            Log.d("FileServerService", "连接测试结果: $isSuccessful, 状态码: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d("FileServerService", "响应内容: $responseBody")
            }

            response.close()
            isSuccessful
        } catch (e: SocketTimeoutException) {
            Log.e("FileServerService", "连接超时: ${e.message}")
            false
        } catch (e: IOException) {
            Log.e("FileServerService", "网络错误: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("FileServerService", "未知错误: ${e.message}")
            false
        }
    }

    private fun formatServerUrl(url: String): String {
        var formattedUrl = url.trim()

        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
            Log.d("FileServerService", "自动添加协议前缀: $formattedUrl")
        }

        if (!formattedUrl.contains(":") || formattedUrl.matches(Regex("https?://[^:]+$"))) {
            formattedUrl += if (formattedUrl.startsWith("https://")) {
                ":443"
            } else {
                ":8080"
            }
            Log.d("FileServerService", "自动添加默认端口: $formattedUrl")
        }

        return formattedUrl
    }

    suspend fun getFileList(serverUrl: String, path: String = ""): List<FileSystemItem> = withContext(Dispatchers.IO) {
        try {
            val formattedUrl = formatServerUrl(serverUrl)

            // 修复：正确处理路径编码，特别是深层目录
            val url = if (path.isEmpty()) {
                "${formattedUrl.removeSuffix("/")}/api/fileserver/list"
            } else {
                // 对于深层目录，需要正确编码路径
                val encodedPath = path.split("/").joinToString("/") { segment ->
                    java.net.URLEncoder.encode(segment, "UTF-8")
                }
                "${formattedUrl.removeSuffix("/")}/api/fileserver/list/$encodedPath"
            }

            Log.d("FileServerService", "获取文件列表: $url (原始路径: $path)")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "FileServerClient/1.0")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                Log.d("FileServerService", "文件列表响应: $json")

                val apiResponse = gson.fromJson(json, ApiListResponse::class.java)

                val items = mutableListOf<FileSystemItem>()

                // 添加上级目录（返回上级）
                if (path.isNotEmpty() && path != "/") {
                    val parentPath = if (path.contains("/")) {
                        path.substringBeforeLast("/")
                    } else {
                        ""
                    }
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

                // 添加目录
                apiResponse.directories.forEach { dir ->
                    val dirName = dir.name.ifEmpty {
                        dir.path.substringAfterLast('/').ifEmpty { "未命名目录" }
                    }

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

                // 添加文件
                apiResponse.files.forEach { file ->
                    val fileName = file.name.ifEmpty {
                        file.path.substringAfterLast('/').ifEmpty { "未命名文件" }
                    }

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

                Log.d("FileServerService", "成功获取 ${items.size} 个文件/目录")
                items
            } else {
                Log.e("FileServerService", "获取文件列表失败: ${response.code} - ${response.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("FileServerService", "获取文件列表异常: ${e.message}")
            emptyList()
        }
    }

    suspend fun uploadFiles(serverUrl: String, files: List<File>, targetPath: String = ""): UploadResult = withContext(Dispatchers.IO) {
        // 添加空列表检查
        if (files.isEmpty()) {
            Log.e("FileServerService", "文件列表为空")
            return@withContext UploadResult(success = false, message = "没有选择要上传的文件")
        }

        // 检查文件是否存在
        val validFiles = files.filter { file ->
            if (!file.exists()) {
                Log.w("FileServerService", "文件不存在: ${file.absolutePath}")
                false
            } else if (!file.canRead()) {
                Log.w("FileServerService", "文件不可读: ${file.absolutePath}")
                false
            } else {
                true
            }
        }

        if (validFiles.isEmpty()) {
            Log.e("FileServerService", "没有有效的文件可上传")
            return@withContext UploadResult(success = false, message = "没有有效的文件可上传")
        }

        try {
            val formattedUrl = formatServerUrl(serverUrl)

            // 修复：正确处理深层目录的上传路径
            val uploadUrl = if (targetPath.isEmpty()) {
                "${formattedUrl.removeSuffix("/")}/api/fileserver/upload"
            } else {
                // 对深层目录路径进行分段编码
                val encodedPath = targetPath.split("/").joinToString("/") { segment ->
                    java.net.URLEncoder.encode(segment, "UTF-8")
                }
                "${formattedUrl.removeSuffix("/")}/api/fileserver/upload/$encodedPath"
            }

            Log.d("FileServerService", "上传 ${validFiles.size} 个文件到: $uploadUrl (目标路径: $targetPath)")

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)

            validFiles.forEach { file ->
                Log.d("FileServerService", "添加文件到multipart: ${file.name} (${file.length()} bytes)")
                multipartBuilder.addFormDataPart(
                    "files",
                    file.name,
                    file.asRequestBody("application/octet-stream".toMediaType())
                )
            }

            val requestBody = multipartBuilder.build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                Log.d("FileServerService", "上传成功: $json")
                gson.fromJson(json, UploadResult::class.java)
            } else {
                val errorBody = response.body?.string() ?: "无错误信息"
                Log.e("FileServerService", "上传失败: ${response.code} - $errorBody")
                UploadResult(success = false, message = "上传失败: ${response.code} - $errorBody")
            }
        } catch (e: Exception) {
            Log.e("FileServerService", "上传异常: ${e.message}")
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
            val encodedPath = java.net.URLEncoder.encode(filePath, "UTF-8")
            val deleteUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/delete/$encodedPath"

            Log.d("FileServerService", "删除文件: $deleteUrl")

            val request = Request.Builder()
                .url(deleteUrl)
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val isSuccessful = response.isSuccessful

            if (isSuccessful) {
                Log.d("FileServerService", "删除文件成功: $filePath")
            } else {
                Log.e("FileServerService", "删除文件失败: ${response.code} - ${response.message}")
            }

            response.close()
            isSuccessful
        } catch (e: Exception) {
            Log.e("FileServerService", "删除文件异常: ${e.message}")
            false
        }
    }
}