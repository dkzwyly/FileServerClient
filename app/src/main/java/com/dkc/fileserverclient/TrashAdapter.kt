package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrashAdapter(
    private val items: MutableList<TrashRecord>,
    private val onRestore: (TrashRecord) -> Unit,
    private val onPermanentDelete: (TrashRecord) -> Unit,
    private val onItemClick: (TrashRecord) -> Unit   // 新增：点击条目预览
) : RecyclerView.Adapter<TrashAdapter.TrashViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trash, parent, false)
        return TrashViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.btnRestore.setOnClickListener { onRestore(item) }
        holder.btnPermanentDelete.setOnClickListener { onPermanentDelete(item) }
        // 点击整个条目触发预览
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<TrashRecord>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    class TrashViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.trashIcon)
        private val fileName: TextView = itemView.findViewById(R.id.trashFileName)
        private val filePath: TextView = itemView.findViewById(R.id.trashFilePath)
        private val fileSize: TextView = itemView.findViewById(R.id.trashFileSize)
        private val deletedTime: TextView = itemView.findViewById(R.id.trashDeletedTime)
        val btnRestore: Button = itemView.findViewById(R.id.btnRestore)
        val btnPermanentDelete: Button = itemView.findViewById(R.id.btnPermanentDelete)

        fun bind(item: TrashRecord) {
            val name = item.originalPath.substringAfterLast('/').ifEmpty { "未命名" }
            fileName.text = name
            filePath.text = item.originalPath
            fileSize.text = if (item.isDirectory) "📁 文件夹" else formatFileSize(item.fileSize)
            deletedTime.text = formatTime(item.deletedTime)
            icon.text = if (item.isDirectory) "📁" else "📄"
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes == 0L) return "0 B"
            val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
            var order = 0
            var len = bytes.toDouble()
            while (len >= 1024 && order < sizes.size - 1) {
                order++
                len /= 1024
            }
            return "%.2f ${sizes[order]}".format(len)
        }

        private fun formatTime(timeStr: String): String {
            return try {
                // 简单截取前16个字符，可根据需要优化
                if (timeStr.length >= 16) timeStr.substring(0, 16) else timeStr
            } catch (_: Exception) {
                timeStr
            }
        }
    }
}