package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class ImagePickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var doneButton: Button
    private lateinit var closeButton: ImageButton
    private val fileServerService by lazy { FileServerService(this) }
    private var currentServerUrl = ""
    private val selectedPaths = mutableSetOf<String>()
    private lateinit var adapter: ImageGalleryAdapter
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val imageGalleryPath = "data/图片"

    private val dateTakenMap = mutableMapOf<String, String?>()

    companion object {
        const val EXTRA_SELECTED_PATHS = "selected_paths"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_picker)

        currentServerUrl = intent.getStringExtra(ImageGalleryActivity.EXTRA_SERVER_URL) ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }

        recyclerView = findViewById(R.id.pickerRecyclerView)
        doneButton = findViewById(R.id.pickerDoneButton)
        closeButton = findViewById(R.id.pickerCloseButton)

        val gridLayoutManager = GridLayoutManager(this, 4)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    ImageGalleryAdapter.VIEW_TYPE_HEADER -> 4
                    else -> 1
                }
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        // ★ 关键：关掉默认动画（避免每次 toggle 交叉淡入），固定尺寸（避免异步 setImageDrawable 后重新 measure）
        recyclerView.itemAnimator = null
        recyclerView.setHasFixedSize(true)

        adapter = ImageGalleryAdapter(
            serverUrl = currentServerUrl,
            isMultiSelectionMode = { true },
            isItemSelected = { selectedPaths.contains(it) },
            onImageClick = { toggleSelection(it.path) },
            onImageLongClick = { toggleSelection(it.path) }
        )
        recyclerView.adapter = adapter

        doneButton.setOnClickListener {
            val result = Intent().apply {
                putStringArrayListExtra(EXTRA_SELECTED_PATHS, ArrayList(selectedPaths))
            }
            setResult(RESULT_OK, result)
            finish()
        }

        closeButton.setOnClickListener { finish() }

        loadAllImages()
    }

    private fun loadAllImages() {
        coroutineScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryPath, "dateTaken", "desc")
                }
                val mediaItems = items.filter { !it.isDirectory && (it.isImage || it.isVideo) }

                val paths = mediaItems.map { it.path }
                val dates = withContext(Dispatchers.IO) {
                    fileServerService.getBatchDateTaken(currentServerUrl, paths)
                }
                dateTakenMap.clear()
                dateTakenMap.putAll(dates)

                adapter.submitList(groupByDateTaken(mediaItems))
            } catch (e: Exception) {
                Log.e("ImagePicker", "load error", e)
            }
        }
    }

    private fun groupByDateTaken(items: List<FileSystemItem>): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()
        var currentLabel: String? = null

        for (item in items) {
            val dateStr = dateTakenMap[item.path]
            val isoDate: String? = when {
                !dateStr.isNullOrEmpty() -> dateStr.substringBefore("T").take(10)
                item.lastModified.isNotEmpty() -> item.lastModified.substringBefore("T").take(10)
                else -> null
            }

            val label: String = if (isoDate == null) {
                "未知日期"
            } else try {
                val cal = Calendar.getInstance().apply { time = dateFormat.parse(isoDate)!! }
                when {
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
                    cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                        SimpleDateFormat("M月d日", Locale.getDefault()).format(cal.time)
                    else ->
                        SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(cal.time)
                }
            } catch (e: Exception) { "未知日期" }

            if (label != currentLabel) {
                currentLabel = label
                result.add(GalleryItem.DateHeader(label))
            }
            result.add(GalleryItem.ImageEntry(item))
        }
        return result
    }

    /**
     * ★ 精确刷新：只通知被点击的那一项，且 payload="selection"，
     *   让 ImageGalleryAdapter 只更新勾选遮罩，不重新加载缩略图。
     */
    private fun toggleSelection(path: String) {
        if (selectedPaths.contains(path)) selectedPaths.remove(path) else selectedPaths.add(path)

        val pos = adapter.currentList.indexOfFirst {
            it is GalleryItem.ImageEntry && it.image.path == path
        }
        if (pos != -1) {
            adapter.notifyItemChanged(pos, "selection")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}