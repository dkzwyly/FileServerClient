package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TextLibraryDetailAdapter(
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<TextLibraryDetailAdapter.TextItemViewHolder>() {

    class TextItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.textItemIcon)
        val titleView: TextView = view.findViewById(R.id.textItemTitle)
        val typeView: TextView = view.findViewById(R.id.textItemType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_text_library, parent, false)
        return TextItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: TextItemViewHolder, position: Int) {
        val item = items[position]

        if (item.isDirectory) {
            // 文件夹显示为书架
            holder.iconView.setImageResource(R.drawable.ic_bookshelf)
            holder.titleView.text = item.name
            holder.typeView.text = "文件夹"
        } else {
            // 文本文件显示文件图标和去掉后缀的文件名
            holder.iconView.setImageResource(R.drawable.ic_text_file)

            // 去掉文件后缀显示
            val displayName = removeFileExtension(item.name)
            holder.titleView.text = displayName

            // 显示文件类型
            holder.typeView.text = getFileTypeDescription(item)
        }

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    private fun removeFileExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            fileName.substring(0, lastDotIndex)
        } else {
            fileName
        }
    }

    private fun getFileTypeDescription(item: FileSystemItem): String {
        return when {
            item.isDirectory -> "文件夹"
            item.name.endsWith(".txt", ignoreCase = true) -> "文本文件"
            item.name.endsWith(".pdf", ignoreCase = true) -> "PDF文档"
            item.name.endsWith(".doc", ignoreCase = true) || item.name.endsWith(".docx", ignoreCase = true) -> "Word文档"
            item.name.endsWith(".xls", ignoreCase = true) || item.name.endsWith(".xlsx", ignoreCase = true) -> "Excel文档"
            item.name.endsWith(".ppt", ignoreCase = true) || item.name.endsWith(".pptx", ignoreCase = true) -> "PPT文档"
            else -> "文档"
        }
    }

    override fun getItemCount(): Int = items.size
}