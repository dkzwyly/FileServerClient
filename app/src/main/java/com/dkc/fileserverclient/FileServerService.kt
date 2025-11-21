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

class FileServerService(private val context: Context) {

    private val client: OkHttpClient by lazy {
        createUnsafeOkHttpClient()
    }

    private val gson = Gson()

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            // 创建信任所有证书的 TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // 创建 SSLContext 使用信任所有证书的 TrustManager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 创建不验证主机名的 HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            return OkHttpClient.Builder()
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

    // 其他方法保持不变...
    suspend fun testConnection(serverUrl: String): Boolean {
        return try {
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

        // 如果没有协议前缀，添加https://（优先使用HTTPS）
        if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
            formattedUrl = "https://$formattedUrl"
            Log.d("FileServerService", "自动添加协议前缀: $formattedUrl")
        }

        // 确保有端口号
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

    // getFileList 和 uploadFiles 方法保持不变...
    suspend fun getFileList(serverUrl: String, path: String = ""): List<FileSystemItem> {
        return try {
            val formattedUrl = formatServerUrl(serverUrl)
            val url = "${formattedUrl.removeSuffix("/")}/api/fileserver/list/${path.removePrefix("/")}"

            Log.d("FileServerService", "获取文件列表: $url")

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
                    // 确保目录名不为空
                    val dirName = if (dir.name.isEmpty()) {
                        dir.path.substringAfterLast('/').ifEmpty { "未命名目录" }
                    } else {
                        dir.name
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
                    // 确保文件名不为空
                    val fileName = if (file.name.isEmpty()) {
                        file.path.substringAfterLast('/').ifEmpty { "未命名文件" }
                    } else {
                        file.name
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

    suspend fun uploadFiles(serverUrl: String, files: List<File>, targetPath: String = ""): UploadResult {
        return try {
            val formattedUrl = formatServerUrl(serverUrl)
            var uploadUrl = "${formattedUrl.removeSuffix("/")}/api/fileserver/upload"

            if (targetPath.isNotEmpty()) {
                uploadUrl += "?path=${java.net.URLEncoder.encode(targetPath, "UTF-8")}"
            }

            Log.d("FileServerService", "上传文件到: $uploadUrl")

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
}