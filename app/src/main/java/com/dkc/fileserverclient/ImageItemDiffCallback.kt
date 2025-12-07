package com.dkc.fileserverclient

import androidx.recyclerview.widget.DiffUtil

class ImageItemDiffCallback : DiffUtil.ItemCallback<FileSystemItem>() {
    override fun areItemsTheSame(oldItem: FileSystemItem, newItem: FileSystemItem): Boolean {
        // 如果路径相同，则视为同一个项目
        return oldItem.path == newItem.path
    }

    override fun areContentsTheSame(oldItem: FileSystemItem, newItem: FileSystemItem): Boolean {
        // 比较所有相关属性
        return oldItem.name == newItem.name &&
                oldItem.size == newItem.size &&
                oldItem.lastModified == newItem.lastModified && // 修正：使用正确的字段名
                oldItem.isDirectory == newItem.isDirectory &&
                oldItem.isImage == newItem.isImage &&
                oldItem.hasThumbnail == newItem.hasThumbnail &&
                oldItem.isVideo == newItem.isVideo && // 添加视频属性比较
                oldItem.isAudio == newItem.isAudio && // 添加音频属性比较
                oldItem.mimeType == newItem.mimeType // 添加MIME类型比较
    }

    override fun getChangePayload(oldItem: FileSystemItem, newItem: FileSystemItem): Any? {
        // 检查哪些属性发生了变化
        val payload = mutableListOf<String>()

        if (oldItem.name != newItem.name) payload.add("name")
        if (oldItem.size != newItem.size) payload.add("size")
        if (oldItem.lastModified != newItem.lastModified) payload.add("lastModified")
        if (oldItem.isImage != newItem.isImage) payload.add("isImage")
        if (oldItem.hasThumbnail != newItem.hasThumbnail) payload.add("hasThumbnail")
        if (oldItem.isVideo != newItem.isVideo) payload.add("isVideo")
        if (oldItem.isAudio != newItem.isAudio) payload.add("isAudio")
        if (oldItem.mimeType != newItem.mimeType) payload.add("mimeType")

        // 如果没有变化，返回null
        return if (payload.isEmpty()) null else payload
    }
}
