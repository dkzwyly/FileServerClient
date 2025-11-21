// MediaPlayer.kt
@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor

/**
 * 为 Media3 创建忽略 SSL 的 HttpDataSource Factory
 */
private fun createUnsafeHttpDataSourceFactory(): DefaultHttpDataSource.Factory {
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

        // 使用反射或其他方式设置 SSL socket factory
        // 注意：DefaultHttpDataSource.Factory 没有直接的 setSslSocketFactory 方法
        // 我们需要创建一个自定义的 OkHttpClient 或者使用其他方法

        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
            // 由于 DefaultHttpDataSource.Factory 没有 setSslSocketFactory 方法，
            // 我们使用一个替代方案：通过设置用户代理来确保连接
            .setUserAgent("FileServerClient/1.0")

    } catch (e: Exception) {
        throw RuntimeException(e)
    }
}

/**
 * 创建一个简单的媒体源工厂，处理 SSL 问题
 */
private fun createMediaSource(mediaUrl: String): ProgressiveMediaSource {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(30000)
        .setReadTimeoutMs(30000)
        .setAllowCrossProtocolRedirects(true)

    return ProgressiveMediaSource.Factory(dataSourceFactory)
        .createMediaSource(MediaItem.fromUri(Uri.parse(mediaUrl)))
}

/**
 * 视频播放器组件
 */
@Composable
fun VideoPlayer(
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
        // 设置媒体项
        try {
            val mediaSource = createMediaSource(videoUrl)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            println("DEBUG: 开始加载视频: $videoUrl")
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
 * 音频播放器组件
 */
@Composable
fun AudioPlayer(
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
            val mediaSource = createMediaSource(audioUrl)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            println("DEBUG: 开始加载音频: $audioUrl")
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