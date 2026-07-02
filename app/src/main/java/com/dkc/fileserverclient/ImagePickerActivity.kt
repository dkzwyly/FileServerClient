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

    // 用于日期分组的映射
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
        // 日期头占满整行
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    ImageGalleryAdapter.VIEW_TYPE_HEADER -> 4
                    else -> 1
                }
            }
        }
        recyclerView.layoutManager = gridLayoutManager

        // 强制多选模式，点击切换选中状态
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
                // 获取图片列表（按拍摄时间降序）
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, imageGalleryPath, "dateTaken", "desc")
                }
                val images = items.filter { !it.isDirectory && it.isImage }

                // 批量获取拍摄日期
                val paths = images.map { it.path }
                val dates = withContext(Dispatchers.IO) {
                    fileServerService.getBatchDateTaken(currentServerUrl, paths)
                }
                dateTakenMap.clear()
                dateTakenMap.putAll(dates)

                // 按拍摄日期分组（因为列表已按日期降序，直接分组即可）
                val grouped = groupByDateTaken(images)
                adapter.submitList(grouped)
            } catch (e: Exception) {
                Log.e("ImagePicker", "load error", e)
            }
        }
    }

    /**
     * 将图片列表按拍摄日期分组，生成带日期头的 GalleryItem 列表
     */
    private fun groupByDateTaken(images: List<FileSystemItem>): List<GalleryItem> {
        val result = mutableListOf<GalleryItem>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Calendar.getInstance()

        var currentLabel: String? = null
        for (image in images) {
            val dateStr = dateTakenMap[image.path]
            val label = if (dateStr.isNullOrEmpty()) {
                "未知日期"
            } else {
                try {
                    val isoDate = dateStr.substringBefore("T")
                    val cal = Calendar.getInstance().apply {
                        time = dateFormat.parse(isoDate)!!
                    }
                    when {
                        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "今天"
                        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) - 1 -> "昨天"
                        else -> {
                            if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
                                SimpleDateFormat("M月d日", Locale.getDefault()).format(cal.time)
                            } else {
                                SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(cal.time)
                            }
                        }
                    }
                } catch (e: Exception) {
                    "未知日期"
                }
            }

            if (label != currentLabel) {
                currentLabel = label
                result.add(GalleryItem.DateHeader(label))
            }
            result.add(GalleryItem.ImageEntry(image))
        }
        return result
    }

    private fun toggleSelection(path: String) {
        if (selectedPaths.contains(path)) selectedPaths.remove(path) else selectedPaths.add(path)
        adapter.notifyDataSetChanged() // 全量刷新（分组模式下刷新选择状态）
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        adapter.dispose()
    }
}