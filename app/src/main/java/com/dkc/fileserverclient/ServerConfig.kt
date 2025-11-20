// ServerConfig.kt
package com.dkc.fileserverclient
data class ServerConfig(
    val baseUrl: String,
    val protocol: String, // "http" or "https"
    val ip: String,
    val port: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromInput(input: String): ServerConfig? {
            return try {
                var cleanedInput = input.trim()
                var protocol = "http" // 默认协议

                // 检查并提取协议
                if (cleanedInput.startsWith("http://")) {
                    protocol = "http"
                    cleanedInput = cleanedInput.removePrefix("http://")
                } else if (cleanedInput.startsWith("https://")) {
                    protocol = "https"
                    cleanedInput = cleanedInput.removePrefix("https://")
                }

                // 分割IP和端口
                val parts = cleanedInput.split(":")
                if (parts.size != 2) return null

                val ip = parts[0]
                val port = parts[1].toIntOrNull() ?: return null

                ServerConfig(
                    baseUrl = "$protocol://$ip:$port",
                    protocol = protocol,
                    ip = ip,
                    port = port
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}