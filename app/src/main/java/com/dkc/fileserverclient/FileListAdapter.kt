@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import androidx.core.content.ContextCompat

class FileListAdapter(
    private val context: Context,
    private val serverUrl: String,
    private val onItemClick: (FileSystemItem) -> Unit,
    private val onDeleteClick: (FileSystemItem) -> Unit,
    private val onDirectoryLongPress: (FileSystemItem) -> Unit,  // 文件夹长按（进入选择）
    private val onItemLongPress: (FileSystemItem) -> Unit,       // 文件长按（进入选择）
    private val onItemToggle: (FileSystemItem) -> Unit           // 选择模式下切换选中
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val unsafeImageLoader by lazy {
        ImageLoader.Builder(context)
            .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
            .build()
    }

    // ---------- DiffUtil ----------
    private val diffCallback = object : DiffUtil.ItemCallback<FileSystemItem>() {
        override fun areItemsTheSame(oldItem: FileSystemItem, newItem: FileSystemItem): Boolean {
            return oldItem.path == newItem.path
        }
        override fun areContentsTheSame(oldItem: FileSystemItem, newItem: FileSystemItem): Boolean {
            return oldItem.name == newItem.name &&
                    oldItem.isDirectory == newItem.isDirectory &&
                    oldItem.size == newItem.size &&
                    oldItem.lastModified == newItem.lastModified
        }
    }
    private val differ = AsyncListDiffer(this, diffCallback)

    // ---------- 选择模式状态 ----------
    private var selectionMode = false
    private var selectedItems = setOf<FileSystemItem>()

    fun setSelectionMode(mode: Boolean, items: Set<FileSystemItem>) {
        selectionMode = mode
        selectedItems = items
        notifyDataSetChanged()
    }

    fun submitList(list: List<FileSystemItem>) {
        differ.submitList(list)
    }

    // ---------- 视图类型 ----------
    companion object {
        private const val TYPE_DIRECTORY = 0
        private const val TYPE_FILE = 1
    }

    // ---------- Base ViewHolder ----------
    abstract class BaseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(item: FileSystemItem, selectionMode: Boolean, isSelected: Boolean)
        open fun clear() {}
    }

    // ---------- 文件夹 ViewHolder ----------
    inner class DirectoryViewHolder(view: View) : BaseViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileInfo: TextView = view.findViewById(R.id.fileInfo)
        val previewButton: ImageButton = view.findViewById(R.id.previewButton)
        val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

        override fun bind(item: FileSystemItem, selectionMode: Boolean, isSelected: Boolean) {
            fileName.text = item.displayName
            fileInfo.text = "目录"
            fileIcon.setImageResource(R.drawable.ic_folder)
            previewButton.visibility = View.GONE
            downloadButton.visibility = View.GONE
            deleteButton.visibility = View.GONE

            // 选择模式显示
            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            itemView.setBackgroundColor(
                if (selectionMode && isSelected) ContextCompat.getColor(context, R.color.selected_item_background)
                else android.graphics.Color.TRANSPARENT
            )

            // 点击事件：选择模式切换选中，否则进入目录
            itemView.setOnClickListener {
                if (selectionMode) {
                    onItemToggle(item)
                } else {
                    onItemClick(item)
                }
            }
            // 长按：进入选择模式（如果尚未进入）
            itemView.setOnLongClickListener {
                onItemLongPress(item)
                true
            }
        }
    }

    // ---------- 文件 ViewHolder ----------
    inner class FileViewHolder(view: View) : BaseViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val fileIcon: ImageView = view.findViewById(R.id.fileIcon)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileInfo: TextView = view.findViewById(R.id.fileInfo)
        val previewButton: ImageButton = view.findViewById(R.id.previewButton)
        val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

        private var currentLoadPath: String? = null

        override fun bind(item: FileSystemItem, selectionMode: Boolean, isSelected: Boolean) {
            currentLoadPath = item.path
            fileName.text = item.displayName
            fileInfo.text = "${item.sizeFormatted} • ${formatDate(item.lastModified)}"

            val isPreviewable = isPreviewableFile(item)
            previewButton.visibility = if (isPreviewable && !item.isAudio) View.VISIBLE else View.GONE
            downloadButton.visibility = View.VISIBLE
            deleteButton.visibility = View.VISIBLE

            if (item.isImage) {
                loadImageThumbnail(item)
            } else {
                fileIcon.setImageResource(getFileIconRes(item))
            }

            // 选择模式
            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            itemView.setBackgroundColor(
                if (selectionMode && isSelected) ContextCompat.getColor(context, R.color.selected_item_background)
                else android.graphics.Color.TRANSPARENT
            )

            // 点击：选择模式切换选中，否则进入预览
            itemView.setOnClickListener {
                if (selectionMode) {
                    onItemToggle(item)
                } else {
                    if (item.isImage) {
                        showPreview(item, itemView.context)
                    } else {
                        onItemClick(item)
                    }
                }
            }
            // 长按进入选择模式
            itemView.setOnLongClickListener {
                onItemLongPress(item)
                true
            }

            // 按钮点击
            previewButton.setOnClickListener { showPreview(item, itemView.context) }
            downloadButton.setOnClickListener { downloadFile(item, itemView.context) }
            deleteButton.setOnClickListener { onDeleteClick(item) }
        }

        override fun clear() {
            currentLoadPath = null
            fileIcon.setImageDrawable(null)
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
                val fileType = FileTypeUtils.getFileType(item)
                val fileUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/preview/${java.net.URLEncoder.encode(item.path, "UTF-8")}"
                when (fileType) {
                    "image" -> {
                        val intent = Intent(context, ImageActivity::class.java).apply {
                            putExtra("FILE_NAME", item.name)
                            putExtra("FILE_URL", fileUrl)
                            putExtra("FILE_TYPE", "image")
                            putExtra("FILE_PATH", item.path)
                            putExtra("SERVER_URL", serverUrl)
                            putExtra("CURRENT_PATH", "")
                        }
                        context.startActivity(intent)
                    }
                    "audio" -> {
                        val audioTrack = AudioTrack.fromFileSystemItem(item, serverUrl)
                        val intent = Intent(context, AudioPlayerActivity::class.java).apply {
                            putExtra("AUDIO_TRACK", audioTrack)
                            putExtra("AUDIO_TRACKS", arrayListOf(audioTrack))
                            putExtra("CURRENT_INDEX", 0)
                            putExtra("SERVER_URL", serverUrl)
                            putExtra("FILE_PATH", item.path)
                            putExtra("FILE_NAME", item.name)
                            putExtra(PlaylistDetailActivity.EXTRA_PLAY_MODE, PlaylistDetailActivity.MODE_LIST)
                        }
                        context.startActivity(intent)
                    }
                    "text" -> {
                        val intent = Intent(context, TextPreviewActivity::class.java).apply {
                            putExtra("FILE_NAME", item.name)
                            putExtra("FILE_URL", fileUrl)
                            putExtra("FILE_PATH", item.path)
                        }
                        context.startActivity(intent)
                    }
                    "video" -> {
                        val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                            putExtra("FILE_NAME", item.name)
                            putExtra("FILE_URL", fileUrl)
                            putExtra("FILE_TYPE", "video")
                            putExtra("FILE_PATH", item.path)
                            putExtra("SERVER_URL", serverUrl)
                            putExtra("AUTO_PLAY_ENABLED", false)
                        }
                        context.startActivity(intent)
                    }
                    else -> {
                        val intent = Intent(context, GeneralPreviewActivity::class.java).apply {
                            putExtra("FILE_NAME", item.name)
                            putExtra("FILE_URL", fileUrl)
                            putExtra("FILE_TYPE", fileType)
                            putExtra("FILE_PATH", item.path)
                            putExtra("SERVER_URL", serverUrl)
                        }
                        context.startActivity(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e("FileListAdapter", "预览失败", e)
                Toast.makeText(context, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
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

        private fun formatDate(dateString: String): String = dateString.take(10)

        private fun loadImageThumbnail(item: FileSystemItem) {
            try {
                val encodedPath = java.net.URLEncoder.encode(item.path, "UTF-8")
                val thumbnailUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/thumbnail/$encodedPath"
                val request = ImageRequest.Builder(itemView.context)
                    .data(thumbnailUrl)
                    .target { drawable ->
                        if (currentLoadPath == item.path) {
                            fileIcon.setImageDrawable(drawable)
                        }
                    }
                    .placeholder(R.drawable.ic_image)
                    .error(R.drawable.ic_image)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
                unsafeImageLoader.enqueue(request)
            } catch (e: Exception) {
                Log.e("ThumbnailDebug", "缩略图加载异常", e)
                if (currentLoadPath == item.path) {
                    fileIcon.setImageResource(R.drawable.ic_image)
                }
            }
        }
    }

    // ---------- Adapter 重写 ----------
    override fun getItemViewType(position: Int): Int {
        return if (differ.currentList[position].isDirectory) TYPE_DIRECTORY else TYPE_FILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_list, parent, false)
        return when (viewType) {
            TYPE_DIRECTORY -> DirectoryViewHolder(view)
            TYPE_FILE -> FileViewHolder(view)
            else -> throw IllegalArgumentException("未知视图类型")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = differ.currentList[position]
        val isSelected = selectedItems.contains(item)
        when (holder) {
            is DirectoryViewHolder -> holder.bind(item, selectionMode, isSelected)
            is FileViewHolder -> holder.bind(item, selectionMode, isSelected)
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        (holder as? BaseViewHolder)?.clear()
    }
}