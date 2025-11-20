package com.dkc.fileserverclient

// 文本预览响应
data class TextPreviewResponse(
    val type: String = "",
    val fileName: String = "",
    val content: String = "",
    val encoding: String = "",
    val size: Int = 0,
    val truncated: Boolean = false
)

// 预览状态
sealed class PreviewState {
    object Idle : PreviewState()
    object Loading : PreviewState()
    data class ImageSuccess(val imageUrl: String) : PreviewState()
    data class TextSuccess(val content: TextPreviewResponse) : PreviewState()
    data class MediaSuccess(val mediaUrl: String, val mimeType: String) : PreviewState()
    data class Error(val message: String) : PreviewState()
}