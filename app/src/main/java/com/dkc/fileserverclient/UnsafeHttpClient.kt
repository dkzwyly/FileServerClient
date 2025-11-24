package com.dkc.fileserverclient

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object UnsafeHttpClient {

    /**
     * 创建不验证SSL证书的OkHttpClient
     * 注意：仅用于开发环境或可信的内部网络
     */
    fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            // 创建信任所有证书的 TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    // 信任所有客户端证书
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    // 信任所有服务器证书
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return arrayOf()
                }
            })

            // 创建 SSLContext 使用信任所有证书的 TrustManager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 创建不验证主机名的 HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException("创建不安全HTTP客户端失败", e)
        }
    }
}