// UploadResponse.kt
package com.dkc.fileserverclient
data class UploadResponse(
    val success: Boolean = false,
    val message: String = "",
    val files: List<String> = emptyList(),
    val totalSize: Long = 0,
    val totalSizeFormatted: String = ""
)