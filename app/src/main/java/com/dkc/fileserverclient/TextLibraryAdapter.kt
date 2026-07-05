package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class TextLibraryAdapter(
    private val serverUrl: String,
    private val onItemClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<TextLibraryAdapter.BookshelfViewHolder>() {

    class BookshelfViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.bookshelfIcon)
        val titleView: TextView = view.findViewById(R.id.bookshelfTitle)
        val countView: TextView = view.findViewById(R.id.bookshelfCount)
    }

    // 使用 AsyncListDiffer 替代直接持有 List
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<FileSystemItem>() {
        override fun areItemsTheSame(oldItem: FileSystemItem, newItem: FileSystemItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileSystemItem, newItem: FileSystemItem): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.isDirectory == newItem.isDirectory &&
                    oldItem.size == newItem.size
        }
    })

    // 对外提交数据
    fun submitList(list: List<FileSystemItem>) {
        differ.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookshelfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bookshelf, parent, false)
        return BookshelfViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookshelfViewHolder, position: Int) {
        val item = differ.currentList[position]

        holder.iconView.setImageResource(R.drawable.ic_bookshelf)
        holder.titleView.text = item.name
        holder.countView.text = "书架"

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = differ.currentList.size
}