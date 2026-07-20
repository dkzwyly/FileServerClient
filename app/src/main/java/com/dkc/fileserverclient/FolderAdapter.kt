package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onFolderClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.episodeThumbnail)
        val title: TextView = view.findViewById(R.id.episodeTitle)
        val info: TextView = view.findViewById(R.id.episodeInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return FolderViewHolder(view)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.info.text = "文件夹"
        holder.thumbnail.setImageResource(R.drawable.ic_folder)

        holder.itemView.setOnClickListener {
            onFolderClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}