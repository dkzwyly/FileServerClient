package com.dkc.fileserverclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserApp(
    viewModel: FileViewModel,
    configManager: ServerConfigManager,
    onConfigClick: () -> Unit,
    onPreviewFile: (FileInfoModel) -> Unit // 新增预览回调
) {
    val fileListState by viewModel.fileListState.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val currentConfig by remember { mutableStateOf(configManager.getCurrentConfig()) }

    // 检查是否已配置服务器
    if (currentConfig == null || !RetrofitClient.isInitialized()) {
        // 显示配置提示
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("⚙️", style = MaterialTheme.typography.displayMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "未配置服务器",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "请先配置服务器地址以连接文件服务器",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onConfigClick) {
                Text("配置服务器")
            }
        }
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "文件服务器",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onConfigClick) {
                        Text("⚙️") // 使用文本图标代替设置图标
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO: 处理上传 */ }
            ) {
                Text("上传")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 当前服务器信息
            currentConfig?.let { config ->
                CurrentServerInfo(config)
            }

            // 服务器状态
            serverStatus?.let { status ->
                ServerStatusSurface(status)
            }

            // 文件列表
            when (fileListState) {
                is FileListState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is FileListState.Success -> {
                    val data = (fileListState as FileListState.Success).data
                    FileListView(
                        fileListResponse = data,
                        onDirectoryClick = { path ->
                            viewModel.loadFileList(path)
                        },
                        onFileClick = { file ->
                            // 处理文件点击：可预览文件调用预览，否则下载
                            if (file.isImage || file.isText || file.isMedia) {
                                onPreviewFile(file)
                            } else {
                                // TODO: 处理文件下载
                            }
                        },
                        onNavigateUp = {
                            viewModel.navigateToParent()
                        }
                    )
                }
                is FileListState.Error -> {
                    val message = (fileListState as FileListState.Error).message
                    ErrorView(
                        message = message,
                        onRetry = { viewModel.loadFileList() }
                    )
                }
            }
        }
    }
}

@Composable
fun CurrentServerInfo(config: ServerConfig) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 根据协议显示不同的图标
            val protocolIcon = if (config.protocol == "https") "🔒" else "🌐"
            Text(protocolIcon, modifier = Modifier.padding(end = 8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "当前服务器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    config.baseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            // 显示协议类型
            Text(
                config.protocol.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = if (config.protocol == "https") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
fun ServerStatusSurface(status: ServerStatus) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "服务器状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("运行状态")
                Text(
                    if (status.isRunning) "运行中" else "已停止",
                    color = if (status.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("活跃连接")
                Text("${status.activeConnections}")
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("总请求数")
                Text("${status.totalRequests}")
            }
        }
    }
}

@Composable
fun FileListView(
    fileListResponse: FileListResponse,
    onDirectoryClick: (String) -> Unit,
    onFileClick: (FileInfoModel) -> Unit,
    onNavigateUp: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp)
    ) {
        // 当前路径显示和返回按钮
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (fileListResponse.parentPath.isNotEmpty()) {
                    IconButton(onClick = onNavigateUp) {
                        Text("←") // 使用文本箭头代替图标
                    }
                }
                Text(
                    "当前路径: /${fileListResponse.currentPath}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 目录列表
        items(fileListResponse.directories) { directory ->
            DirectorySurface(
                directory = directory,
                onClick = { onDirectoryClick(directory.path) }
            )
        }

        // 文件列表
        items(fileListResponse.files) { file ->
            FileSurface(
                file = file,
                onClick = { onFileClick(file) }
            )
        }
    }
}

@Composable
fun DirectorySurface(directory: DirectoryInfoModel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📁")
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                directory.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun FileSurface(file: FileInfoModel, onClick: () -> Unit) {
    Surface(
        onClick = {
            println("DEBUG: 文件被点击: ${file.name}")
            println("DEBUG: 文件信息 - 扩展名: ${file.extension}, isImage: ${file.isImage}, isText: ${file.isText}, isMedia: ${file.isMedia}")
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 根据文件类型显示不同文本表情，添加预览标识
            val icon = when {
                file.isVideo -> "🎬"
                file.isAudio -> "🎵"
                file.isImage -> "🖼️"
                file.isText -> "📝"
                else -> "📄"
            }

            Text(icon)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${file.sizeFormatted} • ${file.lastModified}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 显示预览提示
                if (file.isImage || file.isText || file.isMedia) {
                    Text(
                        "点击预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        "点击下载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = { /* TODO: 下载操作 */ },
                modifier = Modifier.height(36.dp)
            ) {
                Text("下载")
            }
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("❌", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "加载失败",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}