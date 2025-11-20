package com.dkc.fileserverclient

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 视频播放器状态，用于保存和恢复播放状态
 */
data class VideoPlayerState(
    val currentPosition: Long = 0L,
    val isPlaying: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val videoUrl: String = ""
)

class FileViewModel(private val repository: FileRepository) : ViewModel() {

    private val _fileListState = MutableStateFlow<FileListState>(FileListState.Loading)
    val fileListState: StateFlow<FileListState> = _fileListState.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerStatus?>(null)
    val serverStatus: StateFlow<ServerStatus?> = _serverStatus.asStateFlow()

    // 预览状态
    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    // 视频播放器状态
    var videoPlayerState by mutableStateOf(VideoPlayerState())
        private set

    private var currentPath = ""

    init {
        loadFileList()
        loadServerStatus()
    }

    // FileViewModel.kt - 在 loadFileList 方法中添加调试信息
    fun loadFileList(path: String = "") {
        viewModelScope.launch {
            _fileListState.value = FileListState.Loading
            currentPath = path

            // 添加调试日志
            println("DEBUG: 加载文件列表 - 路径: '$path'")

            try {
                val fileListResponse = repository.getFileList(path)
                _fileListState.value = FileListState.Success(fileListResponse)

                // 添加成功日志
                println("DEBUG: 文件列表加载成功，目录数: ${fileListResponse.directories.size}, 文件数: ${fileListResponse.files.size}")
            } catch (e: Exception) {
                _fileListState.value = FileListState.Error(e.message ?: "未知错误")

                // 添加错误日志
                println("DEBUG: 文件列表加载失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun navigateToParent() {
        val parentPath = when (val state = _fileListState.value) {
            is FileListState.Success -> state.data.parentPath
            else -> ""
        }
        loadFileList(parentPath)
    }

    fun uploadFiles(uris: List<Uri>) {
        viewModelScope.launch {
            _uploadState.value = UploadState.Uploading
            try {
                // 直接获取上传响应，不再使用 Result 包装
                val uploadResponse = repository.uploadFiles(currentPath, uris)
                _uploadState.value = UploadState.Success(uploadResponse)
                // 刷新文件列表
                loadFileList(currentPath)
            } catch (e: Exception) {
                _uploadState.value = UploadState.Error(e.message ?: "上传失败")
            }
        }
    }

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
            try {
                repository.createDirectory(fullPath)
                loadFileList(currentPath)
            } catch (e: Exception) {
                // 可以在这里处理错误，比如显示 Snackbar
                println("创建目录失败: ${e.message}")
            }
        }
    }

    fun downloadFile(filePath: String, destinationFile: java.io.File) {
        viewModelScope.launch {
            try {
                // 直接下载文件，不再使用 Result 包装
                repository.downloadFile(filePath, destinationFile)
                // 下载成功，可以在这里处理成功逻辑
                println("文件下载成功: ${destinationFile.absolutePath}")
            } catch (e: Exception) {
                // 下载失败，处理错误
                println("文件下载失败: ${e.message}")
            }
        }
    }

    private fun loadServerStatus() {
        viewModelScope.launch {
            try {
                // 直接获取服务器状态，不再使用 Result 包装
                val status = repository.getServerStatus()
                _serverStatus.value = status
            } catch (e: Exception) {
                // 可以忽略服务器状态加载失败，或者记录日志
                println("加载服务器状态失败: ${e.message}")
            }
        }
    }

    /**
     * 预览文件 - 根据文件类型自动选择预览方式
     */
// 在 FileViewModel 的 previewFile 方法中添加详细日志
    fun previewFile(file: FileInfoModel) {
        viewModelScope.launch {
            println("DEBUG: 开始预览文件: ${file.name}, 路径: ${file.path}")
            println("DEBUG: 文件类型 - 图片: ${file.isImage}, 文本: ${file.isText}, 媒体: ${file.isMedia}")
            println("DEBUG: 文件扩展名: ${file.extension}")

            _previewState.value = PreviewState.Loading

            try {
                when {
                    file.isImage -> {
                        println("DEBUG: 处理图片预览")
                        previewImage(file)
                    }
                    file.isText -> {
                        println("DEBUG: 处理文本预览")
                        previewText(file)
                    }
                    file.isMedia -> {
                        println("DEBUG: 处理媒体预览")
                        previewMedia(file)
                    }
                    else -> {
                        println("DEBUG: 不支持预览此文件类型")
                        _previewState.value = PreviewState.Error("不支持预览此文件类型")
                    }
                }
            } catch (e: Exception) {
                println("DEBUG: 预览失败: ${e.message}")
                _previewState.value = PreviewState.Error("预览失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }


    /**
     * 预览图片文件
     */
// 在 previewImage 方法中添加日志
    private suspend fun previewImage(file: FileInfoModel) {
        try {
            println("DEBUG: 获取图片预览URL: ${file.path}")
            val previewUrl = repository.getPreviewUrl(file.path)
            println("DEBUG: 图片预览URL: $previewUrl")
            _previewState.value = PreviewState.ImageSuccess(previewUrl)
            println("DEBUG: 图片预览状态已更新")
        } catch (e: Exception) {
            println("DEBUG: 图片预览失败: ${e.message}")
            _previewState.value = PreviewState.Error("图片预览失败: ${e.message}")
        }
    }

    /**
     * 预览文本文件
     */
    private suspend fun previewText(file: FileInfoModel) {
        try {
            val textContent = repository.previewTextFile(file.path)
            _previewState.value = PreviewState.TextSuccess(textContent)
        } catch (e: Exception) {
            _previewState.value = PreviewState.Error("文本预览失败: ${e.message}")
        }
    }

    /**
     * 预览媒体文件（音频/视频）
     */
    private suspend fun previewMedia(file: FileInfoModel) {
        try {
            val streamUrl = repository.getStreamUrl(file.path)
            val mimeType = when (file.extension.lowercase()) {
                ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm", ".mkv" -> "video/*"
                ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a" -> "audio/*"
                else -> "application/octet-stream"
            }
            _previewState.value = PreviewState.MediaSuccess(streamUrl, mimeType)
        } catch (e: Exception) {
            _previewState.value = PreviewState.Error("媒体预览失败: ${e.message}")
        }
    }

    /**
     * 重置预览状态
     */
    fun resetPreviewState() {
        _previewState.value = PreviewState.Idle
    }

    /**
     * 重置上传状态
     */
    fun resetUploadState() {
        _uploadState.value = UploadState.Idle
    }

    /**
     * 清理预览缓存
     */
    fun clearPreviewCache() {
        repository.clearPreviewCache()
    }

    // ========== 视频播放器状态管理 ==========

    /**
     * 更新视频状态
     */
    fun updateVideoState(newState: VideoPlayerState) {
        videoPlayerState = newState
    }

    /**
     * 重置视频状态（当切换到新视频时调用）
     */
    fun resetVideoState() {
        videoPlayerState = VideoPlayerState()
    }

    /**
     * 检查是否应该恢复状态（相同视频URL）
     */
    fun shouldRestoreState(videoUrl: String): Boolean {
        return videoPlayerState.videoUrl == videoUrl && videoPlayerState.currentPosition > 0
    }
}

// States
sealed class FileListState {
    object Loading : FileListState()
    data class Success(val data: FileListResponse) : FileListState()
    data class Error(val message: String) : FileListState()
}

sealed class UploadState {
    object Idle : UploadState()
    object Uploading : UploadState()
    data class Success(val data: UploadResponse) : UploadState()
    data class Error(val message: String) : UploadState()
}