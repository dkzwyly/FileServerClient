package com.dkc.fileserverclient

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onRenameClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    companion object {
        private const val TAG = "PlaylistAdapter"
    }

    class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val playlistIcon: ImageView = view.findViewById(R.id.playlistIcon)
        val playlistName: TextView = view.findViewById(R.id.playlistName)
        val trackCount: TextView = view.findViewById(R.id.trackCount)
        val moreButton: ImageView = view.findViewById(R.id.moreButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val playlist = playlists[position]

        holder.playlistName.text = playlist.name
        holder.trackCount.text = "${playlist.tracks.size} 首"

        loadPlaylistCover(holder, playlist)

        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        holder.moreButton.setOnClickListener { view ->
            showPopupMenu(view, playlist)
        }
    }

    private fun loadPlaylistCover(holder: PlaylistViewHolder, playlist: Playlist) {
        val firstTrack = playlist.tracks.firstOrNull()
        Log.d(TAG, "loadPlaylistCover: playlistId=${playlist.id}, name=${playlist.name}, firstTrack=${firstTrack?.name}")

        if (firstTrack == null) {
            Log.d(TAG, "No tracks in playlist, using placeholder")
            holder.playlistIcon.setImageResource(R.drawable.ic_music_image_placeholder)
            return
        }

        val coverUrl = firstTrack.coverUrl
        Log.d(TAG, "firstTrack coverUrl: $coverUrl")

        if (coverUrl.isNullOrEmpty()) {
            Log.d(TAG, "coverUrl is null or empty, using placeholder")
            holder.playlistIcon.setImageResource(R.drawable.ic_music_image_placeholder)
            return
        }

        val localFile = CoverImageStorage.getLocalFile(firstTrack.id, coverUrl)
        Log.d(TAG, "localFile path: ${localFile.absolutePath}, exists=${localFile.exists()}, length=${localFile.length()}")

        if (localFile.exists()) {
            Log.d(TAG, "Local cache found for playlist ${playlist.name}, loading from file")
            holder.playlistIcon.load(localFile) {
                placeholder(R.drawable.ic_music_image_placeholder)
                error(R.drawable.ic_music_image_placeholder)
                crossfade(true)
            }
        } else {
            Log.d(TAG, "No local cache for playlist ${playlist.name}, using placeholder")
            holder.playlistIcon.setImageResource(R.drawable.ic_music_image_placeholder)
        }
    }

    private fun showPopupMenu(anchor: View, playlist: Playlist) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.playlist_item_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    onRenameClick(playlist)
                    true
                }
                R.id.action_delete -> {
                    onDeleteClick(playlist)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun getItemCount(): Int = playlists.size

    fun updateData(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}