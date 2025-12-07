package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.request.CachePolicy

class ImageGalleryAdapter(
    private val serverUrl: String,
    private val imageItems: List<FileSystemItem>,
    private val isMultiSelectionMode: () -> Boolean,
    private val isItemSelected: (String) -> Boolean,
    private val onImageClick: (FileSystemItem) -> Unit,
    private val onImageLongClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<ImageGalleryAdapter.ImageViewHolder>() {

    // 将ImageLoader的创建移到类内部，但不在属性初始化时使用itemView
    private var unsafeImageLoader: ImageLoader? = null

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.galleryImageView)
        val fileName: TextView = view.findViewById(R.id.galleryFileName)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        val selectionCheck: ImageView = view.findViewById(R.id.selectionCheck)
    }

    // 获取ImageLoader的辅助方法
    private fun getImageLoader(context: Context): ImageLoader {
        if (unsafeImageLoader == null) {
            unsafeImageLoader = ImageLoader.Builder(context)
                .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
                .build()
        }
        return unsafeImageLoader!!
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_gallery, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val imageItem = imageItems[position]

        // 设置文件名
        holder.fileName.text = imageItem.name

        // 更新选择状态
        updateSelectionUI(holder, imageItem)

        // 重新加载图片缩略图
        loadImageThumbnail(holder, imageItem)

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onImageClick(imageItem)
        }

        // 设置长按事件
        holder.itemView.setOnLongClickListener {
            onImageLongClick(imageItem)
            true // 返回true表示已处理长按事件
        }
    }

    override fun getItemCount(): Int = imageItems.size

    private fun loadImageThumbnail(holder: ImageViewHolder, imageItem: FileSystemItem) {
        try {
            val encodedPath = java.net.URLEncoder.encode(imageItem.path, "UTF-8")
            val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"

            Log.d("ImageGallery", "加载缩略图: ${imageItem.name}, URL: $thumbnailUrl")

            // 使用Coil的load扩展函数直接加载图片
            holder.imageView.load(thumbnailUrl, getImageLoader(holder.itemView.context)) {
                placeholder(R.drawable.ic_image_placeholder)
                error(R.drawable.ic_image_placeholder)
                memoryCachePolicy(CachePolicy.ENABLED)
                diskCachePolicy(CachePolicy.ENABLED)
                size(300, 300) // 限制缩略图大小
                // Coil会自动处理ViewHolder复用，取消之前的请求
                // crossfade(true) // 如果需要淡入淡出效果可以添加
            }

        } catch (e: Exception) {
            Log.e("ImageGallery", "缩略图加载异常: ${e.message}", e)
            holder.imageView.setImageResource(R.drawable.ic_image_placeholder)
        }
    }

    // 更新选择状态UI
    private fun updateSelectionUI(holder: ImageViewHolder, imageItem: FileSystemItem) {
        val isSelected = isItemSelected(imageItem.path)
        val isMultiMode = isMultiSelectionMode()

        // 设置选择状态
        if (isMultiMode) {
            holder.selectionOverlay.isVisible = true
            holder.selectionCheck.isVisible = true

            // 设置选中状态
            if (isSelected) {
                holder.selectionOverlay.alpha = 0.6f
                holder.selectionCheck.setImageResource(android.R.drawable.checkbox_on_background)
                // 使用 setColorFilter 替代 tint
                holder.selectionCheck.setColorFilter(Color.parseColor("#2196F3")) // 蓝色
            } else {
                holder.selectionOverlay.alpha = 0.3f
                holder.selectionCheck.setImageResource(android.R.drawable.checkbox_off_background)
                // 使用 setColorFilter 替代 tint
                holder.selectionCheck.setColorFilter(Color.WHITE)
            }
        } else {
            holder.selectionOverlay.isVisible = false
            holder.selectionCheck.isVisible = false
            // 清除选择状态的颜色
            holder.selectionCheck.clearColorFilter()
        }
    }

    override fun onViewRecycled(holder: ImageViewHolder) {
        super.onViewRecycled(holder)
        // 清除图片，防止复用导致的图片错位
        holder.imageView.setImageDrawable(null)
        // 清除颜色过滤器
        holder.selectionCheck.clearColorFilter()
    }

    // 清理资源
    fun dispose() {
        unsafeImageLoader?.shutdown()
        unsafeImageLoader = null
    }
}
