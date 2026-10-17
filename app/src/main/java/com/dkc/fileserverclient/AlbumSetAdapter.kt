package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AlbumSetAdapter(
    private val serverUrl: String,
    private val albums: List<Album>,
    private val onAlbumClick: (Album) -> Unit,
    private val onAlbumLongClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumSetAdapter.ViewHolder>() {

    companion object {
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv",
            "m4v", "3gp", "ts", "rmvb", "wmv"
        )
    }

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
        holder.countText.text = "${album.imagePaths.size} 项"

        val coverPath = album.imagePaths.firstOrNull()

        if (coverPath.isNullOrEmpty()) {
            // ★ 空相册：固定显示图片占位图
            holder.albumIcon.setImageResource(R.drawable.ic_image_placeholder)
        } else {
            val name = coverPath.substringAfterLast('/')
            val ext = coverPath.substringAfterLast('.', "").lowercase()
            val isVideo = ext in VIDEO_EXTS

            val coverItem = FileSystemItem(
                name = name,
                path = coverPath,
                size = 0,
                extension = ext,
                sizeFormatted = "",
                lastModified = "",
                isVideo = isVideo,
                isAudio = false,
                mimeType = if (isVideo) "video/*" else "image/*",
                encoding = ""
            )

            // ★ 视频封面用 ic_video_placeholder，图片用 ic_image_placeholder
            val placeholderRes = if (isVideo) R.drawable.ic_video_placeholder else R.drawable.ic_image_placeholder

            ThumbnailLoader.loadImageThumbnail(
                imageView = holder.albumIcon,
                imageItem = coverItem,
                serverUrl = serverUrl,
                placeholderResId = placeholderRes,
                errorResId = placeholderRes
            )
        }

        holder.itemView.setOnClickListener { onAlbumClick(album) }
        holder.itemView.setOnLongClickListener {
            onAlbumLongClick(album)
            true
        }
    }

    override fun getItemCount(): Int = albums.size
}