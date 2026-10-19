package com.dkc.fileserverclient

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

    /** 所有已创建 ExoPlayer 的 VideoVH，用于统一 pause / release */
    private val activeVideoHolders = mutableListOf<VideoVH>()

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
                val isGif = item.name.endsWith(".gif", ignoreCase = true)
                holder.progress.visibility = View.VISIBLE
                // ★ 走 Coil 全局单例（MusicApplication 已注册 GifDecoder），
                //   GIF 需关闭硬件 Bitmap，否则部分设备会退化成静态首帧
                holder.image.load(url) {
                    if (isGif) allowHardware(false)
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
            if (holder !in activeVideoHolders) activeVideoHolders.add(holder)
        } else {
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
        // 只有 player 已存在时才走轻量更新；player 为 null 必须 fall through
        if (payloads.contains(PAYLOAD_PLAY_STATE) && holder is VideoVH && holder.player != null) {
            val shouldPlay = position == currentPage
            holder.player?.playWhenReady = shouldPlay
            if (!shouldPlay) holder.player?.pause()
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /** 切换当前页：只通知旧页和新页两个视频页，不触发全量刷新 */
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
                activeVideoHolders.remove(holder)
                holder.playerView.player = null
                holder.player?.release()
                holder.player = null
            }
            is ImageVH -> holder.image.setImageDrawable(null)
        }
    }

    // ==================== 供 Activity 生命周期调用 ====================

    /** 暂停所有视频，不释放资源（onPause 用） */
    fun pauseAllVideos() {
        activeVideoHolders.forEach { it.player?.pause() }
    }

    /** 释放所有视频播放器（onDestroy 用），防止后台继续出声 */
    fun releaseAllVideos() {
        activeVideoHolders.toList().forEach {
            it.playerView.player = null
            it.player?.release()
            it.player = null
        }
        activeVideoHolders.clear()
    }

    /** 恢复当前页视频播放（如需从后台返回自动续播，可在 onResume 调用） */
    fun resumeCurrentVideo() {
        activeVideoHolders.firstOrNull {
            it.absoluteAdapterPosition == currentPage
        }?.player?.play()
    }
}