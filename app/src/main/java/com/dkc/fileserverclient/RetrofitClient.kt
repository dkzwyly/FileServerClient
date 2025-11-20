// RetrofitClient.kt
package com.dkc.fileserverclient
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.net.ssl.TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier

object RetrofitClient {
    private var retrofit: Retrofit? = null
    private var currentBaseUrl: String = ""

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // 创建信任所有证书的 OkHttpClient（用于自签名证书）
    public fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            // 创建信任所有证书的 TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // 安装 TrustManager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 创建不验证主机名的 HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun initialize(context: Context) {
        val configManager = ServerConfigManager(context)
        val currentConfig = configManager.getCurrentConfig()

        currentConfig?.let { config ->
            updateBaseUrl(config.baseUrl)
        }
    }

    fun updateBaseUrl(baseUrl: String) {
        if (baseUrl != currentBaseUrl) {
            currentBaseUrl = baseUrl

            // 根据协议选择不同的 OkHttpClient
            val client = if (baseUrl.startsWith("https://")) {
                // 对于 HTTPS，使用信任所有证书的客户端（支持自签名证书）
                createUnsafeOkHttpClient()
            } else {
                // 对于 HTTP，使用普通客户端
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(loggingInterceptor)
                    .build()
            }

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
    }

    fun getFileServerApiService(): FileServerApiService {
        if (retrofit == null) {
            throw IllegalStateException("RetrofitClient not initialized. Call initialize() first.")
        }
        return retrofit!!.create(FileServerApiService::class.java)
    }

    fun getCurrentBaseUrl(): String {
        return currentBaseUrl
    }

    fun isInitialized(): Boolean {
        return retrofit != null
    }
}