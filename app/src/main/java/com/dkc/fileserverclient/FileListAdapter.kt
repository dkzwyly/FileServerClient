@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy

class FileListAdapter(
    private val context: Context,
    private val serverUrl: String,
    private val items: List<FileSystemItem>,
    private val onItemClick: (FileSystemItem) -> Unit,
    private val onDeleteClick: (FileSystemItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // 使用统一的ImageLoader实例，避免重复创建
    private val unsafeImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
            .build()
    }

    // 视图类型常量
    companion object {
        private const val TYPE_DIRECTORY = 0
        private const val TYPE_FILE = 1
    }

    abstract class BaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(item: FileSystemItem, position: Int)
    }

    class DirectoryViewHolder(view: View) : BaseViewHolder(view) {
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileInfo: TextView = view.findViewById(R.id.fileInfo)
        val previewButton: ImageButton = view.findViewById(R.id.previewButton)
        val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

        override fun bind(item: FileSystemItem, position: Int) {
            // 文件夹处理 - 强制使用文件夹图标，不加载任何缩略图
            fileName.text = item.displayName
            fileInfo.text = "目录"

            // 关键修复：立即设置文件夹图标，不进行任何异步操作
            fileIcon.setImageResource(R.drawable.ic_folder)

            // 文件夹不应该有任何操作按钮
            previewButton.visibility = View.GONE
            downloadButton.visibility = View.GONE
            deleteButton.visibility = View.GONE

            // 确保按钮没有点击事件
            previewButton.setOnClickListener(null)
            downloadButton.setOnClickListener(null)
            deleteButton.setOnClickListener(null)
        }
    }

    class FileViewHolder(
        view: View,
        private val serverUrl: String,
        private val imageLoader: ImageLoader,
        private val onItemClick: (FileSystemItem) -> Unit,
        private val onDeleteClick: (FileSystemItem) -> Unit
    ) : BaseViewHolder(view) {
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileInfo: TextView = view.findViewById(R.id.fileInfo)
        val previewButton: ImageButton = view.findViewById(R.id.previewButton)
        val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

        // 添加标识来跟踪当前加载的路径
        var currentLoadPath: String? = null

        override fun bind(item: FileSystemItem, position: Int) {
            // 重置当前状态
            currentLoadPath = null

            // 文件处理 - 将大小和日期信息合并显示在fileInfo中
            fileName.text = item.displayName
            fileInfo.text = "${item.sizeFormatted} • ${formatDate(item.lastModified)}"

            // 设置按钮可见性
            val isPreviewable = isPreviewableFile(item)
            previewButton.visibility = if (isPreviewable) View.VISIBLE else View.GONE
            downloadButton.visibility = View.VISIBLE
            deleteButton.visibility = View.VISIBLE

            // 设置当前状态
            currentLoadPath = item.path

            // 处理图片文件缩略图
            if (item.isImage) {
                // 只有图片文件才加载缩略图
                loadImageThumbnail(item, position)
            } else {
                // 非图片文件使用文件类型图标 - 立即设置，不异步
                fileIcon.setImageResource(getFileIconRes(item))
            }

            // 设置按钮图标
            previewButton.setImageResource(R.drawable.ic_preview)
            downloadButton.setImageResource(R.drawable.ic_download)
            deleteButton.setImageResource(R.drawable.ic_delete)

            // 文件点击事件 - 图片直接预览，其他文件导航
            itemView.setOnClickListener {
                if (item.isImage) {
                    showPreview(item, itemView.context)
                } else {
                    onItemClick(item)
                }
            }

            // 预览按钮点击事件
            previewButton.setOnClickListener {
                showPreview(item, itemView.context)
            }

            // 下载按钮点击事件
            downloadButton.setOnClickListener {
                downloadFile(item, itemView.context)
            }

            // 删除按钮点击事件
            deleteButton.setOnClickListener {
                onDeleteClick(item)
            }
        }

        private fun getFileIconRes(item: FileSystemItem): Int {
            return when {
                item.isVideo -> R.drawable.ic_video
                item.isAudio -> R.drawable.ic_audio
                item.extension in listOf(".pdf") -> R.drawable.ic_pdf
                item.extension in listOf(".doc", ".docx") -> R.drawable.ic_document
                item.extension in listOf(".xls", ".xlsx") -> R.drawable.ic_spreadsheet
                item.extension in listOf(".zip", ".rar", ".7z", ".tar", ".gz") -> R.drawable.ic_archive
                else -> R.drawable.ic_file
            }
        }

        private fun isPreviewableFile(item: FileSystemItem): Boolean {
            return when {
                item.isVideo || item.isAudio -> true
                item.extension in listOf(".txt", ".log", ".json", ".xml", ".csv", ".md",
                    ".html", ".htm", ".css", ".js", ".java", ".kt", ".py") -> true
                else -> false
            }
        }

        private fun showPreview(item: FileSystemItem, context: Context) {
            try {
                val fileType = getFileType(item)
                val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${java.net.URLEncoder.encode(item.path, "UTF-8")}"

                Log.d("Preview", "预览文件: ${item.name}, 类型: $fileType")

                val intent = Intent(context, PreviewActivity::class.java).apply {
                    putExtra("FILE_NAME", item.name)
                    putExtra("FILE_URL", fileUrl)
                    putExtra("FILE_TYPE", fileType)
                    putExtra("FILE_PATH", item.path)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("Preview", "预览文件失败: ${e.message}", e)
                Toast.makeText(context, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun getFileType(item: FileSystemItem): String {
            return when {
                item.isVideo -> "video"
                item.isAudio -> "audio"
                item.isImage -> "image"
                item.extension in listOf(".txt", ".log", ".json", ".xml", ".csv", ".md",
                    ".html", ".htm", ".css", ".js", ".java", ".kt", ".py") -> "text"
                else -> "general"
            }
        }

        private fun downloadFile(item: FileSystemItem, context: Context) {
            try {
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val downloadUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/download/$encodedPath"

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                context.startActivity(intent)

                Toast.makeText(context, "开始下载: ${item.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun formatDate(dateString: String): String {
            return if (dateString.length > 10) {
                dateString.substring(0, 10)
            } else {
                dateString
            }
        }

        private fun loadImageThumbnail(item: FileSystemItem, position: Int) {
            try {
                // 构建缩略图URL
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"

                Log.d("ThumbnailDebug", "开始加载缩略图: ${item.name}, URL: $thumbnailUrl")

                // 使用 Coil 加载缩略图
                val request = ImageRequest.Builder(itemView.context)
                    .data(thumbnailUrl)
                    .target { drawable ->
                        // 检查当前加载路径是否匹配
                        if (currentLoadPath == item.path) {
                            fileIcon.setImageDrawable(drawable)
                            Log.d("ThumbnailDebug", "缩略图加载成功: ${item.name}")
                        } else {
                            Log.d("ThumbnailDebug", "缩略图加载完成但路径不匹配: ${item.name}")
                        }
                    }
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()

                // 执行请求
                imageLoader.enqueue(request)

            } catch (e: Exception) {
                Log.e("ThumbnailDebug", "缩略图加载异常: ${e.message}", e)
                // 只在当前加载路径匹配时设置错误图标
                if (currentLoadPath == item.path) {
                    fileIcon.setImageResource(R.drawable.ic_image)
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isDirectory) TYPE_DIRECTORY else TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_list, parent, false)

        return when (viewType) {
            TYPE_DIRECTORY -> DirectoryViewHolder(view)
            TYPE_FILE -> FileViewHolder(view, serverUrl, unsafeImageLoader, onItemClick, onDeleteClick)
            else -> throw IllegalArgumentException("未知的视图类型: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]

        when (holder) {
            is DirectoryViewHolder -> {
                holder.bind(item, position)
                // 设置文件夹点击事件
                holder.itemView.setOnClickListener { onItemClick(item) }
            }
            is FileViewHolder -> holder.bind(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is FileViewHolder -> {
                // 重置文件视图的状态
                holder.fileIcon.setImageDrawable(null)
                holder.currentLoadPath = null
            }
            is DirectoryViewHolder -> {
                // 重置文件夹视图的状态
                holder.fileIcon.setImageDrawable(null)
            }
        }
    }
}