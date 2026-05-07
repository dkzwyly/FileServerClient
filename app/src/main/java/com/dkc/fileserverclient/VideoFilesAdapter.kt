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
        holder.currentLoadPath = item.path

        holder.titleView.text = removeFileExtension(item.name)
        holder.durationView.text = item.sizeFormatted

        // 直接使用 ThumbnailLoader，内部优先本地缓存
        ThumbnailLoader.loadVideoThumbnail(
            imageView = holder.thumbnailView,
            serverUrl = serverUrl,
            videoPath = item.path,
            width = 320,
            height = 180
        )

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

        ThumbnailLoader.loadVideoThumbnail(
            imageView = holder.thumbnailView,
            serverUrl = serverUrl,
            videoPath = videoItem.path,
            width = 320,
            height = 180
        )
    }

    override fun onViewRecycled(holder: VideoFileViewHolder) {
        super.onViewRecycled(holder)
        holder.thumbnailView.setImageDrawable(null)
        holder.currentLoadPath = null
    }
}