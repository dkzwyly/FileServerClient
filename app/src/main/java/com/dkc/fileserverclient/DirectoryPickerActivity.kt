package com.dkc.fileserverclient

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class DirectoryPickerActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var currentPathText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var confirmButton: MaterialButton

    private val fileServerService by lazy { FileServerService(this) }
    private val directoryList = mutableListOf<DirectoryItem>()
    private val pathHistory = mutableListOf<String>()

    private var currentServerUrl = ""
    private var currentPath = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private lateinit var adapter: DirectoryPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_directory_picker)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }

        initViews()
        setupToolbar()
        loadDirectory("")
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        currentPathText = findViewById(R.id.currentPathText)
        recyclerView = findViewById(R.id.directoryRecyclerView)
        confirmButton = findViewById(R.id.confirmButton)

        adapter = DirectoryPickerAdapter(
            onItemClick = { item ->
                if (item.name == "..") {
                    navigateBack()
                } else {
                    pathHistory.add(currentPath)
                    loadDirectory(item.path)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        confirmButton.setOnClickListener {
            // 返回当前路径给调用者
            val intent = intent
            setResult(RESULT_OK, intent.putExtra("SELECTED_PATH", currentPath))
            finish()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }
    }

    private fun loadDirectory(path: String) {
        currentPath = path
        currentPathText.text = "当前路径：/${path.ifEmpty { "根目录" }}"
        supportActionBar?.subtitle = if (path.isEmpty()) "根目录" else path

        coroutineScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, path)
                }
                // 只保留目录
                val directories = items.filter { it.isDirectory }
                directoryList.clear()
                // 添加返回上级（如果不在根目录）
                if (path.isNotEmpty()) {
                    val parent = if (path.contains("/")) path.substringBeforeLast("/") else ""
                    directoryList.add(DirectoryItem("..", parent, isDirectory = true))
                }
                directoryList.addAll(directories.map { DirectoryItem(it.name, it.path, true) })
                adapter.submitList(ArrayList(directoryList))
            } catch (e: Exception) {
                // 显示错误
            }
        }
    }

    private fun navigateBack() {
        if (pathHistory.isNotEmpty()) {
            val previous = pathHistory.removeAt(pathHistory.size - 1)
            loadDirectory(previous)
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    // 简单数据类
    data class DirectoryItem(val name: String, val path: String, val isDirectory: Boolean)
}