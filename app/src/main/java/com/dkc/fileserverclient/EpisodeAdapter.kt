package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EpisodeAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit,
    private val onPlayClick: (FileSystemItem) -> Unit   // 新增播放点击回调
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    class EpisodeViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.episodeThumbnail)
        val title: TextView = view.findViewById(R.id.episodeTitle)
        val info: TextView = view.findViewById(R.id.episodeInfo)
        val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.info.text = if (item.isDirectory) "目录" else item.sizeFormatted

        // 如果是文件夹，显示文件夹图标，不加载缩略图；否则加载视频缩略图
        if (item.isDirectory) {
            holder.thumbnail.setImageResource(R.drawable.ic_folder)
            holder.btnPlay.visibility = android.view.View.GONE
        } else {
            // 视频：加载缩略图，显示播放按钮
            ThumbnailLoader.loadVideoThumbnail(
                imageView = holder.thumbnail,
                serverUrl = serverUrl,
                videoPath = item.path,
                width = 120,
                height = 80
            )
            holder.btnPlay.visibility = android.view.View.VISIBLE
            holder.btnPlay.setOnClickListener {
                onPlayClick(item)
            }
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}