// MediaPlayerWithOkHttp.kt
package com.dkc.fileserverclient

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor

/**
 * 为 Media3 创建使用 OkHttp 的 DataSource Factory
 */
private fun createOkHttpDataSourceFactory(): OkHttpDataSource.Factory {
    val okHttpClient = createUnsafeOkHttpClient()
    return OkHttpDataSource.Factory(okHttpClient)
}

/**
 * 创建忽略 SSL 的 OkHttpClient
 */
private fun createUnsafeOkHttpClient(): OkHttpClient {
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
                println("DEBUG: Media3发送请求: ${request.url}")
                val response = chain.proceed(request)
                println("DEBUG: Media3收到响应: ${response.code} - ${response.message}")
                response
            }
            .build()
    } catch (e: Exception) {
        throw RuntimeException(e)
    }
}

/**
 * 使用 OkHttp DataSource 的视频播放器组件
 */
@Composable
fun VideoPlayerWithOkHttp(
    videoUrl: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }

    DisposableEffect(videoUrl) {
        try {
            val dataSourceFactory = createOkHttpDataSourceFactory()
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            println("DEBUG: 开始加载视频 (OkHttp): $videoUrl")
        } catch (e: Exception) {
            onError("视频加载失败: ${e.message}")
            e.printStackTrace()
        }

        onDispose {
            exoPlayer.release()
            println("DEBUG: 视频播放器已释放")
        }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier
    )
}

/**
 * 使用 OkHttp DataSource 的音频播放器组件
 */
@Composable
fun AudioPlayerWithOkHttp(
    audioUrl: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }

    DisposableEffect(audioUrl) {
        try {
            val dataSourceFactory = createOkHttpDataSourceFactory()
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            println("DEBUG: 开始加载音频 (OkHttp): $audioUrl")
        } catch (e: Exception) {
            onError("音频加载失败: ${e.message}")
            e.printStackTrace()
        }

        onDispose {
            exoPlayer.release()
            println("DEBUG: 音频播放器已释放")
        }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = modifier
    )
}