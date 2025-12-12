package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val onPlaylistClick: (Playlist) -> Unit
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

        // 设置歌单名称
        holder.playlistName.text = playlist.name

        // 设置曲目数量
        val count = playlist.tracks.size
        holder.trackCount.text = "$count 首"

        // 设置歌单图标 - 使用唱片样式的占位符
        holder.playlistIcon.setImageResource(R.drawable.ic_music_image_placeholder)

        // 如果有歌单封面，可以在这里加载（后续扩展）

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        // 更多按钮点击事件（后续可以添加菜单）
        holder.moreButton.setOnClickListener {
            // TODO: 显示歌单操作菜单（重命名、删除等）
        }
    }

    override fun getItemCount(): Int = playlists.size

    fun updateData(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}