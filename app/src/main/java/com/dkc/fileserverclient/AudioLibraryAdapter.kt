// AudioLibraryAdapter.kt
package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import java.io.File

class AudioLibraryAdapter(
    private val serverUrl: String,
    private var audioTracks: List<AudioTrack>,
    private val onAudioClick: (AudioTrack) -> Unit,
    private val onAudioLongClick: (AudioTrack) -> Unit,
    private val lifecycleScope: CoroutineScope   // 从 Activity 传入，管理下载协程生命周期
) : RecyclerView.Adapter<AudioLibraryAdapter.AudioViewHolder>() {

    // 缩略图尺寸（根据 Item 布局中的 ImageView 大小设定）
    private val thumbnailSize by lazy { dpToPx(48) }

    class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val audioIcon: ImageView = view.findViewById(R.id.audioIcon)
        val fileName: TextView = view.findViewById(R.id.audioFileName)
        val artistAlbum: TextView = view.findViewById(R.id.audioArtistAlbum)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_library, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val track = audioTracks[position]
        val displayTitle = track.title ?: removeExtension(track.name)
        holder.fileName.text = displayTitle

        val artistAlbumText = when {
            !track.artist.isNullOrEmpty() && !track.album.isNullOrEmpty() -> "${track.artist} · ${track.album}"
            !track.artist.isNullOrEmpty() -> track.artist!!
            !track.album.isNullOrEmpty() -> track.album!!
            else -> "未知艺术家 · 未知专辑"
        }
        holder.artistAlbum.text = artistAlbumText

        // 加载封面（优先本地文件）
        loadCoverImage(holder, track)

        holder.itemView.setOnClickListener { onAudioClick(track) }
        holder.itemView.setOnLongClickListener { onAudioLongClick(track); true }
    }

    private fun loadCoverImage(holder: AudioViewHolder, track: AudioTrack) {
        val coverUrl = track.coverUrl
        if (coverUrl.isNullOrEmpty()) {
            holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)
            return
        }

        // 1. 检查本地是否已有
        val localFile = CoverImageStorage.getLocalFile(track.id, coverUrl)
        if (localFile.exists()) {
            // 直接加载本地文件
            loadFromFile(holder, localFile)
            return
        }

        // 2. 没有本地文件，先显示占位图，并触发下载
        holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)
        CoverImageStorage.downloadCover(track.id, coverUrl, lifecycleScope) { file ->
            if (file != null) {
                // 确保 ViewHolder 位置仍然对应同一个 track
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && audioTracks.getOrNull(pos)?.id == track.id) {
                    loadFromFile(holder, file)
                }
            }
        }
    }

    private fun loadFromFile(holder: AudioViewHolder, file: File) {
        val request = ImageRequest.Builder(holder.itemView.context)
            .data(file)
            .size(thumbnailSize)
            .placeholder(R.drawable.ic_music_image_placeholder)
            .error(R.drawable.ic_music_image_placeholder)
            .crossfade(true)
            .target(holder.audioIcon)
            .build()
        // 使用全局/默认的 ImageLoader（已配置好缓存）
        coil.Coil.imageLoader(holder.itemView.context).enqueue(request)
    }

    override fun getItemCount(): Int = audioTracks.size

    private fun removeExtension(fileName: String): String {
        return try {
            val lastDotIndex = fileName.lastIndexOf(".")
            if (lastDotIndex > 0) fileName.substring(0, lastDotIndex) else fileName
        } catch (e: Exception) {
            fileName
        }
    }

    fun updateData(newTracks: List<AudioTrack>) {
        audioTracks = newTracks
        notifyDataSetChanged()
    }

    private fun dpToPx(dp: Int): Int {
        // 假设 adapter 在创建时能获取到 Resources，可以简单取第一个 item 的 context，这里用静态方式
        return (dp * 3).toInt() // 近似，实际应使用 context.resources.displayMetrics
        // 正式项目建议在构造时传入 Resources 或使用 holder.itemView.context
    }
}