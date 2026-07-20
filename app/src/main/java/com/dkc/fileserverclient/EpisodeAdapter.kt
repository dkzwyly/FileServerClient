package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EpisodeAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

    class EpisodeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.episodeThumbnail)
        val title: TextView = view.findViewById(R.id.episodeTitle)
        val info: TextView = view.findViewById(R.id.episodeInfo)
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

        if (item.isDirectory) {
            holder.thumbnail.setImageResource(R.drawable.ic_folder)
        } else {
            ThumbnailLoader.loadVideoThumbnail(
                imageView = holder.thumbnail,
                serverUrl = serverUrl,
                videoPath = item.path,
                width = 320,  // 服务端支持的固定尺寸
                height = 180
            )
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}