package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class VideoFoldersAdapter(
    private val serverUrl: String,
    private val folderList: MutableList<FileSystemItem>,
    private val onFolderClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<VideoFoldersAdapter.ViewHolder>() {

    private var selectedFolder: FileSystemItem? = null
    private val videoCountMap = mutableMapOf<String, Int>()

    fun setSelectedFolder(folder: FileSystemItem) {
        selectedFolder = folder
        notifyDataSetChanged()
    }

    fun setVideoCount(folderPath: String, count: Int) {
        videoCountMap[folderPath] = count
        val position = folderList.indexOfFirst { it.path == folderPath }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    fun updateFolders(newFolders: List<FileSystemItem>) {
        folderList.clear()
        folderList.addAll(newFolders)
        notifyDataSetChanged()
    }

    fun getFolderAtPosition(position: Int): FileSystemItem? {
        return if (position in 0 until folderList.size) folderList[position] else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folderList[position]
        val context = holder.itemView.context

        // 设置文件夹名称
        holder.folderTitle.text = folder.name

        // 设置视频数量 - 使用字符串资源
        val videoCount = videoCountMap[folder.path] ?: 0
        holder.folderVideoCount.text = if (videoCount > 0) {
            context.getString(R.string.video_count_format, videoCount)
        } else {
            context.getString(R.string.empty_folder)
        }

        // 其余代码保持不变...
        val isSelected = selectedFolder?.path == folder.path
        if (isSelected) {
            holder.itemView.alpha = 1.0f
            holder.itemView.elevation = 6f
            holder.itemView.setBackgroundResource(R.drawable.folder_card_bg)
        } else {
            holder.itemView.alpha = 0.9f
            holder.itemView.elevation = 2f
            holder.itemView.setBackgroundResource(R.drawable.folder_card_bg)
        }

        holder.itemView.setOnClickListener {
            onFolderClick(folder)
        }
    }

    override fun getItemCount(): Int {
        return folderList.size
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folderIcon: ImageView = itemView.findViewById(R.id.folderIcon)
        val folderTitle: TextView = itemView.findViewById(R.id.folderTitle)
        val folderVideoCount: TextView = itemView.findViewById(R.id.folderVideoCount)
    }
}