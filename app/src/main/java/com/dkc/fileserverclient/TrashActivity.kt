package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.net.URLEncoder

class TrashActivity : AppCompatActivity() {

    private lateinit var serverUrl: String
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var btnEmptyTrash: Button

    private val fileServerService by lazy { FileServerService(this) }
    private val adapter = TrashAdapter(
        mutableListOf(),
        ::onRestore,
        ::onPermanentDelete,
        ::previewTrashItem   // 预览回调
    )
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (serverUrl.isEmpty()) {
            finish()
            return
        }

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        emptyHint = findViewById(R.id.emptyHint)
        btnEmptyTrash = findViewById(R.id.btnEmptyTrash)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnEmptyTrash.setOnClickListener {
            showConfirmDialog("清空回收站", "确定要永久清空回收站中的所有文件吗？此操作不可恢复！") {
                emptyTrash()
            }
        }

        loadTrashList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    // ---------- 数据加载 ----------
    private fun loadTrashList() {
        coroutineScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    fileServerService.getTrashList(serverUrl)
                }
                if (list.isEmpty()) {
                    emptyHint.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyHint.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.updateList(list)
                }
            } catch (e: Exception) {
                Log.e("TrashActivity", "加载回收站失败", e)
                showToast("加载回收站失败: ${e.message}")
            }
        }
    }

    // ---------- 操作回调 ----------
    private fun onRestore(record: TrashRecord) {
        showConfirmDialog("恢复文件", "确定要恢复 '${record.originalPath}' 吗？") {
            coroutineScope.launch {
                try {
                    val success = withContext(Dispatchers.IO) {
                        fileServerService.restoreFromTrash(serverUrl, record.id)
                    }
                    if (success) {
                        showToast("恢复成功")
                        loadTrashList()
                    } else {
                        showToast("恢复失败，请重试")
                    }
                } catch (e: Exception) {
                    Log.e("TrashActivity", "恢复失败", e)
                    showToast("恢复失败: ${e.message}")
                }
            }
        }
    }

    private fun onPermanentDelete(record: TrashRecord) {
        showConfirmDialog("永久删除", "确定要永久删除 '${record.originalPath}' 吗？此操作不可恢复！") {
            coroutineScope.launch {
                try {
                    val success = withContext(Dispatchers.IO) {
                        fileServerService.permanentDeleteFromTrash(serverUrl, record.id)
                    }
                    if (success) {
                        showToast("已永久删除")
                        loadTrashList()
                    } else {
                        showToast("删除失败，请重试")
                    }
                } catch (e: Exception) {
                    Log.e("TrashActivity", "永久删除失败", e)
                    showToast("删除失败: ${e.message}")
                }
            }
        }
    }

    private fun emptyTrash() {
        coroutineScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    fileServerService.emptyTrash(serverUrl)
                }
                if (success) {
                    showToast("回收站已清空")
                    loadTrashList()
                } else {
                    showToast("清空失败，请重试")
                }
            } catch (e: Exception) {
                Log.e("TrashActivity", "清空回收站失败", e)
                showToast("清空失败: ${e.message}")
            }
        }
    }

    // ---------- 预览逻辑 ----------
    private fun previewTrashItem(record: TrashRecord) {
        try {
            val previewUrl = "${serverUrl.removeSuffix("/")}/api/fileserver/trash/preview/${record.id}"
            val fileName = record.originalPath.substringAfterLast('/').ifEmpty { "未命名" }
            val ext = record.originalPath.substringAfterLast('.').lowercase()

            // 添加日志，确认 URL 正确
            Log.d("TrashPreview", "预览 URL: $previewUrl")

            val fileType = when (ext) {
                "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image"
                "mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v" -> "video"
                "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma" -> "audio"
                "txt", "log", "json", "xml", "csv", "md",
                "html", "htm", "css", "js", "java", "kt", "py" -> "text"
                else -> "general"
            }

            when (fileType) {
                "image" -> {
                    startActivity(Intent(this, SingleImageActivity::class.java).apply {
                        putExtra("IMAGE_URL", previewUrl)
                        // 可选的标题
                        putExtra("FILE_NAME", fileName)
                    })
                }
                "video" -> {
                    startActivity(Intent(this, VideoPlayerActivityV2::class.java).apply {
                        putExtra("FILE_NAME", fileName)
                        putExtra("FILE_URL", previewUrl)
                        putExtra("FILE_TYPE", "video")
                        putExtra("FILE_PATH", record.originalPath)
                        putExtra("SERVER_URL", serverUrl)
                        putExtra("AUTO_PLAY_ENABLED", false)
                    })
                }
                "audio" -> {
                    val audioTrack = AudioTrack(
                        id = "trash_${record.id}",
                        name = fileName,
                        url = previewUrl,
                        serverUrl = serverUrl,
                        path = record.originalPath,
                        duration = 0L,
                        artist = "回收站",
                        album = "",
                        title = fileName,
                        coverUrl = null,
                        fileExtension = ext,
                        sizeFormatted = ""
                    )
                    startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
                        putExtra("AUDIO_TRACK", audioTrack)
                        putExtra("AUDIO_TRACKS", arrayListOf(audioTrack))
                        putExtra("CURRENT_INDEX", 0)
                        putExtra("SERVER_URL", serverUrl)
                        putExtra("FILE_PATH", record.originalPath)
                        putExtra("FILE_NAME", fileName)
                        putExtra("CUSTOM_AUDIO_URL", previewUrl)
                    })
                }
                "text" -> {
                    startActivity(Intent(this, TextPreviewActivity::class.java).apply {
                        putExtra("FILE_NAME", fileName)
                        putExtra("FILE_URL", previewUrl)
                        putExtra("FILE_PATH", record.originalPath)
                    })
                }
                else -> {
                    startActivity(Intent(this, GeneralPreviewActivity::class.java).apply {
                        putExtra("FILE_NAME", fileName)
                        putExtra("FILE_URL", previewUrl)
                        putExtra("FILE_TYPE", fileType)
                        putExtra("FILE_PATH", record.originalPath)
                        putExtra("SERVER_URL", serverUrl)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("TrashActivity", "预览失败", e)
            showToast("预览失败: ${e.message}")
        }
    }


    // ---------- 工具方法 ----------
    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}