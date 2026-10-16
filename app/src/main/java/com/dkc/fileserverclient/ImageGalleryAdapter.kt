package com.dkc.fileserverclient

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.request.CachePolicy

class ImageGalleryAdapter(
    private val serverUrl: String,
    private val isMultiSelectionMode: () -> Boolean,
    private val isItemSelected: (String) -> Boolean,
    private val onImageClick: (FileSystemItem) -> Unit,
    private val onImageLongClick: (FileSystemItem) -> Unit
) : ListAdapter<GalleryItem, RecyclerView.ViewHolder>(GalleryItemDiffCallback()) {

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_IMAGE  = 1
    }

    private var unsafeImageLoader: ImageLoader? = null
    private var currentServerUrl = serverUrl

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.tv_date_header)
        fun bind(dateLabel: String) { dateText.text = dateLabel }
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.galleryImageView)
        val fileName: TextView = view.findViewById(R.id.galleryFileName)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val selectionCheck: ImageView = view.findViewById(R.id.selectionCheck)

        /** 视频播放图标 */
        val videoPlayIcon: ImageView = ImageView(view.context).apply {
            id = View.generateViewId()
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            val pad = (10 * view.context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#80000000"))
            }
            visibility = View.GONE
        }

        init {
            // 把播放图标加到 imageView 的父容器（应为 FrameLayout）
            val parent = imageView.parent
            if (parent is FrameLayout) {
                val size = (48 * view.context.resources.displayMetrics.density).toInt()
                parent.addView(
                    videoPlayIcon,
                    FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                )
            }
        }

        fun bindImage(
            item: FileSystemItem,
            serverUrl: String,
            imageLoader: ImageLoader,
            isMultiSelectionMode: () -> Boolean,
            isItemSelected: (String) -> Boolean,
            onImageClick: (FileSystemItem) -> Unit,
            onImageLongClick: (FileSystemItem) -> Unit
        ) {
            fileName.text = item.name
            videoPlayIcon.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            updateSelectionUI(item, isMultiSelectionMode, isItemSelected)
            loadThumbnail(item, serverUrl, imageLoader)

            itemView.setOnClickListener { onImageClick(item) }
            itemView.setOnLongClickListener {
                onImageLongClick(item)
                true
            }
        }

        private fun updateSelectionUI(
            item: FileSystemItem,
            isMultiSelectionMode: () -> Boolean,
            isItemSelected: (String) -> Boolean
        ) {
            val selected = isItemSelected(item.path)
            val multiMode = isMultiSelectionMode()

            if (multiMode) {
                selectionOverlay.isVisible = true
                selectionCheck.isVisible = true
                if (selected) {
                    selectionOverlay.alpha = 0.6f
                    selectionCheck.setImageResource(R.drawable.ic_checkbox_checked)
                } else {
                    selectionOverlay.alpha = 0.3f
                    selectionCheck.setImageResource(R.drawable.ic_checkbox_unchecked)
                }
            } else {
                selectionOverlay.isVisible = false
                selectionCheck.isVisible = false
                selectionCheck.clearColorFilter()
            }
        }

        /**
         * 关键：图片走 /thumbnail/，视频走 /video-thumbnail/
         */
        private fun loadThumbnail(
            item: FileSystemItem,
            serverUrl: String,
            imageLoader: ImageLoader
        ) {
            try {
                val encoded = java.net.URLEncoder.encode(item.path, "UTF-8")
                val base = serverUrl.removeSuffix("/")
                val url = if (item.isVideo) {
                    "$base/api/fileserver/video-thumbnail/$encoded"
                } else {
                    "$base/api/fileserver/thumbnail/$encoded"
                }
                imageView.load(url, imageLoader) {
                    placeholder(R.drawable.ic_image_placeholder)
                    error(R.drawable.ic_image_placeholder)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    size(300, 300)
                }
            } catch (e: Exception) {
                Log.e("ImageGallery", "缩略图加载异常: ${e.message}", e)
                imageView.setImageResource(R.drawable.ic_image_placeholder)
            }
        }
    }

    private fun getImageLoader(context: android.content.Context): ImageLoader {
        if (unsafeImageLoader == null) {
            unsafeImageLoader = ImageLoader.Builder(context)
                .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
                .build()
        }
        return unsafeImageLoader!!
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is GalleryItem.DateHeader -> VIEW_TYPE_HEADER
        is GalleryItem.ImageEntry -> VIEW_TYPE_IMAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_date_header, parent, false)
                HeaderViewHolder(v)
            }
            else -> {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image_gallery, parent, false)
                ImageViewHolder(v)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GalleryItem.DateHeader -> (holder as HeaderViewHolder).bind(item.dateText)
            is GalleryItem.ImageEntry -> (holder as ImageViewHolder).bindImage(
                item.image,
                currentServerUrl,
                getImageLoader(holder.itemView.context),
                isMultiSelectionMode,
                isItemSelected,
                onImageClick,
                onImageLongClick
            )
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        if (payloads.isNullOrEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = getItem(position)
        if (item is GalleryItem.ImageEntry && holder is ImageViewHolder) {
            if (payloads.any { it == "selection" }) {
                holder.bindImage(
                    item.image,
                    currentServerUrl,
                    getImageLoader(holder.itemView.context),
                    isMultiSelectionMode,
                    isItemSelected,
                    onImageClick,
                    onImageLongClick
                )
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ImageViewHolder) {
            holder.imageView.setImageDrawable(null)
            holder.selectionCheck.clearColorFilter()
            holder.videoPlayIcon.visibility = View.GONE
        }
    }

    fun dispose() {
        unsafeImageLoader?.shutdown()
        unsafeImageLoader = null
    }

    fun updateServerUrl(newUrl: String) {
        currentServerUrl = newUrl
    }
}