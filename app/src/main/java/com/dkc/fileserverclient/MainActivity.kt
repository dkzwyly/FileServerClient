// MainActivity.kt
package com.dkc.fileserverclient

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 RetrofitClient
        RetrofitClient.initialize(this)

        setContent {
            FileServerClientTheme {
                MainContent()
            }
        }
    }

    @Composable
    private fun MainContent() {
        var showConfigDialog by remember { mutableStateOf(false) }
        var showPreviewScreen by remember { mutableStateOf(false) }

        // 使用 viewModel() 函数获取 ViewModel
        val viewModel: FileViewModel = viewModel(
            factory = FileViewModelFactory(FileRepository(this))
        )

        val configManager = remember { ServerConfigManager(this) }
        val previewState by viewModel.previewState.collectAsState()

        // 修复预览状态监听逻辑
        LaunchedEffect(previewState) {
            println("DEBUG: 预览状态变化: $previewState")
            when (previewState) {
                is PreviewState.Loading -> {
                    println("DEBUG: 预览加载中...")
                }
                is PreviewState.ImageSuccess -> {
                    println("DEBUG: 图片预览成功，显示预览界面")
                    showPreviewScreen = true
                }
                is PreviewState.TextSuccess -> {
                    println("DEBUG: 文本预览成功，显示预览界面")
                    showPreviewScreen = true
                }
                is PreviewState.MediaSuccess -> {
                    println("DEBUG: 媒体预览成功，显示预览界面")
                    showPreviewScreen = true
                }
                is PreviewState.Error -> {
                    println("DEBUG: 预览错误: ${(previewState as PreviewState.Error).message}")
                    showPreviewScreen = false
                    val errorMessage = (previewState as PreviewState.Error).message
                    Toast.makeText(this@MainActivity, "预览错误: $errorMessage", Toast.LENGTH_LONG).show()
                }
                PreviewState.Idle -> {
                    println("DEBUG: 预览状态空闲")
                    showPreviewScreen = false
                }
            }
        }

        // 根据状态显示不同界面
        if (showPreviewScreen) {
            println("DEBUG: 显示预览界面")
            FilePreviewScreen(
                previewState = previewState,
                onBack = {
                    println("DEBUG: 返回文件列表")
                    showPreviewScreen = false
                    viewModel.resetPreviewState()
                    viewModel.clearSharedVideoPlayer() // 清理共享播放器
                }
            )
        } else {
            println("DEBUG: 显示文件浏览器")
            FileBrowserApp(
                viewModel = viewModel,
                configManager = configManager,
                onConfigClick = {
                    showConfigDialog = true
                },
                onPreviewFile = { file ->
                    println("DEBUG: 点击预览文件: ${file.name}")
                    // 处理文件预览
                    viewModel.previewFile(file)
                }
            )
        }

        // 服务器配置对话框
        if (showConfigDialog) {
            ServerConfigDialog(
                configManager = configManager,
                onDismiss = { showConfigDialog = false },
                onConfigSelected = { config ->
                    showConfigDialog = false
                    viewModel.loadFileList()
                    Toast.makeText(this@MainActivity, "服务器配置已更新", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理预览缓存
        // 注意：这里需要获取 ViewModel 实例来调用 clearPreviewCache
        // 由于 ViewModel 生命周期与 Activity 绑定，这里可以不清理
    }
}

/**
 * 服务器配置对话框
 */
@Composable
fun ServerConfigDialog(
    configManager: ServerConfigManager,
    onDismiss: () -> Unit,
    onConfigSelected: (ServerConfig) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "配置服务器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                ServerConfigScreen(
                    configManager = configManager,
                    onConfigSelected = onConfigSelected,
                    onBack = onDismiss
                )
            }
        }
    }
}