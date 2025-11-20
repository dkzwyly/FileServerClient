// ServerConfigScreen.kt
package com.dkc.fileserverclient

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(
    configManager: ServerConfigManager,
    onConfigSelected: (ServerConfig) -> Unit,
    onBack: () -> Unit
) {
    var serverInput by remember { mutableStateOf(TextFieldValue()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val history by remember { mutableStateOf(configManager.getHistory()) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "服务器配置",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // 输入区域
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "服务器地址",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = serverInput,
                        onValueChange = {
                            serverInput = it
                            errorMessage = null
                        },
                        label = { Text("服务器地址 (例如: http://192.168.1.100:8080)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // 错误消息
                    errorMessage?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            val input = serverInput.text.trim()
                            if (input.isEmpty()) {
                                errorMessage = "请输入服务器地址"
                                return@Button
                            }

                            val config = ServerConfig.fromInput(input)
                            if (config == null) {
                                errorMessage = "格式错误，请使用 http://IP:端口 或 https://IP:端口 格式"
                                return@Button
                            }

                            // 保存配置并通知
                            configManager.saveCurrentConfig(config)
                            RetrofitClient.updateBaseUrl(config.baseUrl)
                            onConfigSelected(config)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("连接服务器")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 历史记录
            if (history.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📚", modifier = Modifier.padding(end = 8.dp))
                            Text(
                                "历史记录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn {
                            items(history) { config ->
                                HistoryItem(
                                    config = config,
                                    onClick = {
                                        configManager.saveCurrentConfig(config)
                                        RetrofitClient.updateBaseUrl(config.baseUrl)
                                        onConfigSelected(config)
                                    },
                                    onDelete = {
                                        configManager.removeFromHistory(config)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 提示信息
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "使用说明",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• 必须包含协议头: http:// 或 https://\n" +
                                "• 输入格式: 协议://IP地址:端口\n" +
                                "• HTTP示例: http://192.168.1.100:8080\n" +
                                "• HTTPS示例: https://192.168.1.100:8443\n" +
                                "• 支持自定义端口",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItem(
    config: ServerConfig,  // 这里改为 ServerConfig，不是 History
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    config.baseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "协议: ${config.protocol.uppercase()} | IP: ${config.ip} | 端口: ${config.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
            ) {
                Text("×")
            }
        }
    }
}