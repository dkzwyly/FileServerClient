package com.dkc.fileserverclient

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

class TrashActivity : AppCompatActivity() {

    private lateinit var serverUrl: String
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var btnEmptyTrash: Button

    private val fileServerService by lazy { FileServerService(this) }
    private val adapter = TrashAdapter(mutableListOf(), ::onRestore, ::onPermanentDelete)
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