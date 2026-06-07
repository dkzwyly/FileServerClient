package com.dkc.fileserverclient

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class PlaylistDetailAdapter(
    private var tracks: List<AudioTrack>,
    private val onItemClick: (AudioTrack, Int) -> Unit,
    private val onRemoveClick: (AudioTrack) -> Unit,
    private var animation: PlayingAnimation = ShineAnimationSimple()
) : RecyclerView.Adapter<PlaylistDetailAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "PlaylistDetailAdapter"
    }

    private var currentlyPlayingId: String? = null
    private var recyclerView: RecyclerView? = null

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.audioIcon)
        val fileName: TextView = itemView.findViewById(R.id.audioFileName)
        val artistAlbum: TextView = itemView.findViewById(R.id.audioArtistAlbum)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_detail, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        val displayName = track.name.removeSuffix(track.fileExtension)
        holder.fileName.text = displayName
        holder.artistAlbum.text = track.artist ?: "未知艺术家"

        loadTrackCover(holder, track)

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

        holder.itemView.setOnClickListener {
            onItemClick(track, position)
        }
        holder.btnRemove.setOnClickListener {
            onRemoveClick(track)
        }
    }

    private fun loadTrackCover(holder: ViewHolder, track: AudioTrack) {
        val coverUrl = track.coverUrl
        Log.d(TAG, "loadTrackCover: trackId=${track.id}, name=${track.name}, coverUrl=$coverUrl")

        if (coverUrl.isNullOrEmpty()) {
            Log.d(TAG, "coverUrl is null or empty, using placeholder")
            holder.icon.setImageResource(R.drawable.ic_music_image_placeholder)
            return
        }

        val localFile = CoverImageStorage.getLocalFile(track.id, coverUrl)
        Log.d(TAG, "localFile path: ${localFile.absolutePath}, exists=${localFile.exists()}, length=${localFile.length()}")

        if (localFile.exists()) {
            Log.d(TAG, "Local cache found, loading from file")
            holder.icon.load(localFile) {
                placeholder(R.drawable.ic_music_image_placeholder)
                error(R.drawable.ic_music_image_placeholder)
                crossfade(true)
            }
        } else {
            Log.d(TAG, "No local cache, using placeholder")
            holder.icon.setImageResource(R.drawable.ic_music_image_placeholder)
        }
    }

    override fun getItemCount() = tracks.size

    fun updateData(newTracks: List<AudioTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    fun setCurrentlyPlaying(trackId: String?) {
        if (currentlyPlayingId != trackId) {
            currentlyPlayingId = trackId
            notifyDataSetChanged()
        }
    }

    fun getPositionByTrackId(trackId: String): Int {
        return tracks.indexOfFirst { it.id == trackId }
    }

    fun setAnimation(newAnimation: PlayingAnimation) {
        // 停止当前正在播放的项上的旧动画
        val playingId = currentlyPlayingId
        if (playingId != null && recyclerView != null) {
            val position = getPositionByTrackId(playingId)
            if (position != -1) {
                val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
                if (holder != null) {
                    animation.stop(holder.itemView)
                }
            }
        }
        animation = newAnimation
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        animation.stop(holder.itemView)
    }
}