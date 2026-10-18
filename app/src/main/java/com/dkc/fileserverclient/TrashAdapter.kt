package com.dkc.fileserverclient

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrashAdapter(
    private val items: MutableList<TrashRecord>,
    private val serverUrl: String,
    private val onRestore: (TrashRecord) -> Unit,
    private val onPermanentDelete: (TrashRecord) -> Unit
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
        holder.itemView.isClickable = false
        holder.itemView.setOnClickListener(null)
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<TrashRecord>) {
        items.clear()
        items.addAll(newList)
        Log.d(TAG, "updateList: size=${newList.size} serverUrl=[$serverUrl]")
        notifyDataSetChanged()
    }

    inner class TrashViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: TextView = itemView.findViewById(R.id.trashIcon)
        private val thumbnail: ImageView = itemView.findViewById(R.id.trashThumbnail)
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
            bindIcon(item)
        }

        private fun bindIcon(item: TrashRecord) {
            val path = item.originalPath
            val ext = path.substringAfterLast('.', "").lowercase()
            val isVideo = ext in VIDEO_EXTS
            val isImage = ext in IMAGE_EXTS

            Log.d(TAG, "bindIcon: id=${item.id} originalPath=[$path] ext=[$ext] " +
                    "dir=${item.isDirectory} isVideo=$isVideo isImage=$isImage")

            thumbnail.tag = item.id
            thumbnail.visibility = View.GONE
            thumbnail.setImageDrawable(null)
            icon.visibility = View.VISIBLE

            if (item.isDirectory) {
                icon.text = "📁"
                Log.d(TAG, "bindIcon: id=${item.id} 目录，直接 emoji")
                return
            }
            if (!isVideo && !isImage) {
                icon.text = "📄"
                Log.d(TAG, "bindIcon: id=${item.id} 非图片/视频，直接 emoji")
                return
            }

            icon.text = if (isVideo) "🎬" else "🖼️"

            val onResult: (Boolean) -> Unit = { success ->
                val tagMatch = thumbnail.tag == item.id
                Log.d(TAG, "onResult: id=${item.id} success=$success tagMatch=$tagMatch")
                if (tagMatch) {
                    thumbnail.visibility = if (success) View.VISIBLE else View.GONE
                    icon.visibility = if (success) View.GONE else View.VISIBLE
                }
            }

            if (isVideo) {
                ThumbnailLoader.loadVideoThumbnail(
                    imageView = thumbnail,
                    serverUrl = serverUrl,
                    videoPath = path,
                    width = 320,
                    height = 180,
                    onResult = onResult
                )
            } else {
                ThumbnailLoader.loadImageThumbnailByPath(
                    imageView = thumbnail,
                    serverUrl = serverUrl,
                    imagePath = path,
                    onResult = onResult
                )
            }
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
                if (timeStr.length >= 16) timeStr.substring(0, 16) else timeStr
            } catch (_: Exception) {
                timeStr
            }
        }
    }

    companion object {
        private const val TAG = "TrashAdapter"
        private val IMAGE_EXTS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg"
        )
        private val VIDEO_EXTS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v"
        )
    }
}