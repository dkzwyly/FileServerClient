package com.dkc.fileserverclient

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.net.URLEncoder

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val serverUrl: String,
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
        if (firstTrack != null && serverUrl.isNotEmpty() && firstTrack.path.isNotEmpty()) {
            val coverUrl = buildCoverUrl(firstTrack.path)
            Log.d(TAG, "加载歌单封面: ${playlist.name}, URL: $coverUrl")

            val imageLoader = ImageLoader.Builder(holder.itemView.context.applicationContext)
                .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
                .build()

            val request = ImageRequest.Builder(holder.itemView.context)
                .data(coverUrl)
                .placeholder(R.drawable.ic_music_image_placeholder)
                .error(R.drawable.ic_music_image_placeholder)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .target { drawable ->
                    holder.playlistIcon.setImageDrawable(drawable)
                }
                .build()

            imageLoader.enqueue(request)
        } else {
            Log.d(TAG, "无首歌曲或路径为空，使用默认图标: ${playlist.name}")
            holder.playlistIcon.setImageResource(R.drawable.ic_music_image_placeholder)
        }
    }

    /**
     * 完全参考 PreviewActivity 中 SongMetadataManager.getCoverUrl 的实现
     */
    private fun buildCoverUrl(songPath: String): String {
        val encodedPath = URLEncoder.encode(songPath, "UTF-8")
        var url = "${serverUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
        // 添加时间戳避免缓存，与 PreviewActivity 保持一致
        url += "?t=${System.currentTimeMillis()}"
        return url
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