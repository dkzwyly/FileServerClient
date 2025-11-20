// EnhancedMediaPlayer.kt
package com.dkc.fileserverclient

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor
import androidx.media3.datasource.okhttp.OkHttpDataSource

/**
 * 增强的视频播放器，支持全屏、手势控制和自定义控制栏
 */
@Composable
fun EnhancedVideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }
    var showCustomControls by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var isLongPressing by remember { mutableStateOf(false) }

    // 保存播放状态
    val savedPosition = remember { mutableStateOf(0L) }
    val savedIsPlaying = remember { mutableStateOf(true) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true

            // 添加播放状态监听器
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> isPlaying = this@apply.isPlaying
                        Player.STATE_BUFFERING -> isPlaying = false
                        Player.STATE_ENDED -> isPlaying = false
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
        }
    }

    // 控制栏自动隐藏逻辑
    LaunchedEffect(showCustomControls) {
        if (showCustomControls) {
            delay(3000) // 3秒后自动隐藏控制栏
            showCustomControls = false
        }
    }

    // 监听长按状态变化，更新播放速度
    LaunchedEffect(isLongPressing) {
        if (isLongPressing) {
            // 长按期间双倍速
            playbackSpeed = 2.0f
            exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(playbackSpeed)
        } else {
            // 松开恢复原速
            playbackSpeed = 1.0f
            exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(playbackSpeed)
        }
    }

    DisposableEffect(videoUrl) {
        try {
            val dataSourceFactory = createMediaOkHttpDataSourceFactory()
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl)))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            println("DEBUG: 开始加载增强视频: $videoUrl")
        } catch (e: Exception) {
            onError("视频加载失败: ${e.message}")
            e.printStackTrace()
        }

        onDispose {
            // 保存播放状态
            savedPosition.value = exoPlayer.currentPosition
            savedIsPlaying.value = exoPlayer.isPlaying

            exoPlayer.release()
            println("DEBUG: 增强视频播放器已释放")
        }
    }

    // 恢复播放状态
    LaunchedEffect(Unit) {
        if (savedPosition.value > 0) {
            exoPlayer.seekTo(savedPosition.value)
            if (savedIsPlaying.value) {
                exoPlayer.play()
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // 双击暂停/播放
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                        showCustomControls = true
                    },
                    onTap = {
                        // 单击显示/隐藏自定义控制栏
                        showCustomControls = !showCustomControls
                    },
                    onLongPress = {
                        // 长按开始，设置长按状态为true
                        isLongPressing = true
                    }
                )
            }
            .pointerInput(Unit) {
                // 监听指针释放事件
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // 检查是否所有指针都已释放
                        val allPointersReleased = event.changes.all { !it.pressed }
                        if (allPointersReleased && isLongPressing) {
                            // 所有指针释放且之前是长按状态，恢复原速
                            isLongPressing = false
                        }
                    }
                }
            }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // 禁用默认控制栏，使用自定义的
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 自定义控制栏
        if (showCustomControls) {
            CustomControlsOverlay(
                player = exoPlayer,
                isPlaying = isPlaying,
                isFullscreen = isFullscreen,
                playbackSpeed = playbackSpeed,
                onPlayPause = {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                onSeekForward = {
                    val currentPosition = exoPlayer.currentPosition
                    val duration = exoPlayer.duration
                    val newPosition = (currentPosition + 10000).coerceAtMost(duration)
                    exoPlayer.seekTo(newPosition)
                },
                onSeekBackward = {
                    val currentPosition = exoPlayer.currentPosition
                    val newPosition = (currentPosition - 10000).coerceAtLeast(0)
                    exoPlayer.seekTo(newPosition)
                },
                onToggleFullscreen = {
                    isFullscreen = !isFullscreen
                    onFullscreenChange(isFullscreen)

                    // 保存当前播放状态
                    savedPosition.value = exoPlayer.currentPosition
                    savedIsPlaying.value = exoPlayer.isPlaying
                },
                onSpeedChange = { speed ->
                    playbackSpeed = speed
                    exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(speed)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 长按加速指示器
        if (isLongPressing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .size(80.dp, 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${playbackSpeed}x",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * 自定义控制栏覆盖层
 */
@Composable
fun CustomControlsOverlay(
    player: Player,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    playbackSpeed: Float,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSpeedSelector by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 底部控制栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                // 快退10秒 - 使用文本符号
                IconButton(onClick = onSeekBackward) {
                    Text(
                        text = "⏪",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                // 播放/暂停 - 使用文本符号
                IconButton(onClick = onPlayPause) {
                    Text(
                        text = if (isPlaying) "⏸" else "▶",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                // 快进10秒 - 使用文本符号
                IconButton(onClick = onSeekForward) {
                    Text(
                        text = "⏩",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                // 播放速度选择
                IconButton(onClick = { showSpeedSelector = !showSpeedSelector }) {
                    Text(
                        text = "${playbackSpeed}x",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // 全屏切换
                IconButton(onClick = onToggleFullscreen) {
                    Text(
                        text = if (isFullscreen) "📱" else "🔲",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // 播放速度选择器
        if (showSpeedSelector) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        IconButton(
                            onClick = {
                                onSpeedChange(speed)
                                showSpeedSelector = false
                            }
                        ) {
                            Text(
                                text = "${speed}x",
                                color = if (playbackSpeed == speed) Color.Yellow else Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 增强的音频播放器
 */
@Composable
fun EnhancedAudioPlayer(
    audioUrl: String,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var showCustomControls by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            })
        }
    }

    // 控制栏自动隐藏逻辑
    LaunchedEffect(showCustomControls) {
        if (showCustomControls) {
            delay(3000)
            showCustomControls = false
        }
    }

    DisposableEffect(audioUrl) {
        try {
            val dataSourceFactory = createMediaOkHttpDataSourceFactory()
            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl)))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            println("DEBUG: 开始加载增强音频: $audioUrl")
        } catch (e: Exception) {
            onError("音频加载失败: ${e.message}")
            e.printStackTrace()
        }

        onDispose {
            exoPlayer.release()
            println("DEBUG: 增强音频播放器已释放")
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showCustomControls = !showCustomControls
                    }
                )
            }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // 使用自定义控制栏
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 音频控制栏
        if (showCustomControls) {
            AudioControlsOverlay(
                player = exoPlayer,
                isPlaying = isPlaying,
                onPlayPause = {
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    } else {
                        exoPlayer.play()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
            )
        }
    }
}

/**
 * 音频控制栏
 */
@Composable
fun AudioControlsOverlay(
    player: Player,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onPlayPause) {
            Text(
                text = if (isPlaying) "⏸" else "▶",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

// 以下工具函数保持不变
fun createMediaOkHttpDataSourceFactory(): OkHttpDataSource.Factory {
    val okHttpClient = createUnsafeOkHttpClient()
    return OkHttpDataSource.Factory(okHttpClient)
}

private fun createUnsafeOkHttpClient(): OkHttpClient {
    try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

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