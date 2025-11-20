// CoilImageLoader.kt
package com.dkc.fileserverclient

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor

/**
 * 创建自定义的 ImageLoader，忽略 SSL 证书错误
 */
@Composable
fun rememberCustomImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader.Builder(context)
            .okHttpClient {
                // 使用与 Retrofit 相同的忽略 SSL 的 OkHttpClient
                createUnsafeOkHttpClientForCoil()
            }
            .build()
    }
}

/**
 * 使用自定义 ImageLoader 的 AsyncImage
 */
@Composable
fun SafeAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    imageLoader: ImageLoader = rememberCustomImageLoader(),
    onLoading: ((coil.compose.AsyncImagePainter.State.Loading) -> Unit)? = null,
    onSuccess: ((coil.compose.AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((coil.compose.AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        imageLoader = imageLoader,
        onLoading = onLoading,
        onSuccess = onSuccess,
        onError = onError
    )
}

/**
 * 为 Coil 创建忽略 SSL 的 OkHttpClient
 */
private fun createUnsafeOkHttpClientForCoil(): OkHttpClient {
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

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier(hostnameVerifier)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                println("DEBUG: Coil发送请求: ${request.url}")
                val response = chain.proceed(request)
                println("DEBUG: Coil收到响应: ${response.code} - ${response.message}")
                response
            }
            .build()
    } catch (e: Exception) {
        throw RuntimeException(e)
    }
}