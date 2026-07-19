package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class VideoFoldersAdapter(
    private val serverUrl: String,
    private val folderList: MutableList<FileSystemItem>,
    private val onFolderClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<VideoFoldersAdapter.ViewHolder>() {

    private var selectedFolder: FileSystemItem? = null
    private val videoCountMap = mutableMapOf<String, Int>()

    var isListMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folderList[position]
        val context = holder.itemView.context

        holder.folderTitle.text = folder.name

        val videoCount = videoCountMap[folder.path] ?: 0
        holder.folderVideoCount.text = if (videoCount > 0) {
            context.getString(R.string.video_count_format, videoCount)
        } else {
            context.getString(R.string.empty_folder)
        }

        // ---- 根据模式切换样式 ----
        if (isListMode) {
            // 列表模式：隐藏卡片背景/阴影，隐藏图标，调整内边距
            holder.folderCardView.cardElevation = 0f
            holder.folderCardView.radius = 0f
            holder.folderCardView.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.folderIcon.visibility = View.GONE
            // 调整内部布局为水平方向，但为简化，保持垂直但去掉图标间距
            // 可设置最小高度较小
            holder.itemView.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            // 网格模式：显示卡片样式
            holder.folderCardView.cardElevation = 4f
            holder.folderCardView.radius = 8f
            holder.folderCardView.setCardBackgroundColor(android.graphics.Color.WHITE)
            holder.folderIcon.visibility = View.VISIBLE
        }

        // 选中状态（仅网格模式保留高亮效果，列表模式可忽略）
        if (!isListMode) {
            val isSelected = selectedFolder?.path == folder.path
            if (isSelected) {
                holder.itemView.alpha = 1.0f
                holder.folderCardView.cardElevation = 8f
            } else {
                holder.itemView.alpha = 0.9f
                holder.folderCardView.cardElevation = 4f
            }
        }

        holder.itemView.setOnClickListener {
            onFolderClick(folder)
        }
    }

    override fun getItemCount(): Int = folderList.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folderCardView: CardView = itemView.findViewById(R.id.folderCardView)
        val folderIcon: ImageView = itemView.findViewById(R.id.folderIcon)
        val folderTitle: TextView = itemView.findViewById(R.id.folderTitle)
        val folderVideoCount: TextView = itemView.findViewById(R.id.folderVideoCount)
    }
}