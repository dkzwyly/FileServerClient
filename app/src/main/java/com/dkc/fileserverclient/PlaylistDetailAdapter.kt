package com.dkc.fileserverclient

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.net.URLEncoder

class PlaylistDetailAdapter(
    private var tracks: List<AudioTrack>,
    private val serverUrl: String,
    private val onItemClick: (AudioTrack, Int) -> Unit,
    private val onRemoveClick: (AudioTrack) -> Unit
) : RecyclerView.Adapter<PlaylistDetailAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "PlaylistDetailAdapter"
    }

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

        holder.itemView.setOnClickListener {
            onItemClick(track, position)
        }
        holder.btnRemove.setOnClickListener {
            onRemoveClick(track)
        }
    }

    private fun loadTrackCover(holder: ViewHolder, track: AudioTrack) {
        if (serverUrl.isNotEmpty() && track.path.isNotEmpty()) {
            val coverUrl = buildCoverUrl(track.path)
            Log.d(TAG, "加载歌曲封面: ${track.name}, URL: $coverUrl")

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
                    holder.icon.setImageDrawable(drawable)
                }
                .build()

            imageLoader.enqueue(request)
        } else {
            Log.d(TAG, "路径为空或serverUrl为空，使用默认图标: ${track.name}")
            holder.icon.setImageResource(R.drawable.ic_music_image_placeholder)
        }
    }

    /**
     * 完全参考 PreviewActivity 中 SongMetadataManager.getCoverUrl 的实现
     */
    private fun buildCoverUrl(songPath: String): String {
        val encodedPath = URLEncoder.encode(songPath, "UTF-8")
        var url = "${serverUrl.removeSuffix("/")}/api/fileserver/song/cover/$encodedPath"
        url += "?t=${System.currentTimeMillis()}"
        return url
    }

    override fun getItemCount() = tracks.size

    fun updateData(newTracks: List<AudioTrack>) {
        tracks = newTracks
        notifyDataSetChanged()
    }
}