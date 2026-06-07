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
    private val lifecycleScope: CoroutineScope,
    private val resources: Resources,
    private var animation: PlayingAnimation = ShineAnimationSimple()
) : RecyclerView.Adapter<AudioLibraryAdapter.AudioViewHolder>() {

    private val thumbnailSize by lazy {
        (48 * resources.displayMetrics.density).toInt()
    }

    private var currentlyPlayingId: String? = null

    class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val audioIcon: ImageView = view.findViewById(R.id.audioIcon)
        val fileName: TextView = view.findViewById(R.id.audioFileName)
        val artistAlbum: TextView = view.findViewById(R.id.audioArtistAlbum)
        var currentTrackId: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_library, parent, false)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val track = audioTracks[position]
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

        loadCoverImage(holder, track)

        val isPlaying = currentlyPlayingId == track.id
        if (isPlaying) {
            holder.itemView.setBackgroundColor(
                holder.itemView.context.getColor(R.color.playing_highlight)
            )
            animation.start(holder.itemView)
        } else {
            holder.itemView.setBackgroundColor(
                holder.itemView.context.getColor(android.R.color.transparent)
            )
            animation.stop(holder.itemView)
        }

        holder.itemView.setOnClickListener { onAudioClick(track) }
        holder.itemView.setOnLongClickListener { onAudioLongClick(track); true }
    }

    private fun loadCoverImage(holder: AudioViewHolder, track: AudioTrack) {
        val coverUrl = track.coverUrl
        if (coverUrl.isNullOrEmpty()) {
            if (holder.currentTrackId == track.id) {
                holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)
            }
            return
        }

        val localFile = CoverImageStorage.getLocalFile(track.id, coverUrl)
        if (localFile.exists()) {
            if (holder.currentTrackId == track.id) {
                loadFromFile(holder, localFile, track.id)
            }
            return
        }

        if (holder.currentTrackId == track.id) {
            holder.audioIcon.setImageResource(R.drawable.ic_music_image_placeholder)
        }

        CoverImageStorage.downloadCover(track.id, coverUrl, lifecycleScope) { file ->
            if (file != null && holder.currentTrackId == track.id) {
                loadFromFile(holder, file, track.id)
            }
        }
    }

    private fun loadFromFile(holder: AudioViewHolder, file: File, trackId: String) {
        val request = ImageRequest.Builder(holder.itemView.context)
            .data(file)
            .size(thumbnailSize)
            .placeholder(R.drawable.ic_music_image_placeholder)
            .error(R.drawable.ic_music_image_placeholder)
            .crossfade(true)
            .target { drawable ->
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

    fun setCurrentlyPlaying(trackId: String?) {
        if (currentlyPlayingId != trackId) {
            currentlyPlayingId = trackId
            notifyDataSetChanged()
        }
    }

    fun getPositionByTrackId(trackId: String): Int {
        return audioTracks.indexOfFirst { it.id == trackId }
    }

    fun setAnimation(newAnimation: PlayingAnimation) {
        animation = newAnimation
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: AudioViewHolder) {
        super.onViewRecycled(holder)
        animation.stop(holder.itemView)
        holder.audioIcon.setImageDrawable(null)
        holder.currentTrackId = null
    }
}