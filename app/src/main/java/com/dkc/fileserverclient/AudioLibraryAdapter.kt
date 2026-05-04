package com.dkc.fileserverclient

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import java.io.File

class AudioLibraryAdapter(
    private val serverUrl: String,
    private var audioTracks: List<AudioTrack>,
    private val onAudioClick: (AudioTrack) -> Unit,
    private val onAudioLongClick: (AudioTrack) -> Unit,
    private val lifecycleScope: CoroutineScope,   // Activity的协程域
    private val resources: Resources               // 用于 dp → px
) : RecyclerView.Adapter<AudioLibraryAdapter.AudioViewHolder>() {

    // 正确的 dp 转 px 计算（例如封面图大小为 48dp）
    private val thumbnailSize by lazy {
        (48 * resources.displayMetrics.density).toInt()
    }

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
            .size(thumbnailSize)   // 使用正确的尺寸限制
            .placeholder(R.drawable.ic_music_image_placeholder)
            .error(R.drawable.ic_music_image_placeholder)
            .crossfade(true)
            .target(holder.audioIcon)
            .build()
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
}