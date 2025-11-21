// EnhancedMediaPlayer.kt
@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient



import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
    onFullscreenChange: (Boolean) -> Unit = {},
    externalPlayer: ExoPlayer? = null, // 支持外部传入的播放器实例
    isFullscreen: Boolean = false // 新增：明确知道当前是否全屏
) {
    val context = LocalContext.current
    val viewModel: FileViewModel = viewModel()
    var showCustomControls by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var isLongPressing by remember { mutableStateOf(false) }
    var isPlayerReady by remember { mutableStateOf(false) }
    var hasInitialSeek by remember { mutableStateOf(false) } // 新增：标记是否已经初始定位

    // 使用 remember 保存播放器实例，或者使用外部传入的播放器
    val exoPlayer = remember(externalPlayer, videoUrl) {
        externalPlayer ?: ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true

            // 添加播放状态监听器
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            isPlaying = this@apply.isPlaying
                            isPlayerReady = true

                            // 只在第一次准备好时恢复播放位置
                            if (!hasInitialSeek && viewModel.shouldRestoreState(videoUrl)) {
                                val savedState = viewModel.videoPlayerState
                                if (savedState.currentPosition > 0) {
                                    seekTo(savedState.currentPosition)
                                    if (savedState.isPlaying) {
                                        play()
                                    } else {
                                        pause()
                                    }
                                    playbackSpeed = savedState.playbackSpeed
                                    playbackParameters = playbackParameters.withSpeed(playbackSpeed)
                                    hasInitialSeek = true
                                    println("DEBUG: 初始定位到位置: ${savedState.currentPosition}")
                                }
                            }
                        }
                        Player.STATE_BUFFERING -> isPlaying = false
                        Player.STATE_ENDED -> isPlaying = false
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    // 保存状态到ViewModel
                    if (isPlayerReady) {
                        viewModel.updateVideoState(
                            VideoPlayerState(
                                currentPosition = this@apply.currentPosition,
                                isPlaying = playing,
                                playbackSpeed = playbackSpeed,
                                videoUrl = videoUrl
                            )
                        )
                    }
                }
            })
        }
    }

    // 如果使用外部播放器，需要设置一些基本属性
    LaunchedEffect(externalPlayer) {
        externalPlayer?.let { player ->
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.playWhenReady = true
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
            if (isPlayerReady) {
                viewModel.updateVideoState(
                    VideoPlayerState(
                        currentPosition = exoPlayer.currentPosition,
                        isPlaying = isPlaying,
                        playbackSpeed = playbackSpeed,
                        videoUrl = videoUrl
                    )
                )
            }
        } else {
            // 松开恢复原速
            playbackSpeed = 1.0f
            exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(playbackSpeed)
            if (isPlayerReady) {
                viewModel.updateVideoState(
                    VideoPlayerState(
                        currentPosition = exoPlayer.currentPosition,
                        isPlaying = isPlaying,
                        playbackSpeed = playbackSpeed,
                        videoUrl = videoUrl
                    )
                )
            }
        }
    }

    // 初始化媒体源（只有在没有使用外部播放器或者视频URL变化时才执行）
    LaunchedEffect(videoUrl) {
        // 如果使用外部播放器，且播放器已经准备好，则不需要重新设置媒体源
        if (externalPlayer != null && externalPlayer.playbackState == Player.STATE_READY) {
            isPlayerReady = true
            // 确保初始定位只执行一次
            if (!hasInitialSeek && viewModel.shouldRestoreState(videoUrl)) {
                val savedState = viewModel.videoPlayerState
                if (savedState.currentPosition > 0) {
                    externalPlayer.seekTo(savedState.currentPosition)
                    hasInitialSeek = true
                    println("DEBUG: 外部播放器初始定位到位置: ${savedState.currentPosition}")
                }
            }
            return@LaunchedEffect
        }

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
    }

    // 定期保存播放位置
    LaunchedEffect(isPlayerReady) {
        if (isPlayerReady) {
            while (true) {
                delay(1000) // 每秒保存一次
                if (isPlayerReady) {
                    viewModel.updateVideoState(
                        VideoPlayerState(
                            currentPosition = exoPlayer.currentPosition,
                            isPlaying = isPlaying,
                            playbackSpeed = playbackSpeed,
                            videoUrl = videoUrl
                        )
                    )
                }
            }
        }
    }

    // 清理播放器（只有在内部创建的播放器才需要释放）
    DisposableEffect(Unit) {
        onDispose {
            // 保存最终状态
            if (isPlayerReady) {
                viewModel.updateVideoState(
                    VideoPlayerState(
                        currentPosition = exoPlayer.currentPosition,
                        isPlaying = exoPlayer.isPlaying,
                        playbackSpeed = playbackSpeed,
                        videoUrl = videoUrl
                    )
                )
            }
            // 只有内部创建的播放器才释放
            if (externalPlayer == null) {
                exoPlayer.release()
                println("DEBUG: 增强视频播放器已释放")
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
                        if (isPlayerReady) {
                            viewModel.updateVideoState(
                                VideoPlayerState(
                                    currentPosition = exoPlayer.currentPosition,
                                    isPlaying = exoPlayer.isPlaying,
                                    playbackSpeed = playbackSpeed,
                                    videoUrl = videoUrl
                                )
                            )
                        }
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

                    // 关键优化：防止重新附加时显示第一帧
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)

                    // 如果是全屏模式，调整布局参数
                    if (isFullscreen) {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { playerView ->
                // 更新时确保播放器正确设置
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            }
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
                    if (isPlayerReady) {
                        viewModel.updateVideoState(
                            VideoPlayerState(
                                currentPosition = exoPlayer.currentPosition,
                                isPlaying = exoPlayer.isPlaying,
                                playbackSpeed = playbackSpeed,
                                videoUrl = videoUrl
                            )
                        )
                    }
                    showCustomControls = true
                },
                onSeekForward = {
                    val currentPosition = exoPlayer.currentPosition
                    val duration = exoPlayer.duration
                    val newPosition = (currentPosition + 10000).coerceAtMost(duration)
                    exoPlayer.seekTo(newPosition)
                    if (isPlayerReady) {
                        viewModel.updateVideoState(
                            VideoPlayerState(
                                currentPosition = newPosition,
                                isPlaying = isPlaying,
                                playbackSpeed = playbackSpeed,
                                videoUrl = videoUrl
                            )
                        )
                    }
                },
                onSeekBackward = {
                    val currentPosition = exoPlayer.currentPosition
                    val newPosition = (currentPosition - 10000).coerceAtLeast(0)
                    exoPlayer.seekTo(newPosition)
                    if (isPlayerReady) {
                        viewModel.updateVideoState(
                            VideoPlayerState(
                                currentPosition = newPosition,
                                isPlaying = isPlaying,
                                playbackSpeed = playbackSpeed,
                                videoUrl = videoUrl
                            )
                        )
                    }
                },
                onToggleFullscreen = {
                    onFullscreenChange(!isFullscreen)

                    // 保存当前播放状态
                    if (isPlayerReady) {
                        viewModel.updateVideoState(
                            VideoPlayerState(
                                currentPosition = exoPlayer.currentPosition,
                                isPlaying = exoPlayer.isPlaying,
                                playbackSpeed = playbackSpeed,
                                videoUrl = videoUrl
                            )
                        )
                    }
                },
                onSpeedChange = { speed: Float ->
                    playbackSpeed = speed
                    exoPlayer.playbackParameters = exoPlayer.playbackParameters.withSpeed(speed)
                    if (isPlayerReady) {
                        viewModel.updateVideoState(
                            VideoPlayerState(
                                currentPosition = exoPlayer.currentPosition,
                                isPlaying = isPlaying,
                                playbackSpeed = speed,
                                videoUrl = videoUrl
                            )
                        )
                    }
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

// ... 其他代码保持不变（CustomControlsOverlay, EnhancedAudioPlayer 等）
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

// 工具函数
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