package com.dkc.fileserverclient

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

interface FileServerApiService {

    @GET("api/fileserver/status")
    suspend fun getStatus(): Response<ServerStatus>

    @GET("api/fileserver/health")
    suspend fun getHealth(): Response<HealthResponse>

    // 使用 encoded = true 避免 Retrofit 自动编码
    @GET("api/fileserver/list/{path}")
    suspend fun getFileList(@Path("path", encoded = true) path: String): Response<FileListResponse>

    @GET("api/fileserver/download/{path}")
    suspend fun downloadFile(@Path("path", encoded = true) path: String): Response<ResponseBody>

    @GET("api/fileserver/preview/{path}")
    suspend fun previewFile(@Path("path", encoded = true) path: String): Response<ResponseBody>

    @GET("api/fileserver/stream/{path}")
    suspend fun streamFile(@Path("path", encoded = true) path: String): Response<ResponseBody>

    @Multipart
    @POST("api/fileserver/upload/{path}")
    suspend fun uploadFiles(
        @Path("path", encoded = true) path: String,
        @Part files: List<MultipartBody.Part>
    ): Response<UploadResponse>

    @POST("api/fileserver/directory/{path}")
    suspend fun createDirectory(@Path("path", encoded = true) path: String): Response<Map<String, Any>>
}