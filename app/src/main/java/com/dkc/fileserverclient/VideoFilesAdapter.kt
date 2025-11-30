// VideoFilesAdapter.kt
package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class VideoFilesAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit,
    private val coroutineScope: CoroutineScope
) : RecyclerView.Adapter<VideoFilesAdapter.VideoFileViewHolder>() {

    class VideoFileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnailView: ImageView = view.findViewById(R.id.videoThumbnail)
        val titleView: TextView = view.findViewById(R.id.videoTitle)
        val durationView: TextView = view.findViewById(R.id.videoDuration)
        var currentLoadPath: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoFileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_file, parent, false)
        return VideoFileViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoFileViewHolder, position: Int) {
        val item = items[position]

        // 重置当前状态
        holder.currentLoadPath = null

        // 设置标题（去掉文件后缀）
        val displayName = removeFileExtension(item.name)
        holder.titleView.text = displayName

        // 设置文件大小
        holder.durationView.text = item.sizeFormatted

        // 设置默认占位符
        holder.thumbnailView.setImageResource(R.drawable.ic_video_placeholder)

        // 从服务器加载视频缩略图
        loadVideoThumbnailFromServer(holder, item)

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    private fun removeFileExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            fileName.substring(0, lastDotIndex)
        } else {
            fileName
        }
    }

    private fun loadVideoThumbnailFromServer(holder: VideoFileViewHolder, videoItem: FileSystemItem) {
        holder.currentLoadPath = videoItem.path

        coroutineScope.launch {
            try {
                Log.d("VideoFiles", "从服务器加载视频缩略图: ${videoItem.name}")

                // 使用 ThumbnailLoader 从服务器加载缩略图
                val bitmap = ThumbnailLoader.loadVideoThumbnail(
                    serverUrl = serverUrl,
                    videoPath = videoItem.path,
                    width = 320,
                    height = 180
                )

                // 检查当前加载路径是否仍然匹配
                if (holder.currentLoadPath == videoItem.path) {
                    if (bitmap != null) {
                        holder.thumbnailView.setImageBitmap(bitmap)
                        Log.d("VideoFiles", "视频缩略图设置成功: ${videoItem.name}")
                    } else {
                        holder.thumbnailView.setImageResource(R.drawable.ic_video_placeholder)
                        Log.w("VideoFiles", "视频缩略图加载失败，使用占位符: ${videoItem.name}")
                    }
                }

            } catch (e: Exception) {
                Log.e("VideoFiles", "视频缩略图加载异常: ${e.message}", e)
                if (holder.currentLoadPath == videoItem.path) {
                    holder.thumbnailView.setImageResource(R.drawable.ic_video_placeholder)
                }
            }
        }
    }

    override fun onViewRecycled(holder: VideoFileViewHolder) {
        super.onViewRecycled(holder)
        holder.thumbnailView.setImageDrawable(null)
        holder.currentLoadPath = null
    }
}