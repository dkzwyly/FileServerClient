// FilePreviewScreen.kt
package com.dkc.fileserverclient

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    previewState: PreviewState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFullscreen by remember { mutableStateOf(false) }

    // 处理全屏状态下的返回按钮
    val handleBack = {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            onBack()
        }
    }

    // 全屏模式下隐藏所有UI，只显示视频
    if (isFullscreen && previewState is PreviewState.MediaSuccess && previewState.mimeType.startsWith("video")) {
        FullscreenVideoPlayer(
            videoUrl = previewState.mediaUrl,
            onExitFullscreen = { isFullscreen = false },
            onError = { error ->
                // 处理错误
                println("DEBUG: 全屏视频播放错误: $error")
            }
        )
        return
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "文件预览",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = handleBack) {
                            Text("←")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (previewState) {
                is PreviewState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is PreviewState.ImageSuccess -> {
                    ImagePreview(
                        imageUrl = previewState.imageUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is PreviewState.TextSuccess -> {
                    TextPreview(
                        textContent = previewState.content,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is PreviewState.MediaSuccess -> {
                    MediaPreview(
                        mediaUrl = previewState.mediaUrl,
                        mimeType = previewState.mimeType,
                        isFullscreen = isFullscreen,
                        onFullscreenChange = { fullscreen ->
                            isFullscreen = fullscreen
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is PreviewState.Error -> {
                    ErrorPreview(
                        message = previewState.message,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                PreviewState.Idle -> {
                    // 空状态
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("选择文件进行预览")
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreview(
    imageUrl: String,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 图片加载状态指示
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("加载图片中...")
                    Text(
                        text = "URL: $imageUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        // 图片显示区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (hasError) {
                // 加载失败显示错误信息
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(text = "❌", style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "图片加载失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "URL: $imageUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            isLoading = true
                            hasError = false
                            errorMessage = ""
                        }
                    ) {
                        Text("重试")
                    }
                }
            } else {
                // 使用我们自定义的安全 AsyncImage 加载图片
                SafeAsyncImage(
                    model = imageUrl,
                    contentDescription = "预览图片",
                    modifier = Modifier.fillMaxSize(),
                    onLoading = {
                        isLoading = true
                        hasError = false
                        println("DEBUG: 图片加载中: $imageUrl")
                    },
                    onSuccess = {
                        isLoading = false
                        hasError = false
                        println("DEBUG: 图片加载成功: $imageUrl")
                    },
                    onError = { state ->
                        isLoading = false
                        hasError = true
                        errorMessage = state.result.throwable.message ?: "未知错误"
                        println("DEBUG: 图片加载失败: $imageUrl, 错误: $errorMessage")
                        state.result.throwable.printStackTrace()
                    }
                )
            }
        }

        // 调试信息显示
        if (!isLoading && !hasError) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "✅ 图片加载成功",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "URL: $imageUrl",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TextPreview(
    textContent: TextPreviewResponse,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp)
    ) {
        Text(
            text = "文本预览 - ${textContent.fileName}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (textContent.truncated) {
            Text(
                text = "⚠️ 文件过大，只显示部分内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Text(
            text = "大小: ${textContent.size} 字节 | 编码: ${textContent.encoding}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            Text(
                text = textContent.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun MediaPreview(
    mediaUrl: String,
    mimeType: String,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val isVideo = mimeType.startsWith("video")
    val isAudio = mimeType.startsWith("audio")

    Column(
        modifier = modifier
    ) {
        // 文件信息头
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = if (isVideo) "🎬 视频播放" else "🎵 音频播放",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "格式: $mimeType",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "URL: $mediaUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 添加操作提示
                if (isVideo) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "操作提示: 双击暂停/播放 • 左侧快退 • 右侧快进 • 点击控制栏全屏",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // 添加播放状态信息
                if (isLoading) {
                    Text(
                        text = "状态: 加载中...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (hasError) {
                    Text(
                        text = "状态: 加载失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "状态: 加载成功",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 播放器区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (hasError) {
                // 错误状态
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "❌",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isVideo) "视频播放失败" else "音频播放失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "请检查媒体文件格式和网络连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            // 重试加载
                            isLoading = true
                            hasError = false
                            errorMessage = ""
                        }
                    ) {
                        Text("重试")
                    }
                }
            } else if (isLoading) {
                // 加载状态
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = if (isVideo) "加载视频中..." else "加载音频中...")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "正在处理 HTTPS 安全连接...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 播放器区域
                if (isVideo) {
                    EnhancedVideoPlayer(
                        videoUrl = mediaUrl,
                        modifier = Modifier.fillMaxSize(),
                        onError = { error ->
                            hasError = true
                            errorMessage = error
                            println("DEBUG: 视频播放错误: $error")
                        },
                        onFullscreenChange = { fullscreen ->
                            onFullscreenChange(fullscreen)
                        }
                    )
                } else if (isAudio) {
                    // 音频播放器布局
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 音频图标和标题
                        Text(
                            text = "🎵",
                            style = MaterialTheme.typography.displayLarge,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        Text(
                            text = "音频播放",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // 音频播放器控件
                        EnhancedAudioPlayer(
                            audioUrl = mediaUrl,
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(80.dp),
                            onError = { error ->
                                hasError = true
                                errorMessage = error
                                println("DEBUG: 音频播放错误: $error")
                            }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 音频播放提示
                        Text(
                            text = "使用上方的控件播放音频",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 未知媒体类型
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "❓",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "未知媒体类型",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "MIME类型: $mimeType",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "请在外部应用中打开此文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 控制按钮区域
        if (!isLoading && !hasError) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 媒体控制信息
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "✅ 媒体加载成功",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (isVideo) {
                            Text(
                                text = "点击控制栏全屏按钮进入全屏模式",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // 下载按钮
                    Button(
                        onClick = {
                            // TODO: 实现下载功能
                            println("DEBUG: 下载媒体文件: $mediaUrl")
                        }
                    ) {
                        Text("下载文件")
                    }
                }
            }
        }
    }

    // 自动开始加载媒体
    LaunchedEffect(mediaUrl) {
        // 模拟加载过程，让用户看到加载状态
        delay(500)
        isLoading = false
        println("DEBUG: 媒体预览初始化完成: $mediaUrl")
    }

    // 处理媒体URL变化
    LaunchedEffect(mediaUrl) {
        if (hasError) {
            // 如果URL变化且有错误状态，重置状态
            isLoading = true
            hasError = false
            errorMessage = ""
        }
    }
}

/**
 * 全屏视频播放器 - 修复版
 */
@Composable
fun FullscreenVideoPlayer(
    videoUrl: String,
    onExitFullscreen: () -> Unit,
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }

    // 使用DisposableEffect来管理屏幕方向和全屏模式
    DisposableEffect(Unit) {
        val activity = context as? Activity

        // 进入全屏时锁定横屏并隐藏状态栏
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 真正的沉浸式全屏 - 隐藏状态栏和导航栏
        activity?.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
        }

        onDispose {
            // 退出全屏时恢复竖屏并显示状态栏
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // 控制栏自动隐藏
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // 单击切换控制栏显示
                        showControls = !showControls
                    }
                )
            }
    ) {
        EnhancedVideoPlayer(
            videoUrl = videoUrl,
            modifier = Modifier.fillMaxSize(),
            onError = onError,
            onFullscreenChange = { fullscreen ->
                if (!fullscreen) {
                    onExitFullscreen()
                }
            }
        )

        // 全屏模式下的退出按钮 - 只在显示控制栏时显示
        if (showControls) {
            IconButton(
                onClick = onExitFullscreen,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = "← 退出全屏",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // 操作提示 - 只在隐藏控制栏时显示
        if (!showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            ) {
                Text(
                    text = "点击屏幕显示控制栏",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ErrorPreview(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "❌", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "预览失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    // 这里可以添加重试逻辑
                }
            ) {
                Text("返回")
            }
        }
    }
}