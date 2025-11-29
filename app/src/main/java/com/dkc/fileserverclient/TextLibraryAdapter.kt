package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TextLibraryAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<TextLibraryAdapter.BookshelfViewHolder>() {

    class BookshelfViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.bookshelfIcon)
        val titleView: TextView = view.findViewById(R.id.bookshelfTitle)
        val countView: TextView = view.findViewById(R.id.bookshelfCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookshelfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookshelf, parent, false)
        return BookshelfViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookshelfViewHolder, position: Int) {
        val item = items[position]

        // 设置图标
        holder.iconView.setImageResource(R.drawable.ic_bookshelf)

        // 设置标题（文件夹名称）
        holder.titleView.text = item.name

        // 设置项目计数（可以显示文件夹中的项目数量，这里简单处理）
        holder.countView.text = "书架"

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}