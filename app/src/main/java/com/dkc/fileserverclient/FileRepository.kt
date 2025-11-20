// FileRepository.kt
package com.dkc.fileserverclient

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.*
import java.net.URLEncoder

class FileRepository(private val context: Context) {

    // 不再缓存 apiService，每次都从 RetrofitClient 获取
    private fun getApiService(): FileServerApiService {
        return RetrofitClient.getFileServerApiService()
    }

    suspend fun getServerStatus(): ServerStatus {
        val response = getApiService().getStatus()
        if (response.isSuccessful && response.body() != null) {
            return response.body()!!
        } else {
            throw Exception("服务器状态获取失败: ${response.code()}")
        }
    }

    suspend fun getFileList(path: String = ""): FileListResponse {
        // 手动编码路径，确保多层目录能正确传递
        val encodedPath = encodePathForApi(path)
        println("DEBUG: 请求路径 - 原始: '$path', 编码后: '$encodedPath'")

        val response = getApiService().getFileList(encodedPath)
        if (response.isSuccessful && response.body() != null) {
            return response.body()!!
        } else {
            throw Exception("文件列表获取失败: ${response.code()}")
        }
    }

    suspend fun downloadFile(filePath: String, destination: File): File {
        return withContext(Dispatchers.IO) {
            val encodedPath = encodePathForApi(filePath)
            val response = getApiService().downloadFile(encodedPath)
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(destination)

                    inputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    destination
                } ?: throw Exception("下载响应为空")
            } else {
                throw Exception("下载失败: ${response.code()}")
            }
        }
    }

    suspend fun uploadFiles(targetPath: String, uris: List<Uri>): UploadResponse {
        return withContext(Dispatchers.IO) {
            val encodedPath = encodePathForApi(targetPath)
            val parts = uris.mapNotNull { uri ->
                try {
                    val file = getFileFromUri(uri)
                    val requestFile = file.asRequestBody("multipart/form-data".toMediaType())

                    MultipartBody.Part.createFormData(
                        "files",
                        file.name,
                        requestFile
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (parts.isEmpty()) {
                throw Exception("没有有效的文件可以上传")
            }

            val response = getApiService().uploadFiles(encodedPath, parts)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                throw Exception("上传失败: ${response.code()}")
            }
        }
    }

    suspend fun createDirectory(path: String) {
        val encodedPath = encodePathForApi(path)
        val response = getApiService().createDirectory(encodedPath)
        if (!response.isSuccessful) {
            throw Exception("创建目录失败: ${response.code()}")
        }
    }

    /**
     * 获取文件预览URL（用于图片和媒体文件直接显示）
     */
// 在 FileRepository 中添加日志
    fun getPreviewUrl(filePath: String): String {
        val encodedPath = encodePathForApi(filePath)
        val baseUrl = RetrofitClient.getCurrentBaseUrl()
        val previewUrl = "$baseUrl/api/fileserver/preview/$encodedPath"
        println("DEBUG: 生成的预览URL: $previewUrl")
        return previewUrl
    }

    /**
     * 获取流媒体URL（用于音视频播放）
     */
    fun getStreamUrl(filePath: String): String {
        val encodedPath = encodePathForApi(filePath)
        val baseUrl = RetrofitClient.getCurrentBaseUrl() // 修改这里
        return "$baseUrl/api/fileserver/stream/$encodedPath"
    }

    /**
     * 预览文本文件
     */
    suspend fun previewTextFile(filePath: String): TextPreviewResponse {
        return withContext(Dispatchers.IO) {
            val encodedPath = encodePathForApi(filePath)
            println("DEBUG: 请求文本预览: $encodedPath")

            val response = getApiService().previewFile(encodedPath)
            println("DEBUG: 文本预览响应码: ${response.code()}")

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                println("DEBUG: 文本预览响应内容: $jsonString")
                return@withContext parseTextPreviewResponse(jsonString)
            } else {
                println("DEBUG: 文本预览失败: ${response.code()}")
                throw Exception("文本预览失败: ${response.code()}")
            }
        }
    }
    /**
     * 下载图片到本地缓存（用于图片预览）
     */
    suspend fun downloadImageForPreview(filePath: String): File {
        return withContext(Dispatchers.IO) {
            val encodedPath = encodePathForApi(filePath)
            val response = getApiService().previewFile(encodedPath)

            if (response.isSuccessful) {
                response.body()?.let { body ->
                    // 创建缓存文件
                    val cacheDir = context.cacheDir
                    val tempFile = File.createTempFile("preview_", ".tmp", cacheDir)

                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(tempFile)

                    inputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    return@withContext tempFile
                } ?: throw Exception("预览响应为空")
            } else {
                throw Exception("图片预览失败: ${response.code()}")
            }
        }
    }

    /**
     * 解析文本预览响应
     */
    private fun parseTextPreviewResponse(jsonString: String): TextPreviewResponse {
        return try {
            val jsonObject = JSONObject(jsonString)
            TextPreviewResponse(
                type = jsonObject.optString("type", ""),
                fileName = jsonObject.optString("fileName", ""),
                content = jsonObject.optString("content", ""),
                encoding = jsonObject.optString("encoding", "utf-8"),
                size = jsonObject.optInt("size", 0),
                truncated = jsonObject.optBoolean("truncated", false)
            )
        } catch (e: Exception) {
            throw Exception("解析文本预览响应失败: ${e.message}")
        }
    }

    /**
     * 清理预览缓存
     */
    fun clearPreviewCache() {
        val cacheDir = context.cacheDir
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("preview_")) {
                file.delete()
            }
        }
    }

    private fun getFileFromUri(uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
        val file = File.createTempFile("upload_", "", context.cacheDir)
        inputStream?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return file
    }

    /**
     * 为API调用编码路径
     * 处理多层目录的编码问题
     */
    private fun encodePathForApi(path: String): String {
        if (path.isEmpty()) {
            return ""
        }

        // 将路径分割成各个部分，分别编码，然后用斜杠连接
        val parts = path.split("/")
        val encodedParts = parts.map { part ->
            URLEncoder.encode(part, "UTF-8")
        }

        return encodedParts.joinToString("/")
    }
}