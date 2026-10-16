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
        private const val TAG = "ImageGallery"
    }

    private var currentServerUrl = serverUrl

    // ---------- ViewHolder ----------
    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.tv_date_header)
        fun bind(dateLabel: String) { dateText.text = dateLabel }
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.galleryImageView)
        val fileName: TextView = view.findViewById(R.id.galleryFileName)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val selectionCheck: ImageView = view.findViewById(R.id.selectionCheck)

        /** 视频播放图标（动态加在 imageView 的父 FrameLayout 里，居中覆盖） */
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
            isMultiSelectionMode: () -> Boolean,
            isItemSelected: (String) -> Boolean,
            onImageClick: (FileSystemItem) -> Unit,
            onImageLongClick: (FileSystemItem) -> Unit
        ) {
            fileName.text = item.name
            videoPlayIcon.visibility = if (item.isVideo) View.VISIBLE else View.GONE

            updateSelectionUI(item, isMultiSelectionMode, isItemSelected)
            loadThumbnail(item, serverUrl)

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
         * 统一走 ThumbnailLoader：
         * - 图片 → /thumbnail/，缓存为 .thumb
         * - 视频 → /video-thumbnail/，缓存为 .jpg（占位图期间是 .svg）
         * 磁盘缓存在 filesDir/thumbnails，上限 20GB。
         */
        private fun loadThumbnail(item: FileSystemItem, serverUrl: String) {
            try {
                if (item.isVideo) {
                    ThumbnailLoader.loadVideoThumbnail(
                        imageView = imageView,
                        serverUrl = serverUrl,
                        videoPath = item.path
                    )
                } else {
                    ThumbnailLoader.loadImageThumbnail(
                        imageView = imageView,
                        imageItem = item,
                        serverUrl = serverUrl
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "缩略图加载异常: ${e.message}", e)
                imageView.setImageResource(R.drawable.ic_image_placeholder)
            }
        }
    }

    // ---------- Adapter methods ----------

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
        // ImageLoader 由 ThumbnailLoader 全局管理，无需释放
    }

    fun updateServerUrl(newUrl: String) {
        currentServerUrl = newUrl
    }
}