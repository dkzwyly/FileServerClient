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
    private val onAudioClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<AudioLibraryAdapter.AudioViewHolder>() {

    class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val audioIcon: ImageView = view.findViewById(R.id.audioIcon)
        val fileName: TextView = view.findViewById(R.id.audioFileName)
        val fileInfo: TextView = view.findViewById(R.id.audioFileInfo)
        val playButton: ImageView = view.findViewById(R.id.playButton)
        val durationText: TextView = view.findViewById(R.id.durationText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_library, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val audioItem = audioItems[position]

        // 设置文件名
        holder.fileName.text = audioItem.name

        // 设置文件信息（大小和格式）
        val fileInfo = "${audioItem.sizeFormatted} • ${getAudioFormat(audioItem)}"
        holder.fileInfo.text = fileInfo

        // 设置音频图标
        holder.audioIcon.setImageResource(R.drawable.ic_audio)

        // 设置时长信息（如果有的话）
        holder.durationText.text = getDurationText(audioItem)

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onAudioClick(audioItem)
        }

        // 播放按钮点击事件
        holder.playButton.setOnClickListener {
            onAudioClick(audioItem)
        }
    }

    override fun getItemCount(): Int = audioItems.size

    private fun getAudioFormat(item: FileSystemItem): String {
        return when (item.extension.lowercase()) {
            ".mp3" -> "MP3"
            ".wav" -> "WAV"
            ".ogg" -> "OGG"
            ".m4a" -> "M4A"
            ".flac" -> "FLAC"
            ".aac" -> "AAC"
            ".wma" -> "WMA"
            ".amr" -> "AMR"
            else -> item.extension.uppercase()
        }
    }

    private fun getDurationText(item: FileSystemItem): String {
        // 这里可以根据需要从文件元数据中获取时长
        // 目前先返回空字符串，后续可以扩展
        return ""
    }
}