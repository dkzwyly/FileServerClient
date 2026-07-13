package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class AlbumSetAdapter(
    private val serverUrl: String,
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

        // 获取相册第一张图片路径作为封面
        val coverPath = album.imagePaths.firstOrNull()
        if (coverPath != null) {
            // 构造一个临时 FileSystemItem，只需提供 path 和 name
            // 注意：FileSystemItem 的 isDirectory 是计算属性，这里直接构造数据类
            val tempImageItem = FileSystemItem(
                name = coverPath.substringAfterLast('/'),
                path = coverPath,
                size = 0,
                extension = coverPath.substringAfterLast('.', ""),
                sizeFormatted = "",
                lastModified = "",
                isVideo = false,
                isAudio = false,
                mimeType = "image/*",
                encoding = ""
            )
            // 使用 ThumbnailLoader 加载缩略图，它内部会处理缓存和证书
            ThumbnailLoader.loadImageThumbnail(
                imageView = holder.albumIcon,
                imageItem = tempImageItem,
                serverUrl = serverUrl,
                placeholderResId = R.drawable.ic_image_placeholder,  // 加载中占位
                errorResId = R.drawable.ic_image_placeholder         // 加载失败也显示图片占位
            )
        } else {
            // 空相册显示占位图
            holder.albumIcon.setImageResource(R.drawable.ic_image_placeholder)
        }

        // 点击事件
        holder.itemView.setOnClickListener { onAlbumClick(album) }
        holder.itemView.setOnLongClickListener {
            onAlbumLongClick(album)
            true
        }
    }

    override fun getItemCount(): Int = albums.size
}