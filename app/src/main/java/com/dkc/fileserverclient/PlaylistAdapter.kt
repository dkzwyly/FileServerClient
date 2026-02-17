package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val onPlaylistClick: (Playlist) -> Unit,
    private val onRenameClick: (Playlist) -> Unit,   // 重命名回调
    private val onDeleteClick: (Playlist) -> Unit    // 删除回调
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

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
        holder.playlistIcon.setImageResource(R.drawable.ic_music_image_placeholder)

        // 点击整个项进入详情
        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        // 点击更多按钮显示操作菜单
        holder.moreButton.setOnClickListener { view ->
            showPopupMenu(view, playlist)
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