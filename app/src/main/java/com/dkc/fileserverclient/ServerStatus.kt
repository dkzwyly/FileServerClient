// ServerStatus.kt
package com.dkc.fileserverclient
data class ServerStatus(
    val isRunning: Boolean = false,
    val activeConnections: Int = 0,
    val rootPath: String = "",
    val httpPort: Int = 0,
    val httpsPort: Int = 0,
    val quicPort: Int = 0,
    val quicEnabled: Boolean = false,
    val startTime: String = "",
    val totalRequests: Long = 0,
    val uptime: Long = 0
)