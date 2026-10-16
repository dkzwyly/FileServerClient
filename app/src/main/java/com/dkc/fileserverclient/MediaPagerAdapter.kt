package com.dkc.fileserverclient

import android.util.SparseArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class MediaPagerAdapter(
    private val items: List<FileSystemItem>,
    private val serverUrl: String,
    private val onClickPage: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
        private const val PAYLOAD_PLAY_STATE = "play_state"
    }

    private var currentPage: Int = 0

    class ImageVH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.pagerImageView)
        val progress: ProgressBar = view.findViewById(R.id.pagerProgress)
    }

    class VideoVH(view: View) : RecyclerView.ViewHolder(view) {
        val playerView: PlayerView = view.findViewById(R.id.pagerPlayerView)
        val progress: ProgressBar = view.findViewById(R.id.pagerProgress)
        var player: ExoPlayer? = null
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].isVideo) TYPE_VIDEO else TYPE_IMAGE

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_VIDEO -> VideoVH(inflater.inflate(R.layout.item_media_page_video, parent, false))
            else -> ImageVH(inflater.inflate(R.layout.item_media_page_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
        val base = serverUrl.removeSuffix("/")

        when (holder) {
            is ImageVH -> {
                val url = "$base/api/fileserver/preview/$encoded"
                holder.progress.visibility = View.VISIBLE
                holder.image.load(url) {
                    listener(
                        onSuccess = { _, _ -> holder.progress.visibility = View.GONE },
                        onError = { _, _ -> holder.progress.visibility = View.GONE }
                    )
                }
                holder.itemView.setOnClickListener { onClickPage() }
            }
            is VideoVH -> {
                bindVideo(holder, item, base, encoded, position)
                holder.itemView.setOnClickListener { onClickPage() }
            }
        }
    }

    private fun bindVideo(
        holder: VideoVH,
        item: FileSystemItem,
        base: String,
        encoded: String,
        position: Int
    ) {
        val url = "$base/api/fileserver/stream/$encoded"
        if (holder.player == null) {
            val client = UnsafeHttpClient.createUnsafeOkHttpClient()
            val httpFactory = OkHttpDataSource.Factory(client)
            val dataSourceFactory = DefaultDataSource.Factory(holder.itemView.context, httpFactory)

            holder.player = ExoPlayer.Builder(holder.itemView.context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build().apply {
                    holder.playerView.player = this
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    playWhenReady = position == currentPage
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            holder.progress.visibility =
                                if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        }
                    })
                }
        } else {
            // 已被回收再复用，URL 一般相同（ViewPager2 位置稳定），只更新播放状态
            val shouldPlay = position == currentPage
            holder.player?.playWhenReady = shouldPlay
            if (!shouldPlay) holder.player?.pause()
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        if (payloads.contains(PAYLOAD_PLAY_STATE)) {
            if (holder is VideoVH) {
                val shouldPlay = position == currentPage
                holder.player?.playWhenReady = shouldPlay
                if (!shouldPlay) holder.player?.pause()
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /**
     * 切换当前页：只通知旧页和新页两个视频页，不触发全量刷新。
     */
    fun setCurrentPage(newPage: Int) {
        if (newPage == currentPage) return
        val oldPage = currentPage
        currentPage = newPage

        if (oldPage in items.indices && items[oldPage].isVideo)
            notifyItemChanged(oldPage, PAYLOAD_PLAY_STATE)
        if (newPage in items.indices && items[newPage].isVideo)
            notifyItemChanged(newPage, PAYLOAD_PLAY_STATE)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is VideoVH -> {
                holder.playerView.player = null
                holder.player?.release()
                holder.player = null
            }
            is ImageVH -> {
                holder.image.setImageDrawable(null)
            }
        }
    }
}