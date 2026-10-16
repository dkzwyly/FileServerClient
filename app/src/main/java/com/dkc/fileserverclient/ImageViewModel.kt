package com.dkc.fileserverclient

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageViewModel(application: Application) : AndroidViewModel(application) {

    private val fileServerService = FileServerService(application)

    private val _imageList = MutableLiveData<List<FileSystemItem>>()
    val imageList: LiveData<List<FileSystemItem>> = _imageList

    private val _currentImage = MutableLiveData<FileSystemItem>()
    val currentImage: LiveData<FileSystemItem> = _currentImage

    private val _currentIndex = MutableLiveData<Int>()
    val currentIndex: LiveData<Int> = _currentIndex

    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState

    private val _errorState = MutableLiveData<String?>()
    val errorState: LiveData<String?> = _errorState

    private val _totalCount = MutableLiveData<Int>(0)
    val totalCount: LiveData<Int> = _totalCount

    private var serverUrl = ""
    private var directoryPath = ""
    private var initialImagePath = ""
    private var sortBy = ""
    private var sortOrder = ""

    sealed class LoadingState {
        object Idle : LoadingState()
        object Loading : LoadingState()
        object Success : LoadingState()
        data class Error(val message: String) : LoadingState()
    }

    fun initialize(
        serverUrl: String,
        directoryPath: String,
        initialImagePath: String? = null,
        sortBy: String = "",
        sortOrder: String = ""
    ) {
        this.serverUrl = serverUrl
        this.directoryPath = directoryPath
        this.initialImagePath = initialImagePath ?: ""
        this.sortBy = sortBy
        this.sortOrder = sortOrder
        loadImageList()
    }

    /**
     * 使用外部传入的路径列表初始化（用于相册预览）
     */
    fun initializeWithPaths(serverUrl: String, paths: List<String>, currentIndex: Int) {
        this.serverUrl = serverUrl

        val videoExts = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv",
            "m4v", "3gp", "ts", "rmvb", "wmv"
        )

        val items = paths.map { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            val isVideo = ext in videoExts
            FileSystemItem(
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                extension = ext,
                sizeFormatted = "",
                lastModified = "",
                isVideo = isVideo,                     // ★
                isAudio = false,
                mimeType = if (isVideo) "video/*" else "image/*",
                encoding = ""
            )
        }

        _imageList.value = items
        _totalCount.value = items.size
        if (currentIndex in items.indices) {
            _currentIndex.value = currentIndex
            _currentImage.value = items[currentIndex]
        } else if (items.isNotEmpty()) {
            _currentIndex.value = 0
            _currentImage.value = items[0]
        }
        _loadingState.value = LoadingState.Success
    }

    fun getServerUrl(): String = serverUrl

    private fun loadImageList() {
        viewModelScope.launch {
            try {
                _loadingState.value = LoadingState.Loading
                _errorState.value = null

                val allItems = withContext(Dispatchers.IO) {
                    if (sortBy.isNotEmpty() && sortOrder.isNotEmpty()) {
                        fileServerService.getFileList(serverUrl, directoryPath, sortBy, sortOrder)
                    } else {
                        fileServerService.getFileList(serverUrl, directoryPath)
                    }
                }

                // ★ 图片 + 视频混排
                val media = allItems.filter { item ->
                    !item.isDirectory && (isImageFile(item) || item.isVideo)
                }

                _imageList.value = media
                _totalCount.value = media.size

                if (media.isNotEmpty()) {
                    if (initialImagePath.isNotEmpty()) {
                        val index = media.indexOfFirst { item ->
                            getFullImageUrl(item) == initialImagePath || item.path.contains(initialImagePath)
                        }
                        val currentIdx = if (index != -1) index else 0
                        _currentIndex.value = currentIdx
                        _currentImage.value = media[currentIdx]
                    } else {
                        _currentIndex.value = 0
                        _currentImage.value = media[0]
                    }
                }

                _loadingState.value = LoadingState.Success

            } catch (e: Exception) {
                Log.e("ImageViewModel", "加载媒体列表失败", e)
                _errorState.value = "加载失败: ${e.message}"
                _loadingState.value = LoadingState.Error(e.message ?: "未知错误")
            }
        }
    }


    fun navigateToNext() {
        val currentIdx = _currentIndex.value ?: -1
        val images = _imageList.value ?: emptyList()
        if (currentIdx < images.size - 1) {
            val nextIdx = currentIdx + 1
            _currentIndex.value = nextIdx
            _currentImage.value = images[nextIdx]
        }
    }

    fun navigateToPrevious() {
        val currentIdx = _currentIndex.value ?: -1
        val images = _imageList.value ?: emptyList()
        if (currentIdx > 0) {
            val prevIdx = currentIdx - 1
            _currentIndex.value = prevIdx
            _currentImage.value = images[prevIdx]
        }
    }

    fun navigateToIndex(index: Int) {
        val images = _imageList.value ?: emptyList()
        if (index in images.indices) {
            _currentIndex.value = index
            _currentImage.value = images[index]
        }
    }

    fun getCurrentImageUrl(): String {
        val item = _currentImage.value
        return if (item != null) {
            getFullImageUrl(item)
        } else {
            ""
        }
    }

    private fun getFullImageUrl(item: FileSystemItem): String {
        val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
        return "${serverUrl.removeSuffix("/")}/api/fileserver/preview/$encodedPath"
    }

    private fun isImageFile(item: FileSystemItem): Boolean {
        val imageExtensions = listOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            "JPG", "JPEG", "PNG", "GIF", "BMP", "WEBP"
        )
        return imageExtensions.any { item.extension.contains(it, true) }
    }

    fun refresh() {
        loadImageList()
    }
}