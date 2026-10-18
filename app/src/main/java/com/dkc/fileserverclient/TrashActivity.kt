package com.dkc.fileserverclient

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*

class TrashActivity : AppCompatActivity() {

    private lateinit var serverUrl: String
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: View
    private lateinit var btnEmptyTrash: Button
    private lateinit var adapter: TrashAdapter

    private val fileServerService by lazy { FileServerService(this) }
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 当前列表快照，用于「清空回收站」时逐个删本地缓存
    private var currentRecords: List<TrashRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)

        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (serverUrl.isEmpty()) {
            finish()
            return
        }

        Log.d("TrashActivity", "onCreate: serverUrl=[$serverUrl]")

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        emptyHint = findViewById(R.id.emptyHint)
        btnEmptyTrash = findViewById(R.id.btnEmptyTrash)

        ThumbnailLoader.init(this)
        migrateThumbnailKeyFormat()

        adapter = TrashAdapter(
            mutableListOf(),
            serverUrl,
            ::onRestore,
            ::onPermanentDelete
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnEmptyTrash.setOnClickListener {
            showConfirmDialog("清空回收站", "确定要永久清空回收站中的所有文件吗？此操作不可恢复！") {
                emptyTrash()
            }
        }

        loadTrashList()
    }

    /**
     * 一次性迁移：旧 key 格式（纯 md5 + 扩展名）无法被 deleteByPath 精确管理，
     * 升级到新格式后清空一次。用 SharedPreferences 标记避免重复清。
     */
    private fun migrateThumbnailKeyFormat() {
        val prefs = getSharedPreferences("thumb_cache", Context.MODE_PRIVATE)
        if (prefs.getBoolean("key_format_v2", false)) return

        Log.d("TrashActivity", "migrateThumbnailKeyFormat: 首次升级，清空旧 key 格式缓存")
        ThumbnailLoader.clearAllCache()
        prefs.edit().putBoolean("key_format_v2", true).apply()
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

                Log.d("TrashActivity", "loadTrashList: 拿到 ${list.size} 条")
                list.take(5).forEach {
                    Log.d("TrashActivity", "  记录: id=${it.id} path=[${it.originalPath}] dir=${it.isDirectory}")
                }

                currentRecords = list

                if (list.isEmpty()) {
                    emptyHint.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    btnEmptyTrash.isEnabled = false
                    btnEmptyTrash.alpha = 0.5f
                } else {
                    emptyHint.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    btnEmptyTrash.isEnabled = true
                    btnEmptyTrash.alpha = 1f
                    adapter.updateList(list)
                }
            } catch (e: Exception) {
                Log.e("TrashActivity", "加载回收站失败", e)
                showToast("加载回收站失败: $e")
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
                        // 恢复不改动本地缩略图缓存：原路径可能变（重名加后缀），
                        // 但删除旧 key 后下次浏览会重新生成，这里保持不动最稳妥。
                        showToast("恢复成功")
                        loadTrashList()
                    } else {
                        showToast("恢复失败，请重试")
                    }
                } catch (e: Exception) {
                    Log.e("TrashActivity", "恢复失败", e)
                    showToast("恢复失败: $e")
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
                        // 永久删除成功 → 同步清理本地缩略图
                        ThumbnailLoader.deleteByPath(serverUrl, record.originalPath)
                        showToast("已永久删除")
                        loadTrashList()
                    } else {
                        showToast("删除失败，请重试")
                    }
                } catch (e: Exception) {
                    Log.e("TrashActivity", "永久删除失败", e)
                    showToast("删除失败: $e")
                }
            }
        }
    }

    private fun emptyTrash() {
        // 先在主线程记下当前列表，防止并发修改
        val pathsToDelete = currentRecords.map { it.originalPath }

        coroutineScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    fileServerService.emptyTrash(serverUrl)
                }
                if (success) {
                    // 服务端清空成功 → 同步清理本地缩略图
                    pathsToDelete.forEach { path ->
                        ThumbnailLoader.deleteByPath(serverUrl, path)
                    }
                    Log.d("TrashActivity", "emptyTrash: 已清理 ${pathsToDelete.size} 个文件的本地缓存")
                    showToast("回收站已清空")
                    loadTrashList()
                } else {
                    showToast("清空失败，请重试")
                }
            } catch (e: Exception) {
                Log.e("TrashActivity", "清空回收站失败", e)
                showToast("清空失败: $e")
            }
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