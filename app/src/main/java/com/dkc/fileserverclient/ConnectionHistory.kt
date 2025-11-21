package com.dkc.fileserverclient

data class ConnectionHistory(
    val url: String = "",
    val lastConnected: Long = System.currentTimeMillis(),
    val protocol: String = "HTTP"
)