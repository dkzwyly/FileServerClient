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
    private val onRemoveClick: (AudioTrack) -> Unit
) : RecyclerView.Adapter<PlaylistDetailAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "PlaylistDetailAdapter"
    }

    // 当前正在播放的歌曲 ID
    private var currentlyPlayingId: String? = null

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

        // 高亮当前播放的歌曲
        if (currentlyPlayingId == track.id) {
            holder.itemView.setBackgroundColor(
                holder.itemView.context.getColor(R.color.playing_highlight)
            )
        } else {
            holder.itemView.setBackgroundColor(
                holder.itemView.context.getColor(android.R.color.transparent)
            )
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

    /**
     * 设置正在播放的歌曲 ID，并刷新高亮
     */
    fun setCurrentlyPlaying(trackId: String?) {
        if (currentlyPlayingId != trackId) {
            currentlyPlayingId = trackId
            notifyDataSetChanged()
        }
    }

    /**
     * 根据 trackId 获取在列表中的位置
     */
    fun getPositionByTrackId(trackId: String): Int {
        return tracks.indexOfFirst { it.id == trackId }
    }
}