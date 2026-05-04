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
        // 新增：记录当前绑定的 Track ID，用于异步回调时比对
        var currentTrackId: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_library, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val track = audioTracks[position]
        // 记录本次绑定的 Track ID，用于回调校验
        holder.currentTrackId = track.id

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
            // 没有封面URL，直接显示占位图
            if (holder.currentTrackId == track.id) {
                holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)
            }
            return
        }

        // 1. 检查本地是否已有
        val localFile = CoverImageStorage.getLocalFile(track.id, coverUrl)
        if (localFile.exists()) {
            // 直接加载本地文件（带ID校验）
            if (holder.currentTrackId == track.id) {
                loadFromFile(holder, localFile, track.id)
            }
            return
        }

        // 2. 没有本地文件，先显示占位图，并触发下载
        if (holder.currentTrackId == track.id) {
            holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)
        }

        CoverImageStorage.downloadCover(track.id, coverUrl, lifecycleScope) { file ->
            if (file != null) {
                // 确保 ViewHolder 位置仍然对应同一个 track，再加载
                if (holder.currentTrackId == track.id) {
                    loadFromFile(holder, file, track.id)
                }
            }
        }
    }

    /**
     * 从本地文件加载封面到 ImageView，增加 trackId 校验
     */
    private fun loadFromFile(holder: AudioViewHolder, file: File, trackId: String) {
        val request = ImageRequest.Builder(holder.itemView.context)
            .data(file)
            .size(thumbnailSize)
            .placeholder(R.drawable.ic_music_image_placeholder)
            .error(R.drawable.ic_music_image_placeholder)
            .crossfade(true)
            .target { drawable ->
                // 关键检查：只有当前绑定的 Track ID 一致才设置图片
                if (holder.currentTrackId == trackId) {
                    holder.audioIcon.setImageDrawable(drawable)
                }
            }
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

    // 视图回收时清理状态，避免残留标识或图片
    override fun onViewRecycled(holder: AudioViewHolder) {
        super.onViewRecycled(holder)
        holder.audioIcon.setImageDrawable(null)
        holder.currentTrackId = null
    }
}