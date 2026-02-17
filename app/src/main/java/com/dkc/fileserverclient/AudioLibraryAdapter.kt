package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AudioLibraryAdapter(
    private val serverUrl: String,
    private val audioItems: List<FileSystemItem>,
    private val onAudioClick: (FileSystemItem) -> Unit,
    private val onAudioLongClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<AudioLibraryAdapter.AudioViewHolder>() {

    class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val audioIcon: ImageView = view.findViewById(R.id.audioIcon)
        val fileName: TextView = view.findViewById(R.id.audioFileName)
        val artistAlbum: TextView = view.findViewById(R.id.audioArtistAlbum) // 重命名为artistAlbum
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_library, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val audioItem = audioItems[position]

        // 移除扩展名，只显示文件名
        val displayName = removeExtension(audioItem.name)
        holder.fileName.text = displayName

        // 设置音频图标
        holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)

        // 设置艺术家/专辑信息（目前显示未知）
        holder.artistAlbum.text = "未知艺术家 · 未知专辑"

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onAudioClick(audioItem)
        }

        holder.itemView.setOnLongClickListener {
            onAudioLongClick(audioItem)
            true
        }
    }

    override fun getItemCount(): Int = audioItems.size

    /**
     * 从文件名中移除扩展名
     */
    private fun removeExtension(fileName: String): String {
        return try {
            // 找到最后一个点号的位置
            val lastDotIndex = fileName.lastIndexOf(".")
            if (lastDotIndex > 0) {
                // 移除点号及其后面的内容
                fileName.substring(0, lastDotIndex)
            } else {
                // 如果没有点号，返回原文件名
                fileName
            }
        } catch (e: Exception) {
            fileName
        }
    }
}