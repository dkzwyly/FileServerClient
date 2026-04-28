package com.dkc.fileserverclient

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_IMAGE  = 1
    }

    private var unsafeImageLoader: ImageLoader? = null
    private var currentServerUrl = serverUrl

    // ---------- ViewHolder ----------
    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dateText: TextView = view.findViewById(R.id.tv_date_header)
        fun bind(dateLabel: String) {
            dateText.text = dateLabel
        }
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.galleryImageView)
        val fileName: TextView = view.findViewById(R.id.galleryFileName)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val selectionCheck: ImageView = view.findViewById(R.id.selectionCheck)

        fun bindImage(
            imageItem: FileSystemItem,
            serverUrl: String,
            imageLoader: ImageLoader,
            isMultiSelectionMode: () -> Boolean,
            isItemSelected: (String) -> Boolean,
            onImageClick: (FileSystemItem) -> Unit,
            onImageLongClick: (FileSystemItem) -> Unit
        ) {
            fileName.text = imageItem.name
            updateSelectionUI(imageItem, isMultiSelectionMode, isItemSelected)
            loadImageThumbnail(imageItem, serverUrl, imageLoader)

            itemView.setOnClickListener { onImageClick(imageItem) }
            itemView.setOnLongClickListener {
                onImageLongClick(imageItem)
                true
            }
        }

        private fun updateSelectionUI(
            imageItem: FileSystemItem,
            isMultiSelectionMode: () -> Boolean,
            isItemSelected: (String) -> Boolean
        ) {
            val selected = isItemSelected(imageItem.path)
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

        private fun loadImageThumbnail(
            imageItem: FileSystemItem,
            serverUrl: String,
            imageLoader: ImageLoader
        ) {
            try {
                val encodedPath = java.net.URLEncoder.encode(imageItem.path, "UTF-8")
                val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"
                imageView.load(thumbnailUrl, imageLoader) {
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

    // ---------- Adapter methods ----------
    private fun getImageLoader(context: android.content.Context): ImageLoader {
        if (unsafeImageLoader == null) {
            unsafeImageLoader = ImageLoader.Builder(context)
                .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
                .build()
        }
        return unsafeImageLoader!!
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is GalleryItem.DateHeader -> VIEW_TYPE_HEADER
            is GalleryItem.ImageEntry -> VIEW_TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_date_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image_gallery, parent, false)
                ImageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GalleryItem.DateHeader -> {
                (holder as HeaderViewHolder).bind(item.dateText)
            }
            is GalleryItem.ImageEntry -> {
                val imgHolder = holder as ImageViewHolder
                imgHolder.bindImage(
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
        when (item) {
            is GalleryItem.ImageEntry -> {
                val imgHolder = holder as ImageViewHolder
                // 只更新选择状态，避免重新加载图片
                if (payloads.any { it == "selection" }) {
                    imgHolder.bindImage(
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
            }
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ImageViewHolder) {
            holder.imageView.setImageDrawable(null)
            holder.selectionCheck.clearColorFilter()
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