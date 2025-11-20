// FileListResponse.kt
package com.dkc.fileserverclient
data class FileListResponse(
    val currentPath: String = "",
    val parentPath: String = "",
    val directories: List<DirectoryInfoModel> = emptyList(),
    val files: List<FileInfoModel> = emptyList()
)