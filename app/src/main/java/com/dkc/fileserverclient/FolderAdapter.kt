package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.folderIcon)
        val title: TextView = view.findViewById(R.id.folderTitle)
        val count: TextView = view.findViewById(R.id.folderVideoCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_folder, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.count.text = ""  // 可显示子项数量，暂不实现
        holder.icon.setImageResource(R.drawable.ic_folder)
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}