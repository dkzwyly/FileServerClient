// HealthResponse.kt
package com.dkc.fileserverclient
data class HealthResponse(
    val status: String = "",
    val timestamp: String = "",
    val activeConnections: Int = 0,
    val uptime: String = ""
)