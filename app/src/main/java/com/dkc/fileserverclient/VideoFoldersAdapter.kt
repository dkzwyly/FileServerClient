package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VideoFoldersAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<VideoFoldersAdapter.VideoFolderViewHolder>() {

    private var selectedFolder: FileSystemItem? = null

    class VideoFolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.folderIcon)
        val titleView: TextView = view.findViewById(R.id.folderTitle)
        val backgroundView: View = view.findViewById(R.id.folderBackground)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoFolderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_folder, parent, false)
        return VideoFolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoFolderViewHolder, position: Int) {
        val item = items[position]

        // 设置标题
        holder.titleView.text = item.name

        // 设置选中状态
        val isSelected = selectedFolder?.path == item.path
        if (isSelected) {
            holder.backgroundView.setBackgroundColor(holder.itemView.context.getColor(android.R.color.holo_blue_light))
            holder.titleView.setTextColor(holder.itemView.context.getColor(android.R.color.white))
        } else {
            holder.backgroundView.setBackgroundColor(holder.itemView.context.getColor(android.R.color.darker_gray))
            holder.titleView.setTextColor(holder.itemView.context.getColor(android.R.color.black))
        }

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun setSelectedFolder(folder: FileSystemItem) {
        selectedFolder = folder
        notifyDataSetChanged()
    }
}