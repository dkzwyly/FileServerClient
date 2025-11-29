package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy

class ImageGalleryAdapter(
    private val serverUrl: String,
    private val imageItems: List<FileSystemItem>,
    private val onImageClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<ImageGalleryAdapter.ImageViewHolder>() {

    // 将ImageLoader的创建移到类内部，但不在属性初始化时使用itemView
    private var unsafeImageLoader: ImageLoader? = null

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.galleryImageView)
        val fileName: TextView = view.findViewById(R.id.galleryFileName)
        var currentLoadPath: String? = null
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

        // 重置当前状态
        holder.currentLoadPath = null

        // 设置文件名（可选显示）
        holder.fileName.text = imageItem.name

        // 加载图片缩略图
        loadImageThumbnail(holder, imageItem)

        // 设置点击事件
        holder.itemView.setOnClickListener {
            onImageClick(imageItem)
        }
    }

    override fun getItemCount(): Int = imageItems.size

    private fun loadImageThumbnail(holder: ImageViewHolder, imageItem: FileSystemItem) {
        try {
            val encodedPath = java.net.URLEncoder.encode(imageItem.path, "UTF-8")
            val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"

            holder.currentLoadPath = imageItem.path

            Log.d("ImageGallery", "加载缩略图: ${imageItem.name}, URL: $thumbnailUrl")

            // 使用 Coil 加载缩略图
            val request = ImageRequest.Builder(holder.itemView.context)
                .data(thumbnailUrl)
                .target(holder.imageView)
                .placeholder(R.drawable.ic_image_placeholder)
                .error(R.drawable.ic_image_placeholder)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .size(300, 300) // 限制缩略图大小
                .build()

            getImageLoader(holder.itemView.context).enqueue(request)

        } catch (e: Exception) {
            Log.e("ImageGallery", "缩略图加载异常: ${e.message}", e)
            if (holder.currentLoadPath == imageItem.path) {
                holder.imageView.setImageResource(R.drawable.ic_image_placeholder)
            }
        }
    }

    override fun onViewRecycled(holder: ImageViewHolder) {
        super.onViewRecycled(holder)
        holder.imageView.setImageDrawable(null)
        holder.currentLoadPath = null
    }

    // 清理资源
    fun dispose() {
        unsafeImageLoader?.shutdown()
        unsafeImageLoader = null
    }
}