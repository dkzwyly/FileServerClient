package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AlbumSetAdapter(
    private val albums: List<Album>,
    private val onAlbumClick: (Album) -> Unit,
    private val onAlbumLongClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumSetAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumIcon: ImageView = itemView.findViewById(R.id.albumIcon)
        val nameText: TextView = itemView.findViewById(R.id.albumNameText)
        val countText: TextView = itemView.findViewById(R.id.albumCountText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album_set, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val album = albums[position]
        holder.nameText.text = album.name
        holder.countText.text = "${album.imagePaths.size} 张图片"
        // 如果需要显示相册封面缩略图，可以在这里加载，否则保持默认菱形图标
        // 例如：加载第一张图片的缩略图
        // val coverPath = album.imagePaths.firstOrNull()
        // if (coverPath != null) loadThumbnail(holder.albumIcon, coverPath)
        holder.itemView.setOnClickListener { onAlbumClick(album) }
        holder.itemView.setOnLongClickListener {
            onAlbumLongClick(album)
            true
        }
    }

    override fun getItemCount(): Int = albums.size
}