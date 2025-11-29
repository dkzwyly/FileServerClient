package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var connectionStatusLabel: TextView
    private lateinit var quickActionsCard: CardView
    private lateinit var browseFilesButton: Button
    private lateinit var historyListView: ListView

    // 新增的库按钮
    private lateinit var mediaLibraryButton: Button
    private lateinit var textLibraryButton: Button
    private lateinit var videoLibraryButton: Button
    private lateinit var audioLibraryButton: Button

    private val fileServerService by lazy { FileServerService(this) }
    private val connectionHistory = mutableListOf<ConnectionHistory>()
    private val sharedPreferences: SharedPreferences by lazy {
        getSharedPreferences("FileServerPrefs", Context.MODE_PRIVATE)
    }
    private val gson = Gson()
    private var currentServerUrl = ""
    private var isConnected = false

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate started")

        try {
            setContentView(R.layout.activity_main)
            Log.d(TAG, "setContentView completed")

            currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
            Log.d(TAG, "Current server URL: $currentServerUrl")

            initViews()
            loadConnectionHistory()
            setupHistoryListView()

            Log.d(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            throw e
        }
    }

    private fun initViews() {
        Log.d(TAG, "initViews started")

        try {
            serverUrlEditText = findViewById(R.id.serverUrlEditText)
            connectButton = findViewById(R.id.connectButton)
            connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
            quickActionsCard = findViewById(R.id.quickActionsCard)
            browseFilesButton = findViewById(R.id.browseFilesButton)
            historyListView = findViewById(R.id.historyListView)

            // 初始化库按钮
            mediaLibraryButton = findViewById(R.id.mediaLibraryButton)
            textLibraryButton = findViewById(R.id.textLibraryButton)
            videoLibraryButton = findViewById(R.id.videoLibraryButton)
            audioLibraryButton = findViewById(R.id.audioLibraryButton)

            Log.d(TAG, "All views found successfully")

            connectButton.setOnClickListener {
                Log.d(TAG, "Connect button clicked")
                connectToServer()
            }

            browseFilesButton.setOnClickListener {
                Log.d(TAG, "Browse files button clicked")
                openFileList("all")
            }

            // 设置库按钮点击事件
            mediaLibraryButton.setOnClickListener {
                Log.d(TAG, "Media library button clicked")
                openImageGallery()
            }



            textLibraryButton.setOnClickListener {
                Log.d(TAG, "Text library button clicked")
                openFileList("text")
            }

            videoLibraryButton.setOnClickListener {
                Log.d(TAG, "Video library button clicked")
                openFileList("video")
            }

            audioLibraryButton.setOnClickListener {
                Log.d(TAG, "Audio library button clicked")
                openFileList("audio")
            }

            Log.d(TAG, "initViews completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error in initViews: ${e.message}", e)
            throw e
        }
    }
    // 添加打开图片库的方法
    private fun openImageGallery() {
        if (isConnected) {
            val intent = Intent(this, ImageGalleryActivity::class.java).apply {
                putExtra("SERVER_URL", currentServerUrl)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            showToast("请先连接到服务器")
        }
    }
    private fun openFileList(fileType: String) {
        if (isConnected) {
            val intent = Intent(this, FileListActivity::class.java).apply {
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("FILE_TYPE", fileType)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            showToast("请先连接到服务器")
        }
    }

    private fun connectToServer() {
        val serverInput = serverUrlEditText.text.toString().trim()
        Log.d(TAG, "Connecting to server: $serverInput")

        if (serverInput.isEmpty()) {
            showToast("请输入服务器地址")
            serverUrlEditText.requestFocus()
            return
        }

        coroutineScope.launch {
            updateConnectionStatus("正在连接...", "#FF9800")
            connectButton.isEnabled = false
            connectButton.text = "连接中..."

            try {
                val success = withContext(Dispatchers.IO) {
                    fileServerService.testConnection(serverInput)
                }

                if (success) {
                    currentServerUrl = serverInput
                    isConnected = true

                    updateConnectionStatus("已连接", "#4CAF50")
                    showQuickActions(true)
                    addToConnectionHistory(serverInput)

                    showToast("✅ 连接成功！")

                    val slideIn = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.slide_in_left)
                    quickActionsCard.startAnimation(slideIn)
                } else {
                    isConnected = false
                    updateConnectionStatus("连接失败", "#F44336")
                    showQuickActions(false)
                    showToast("❌ 连接失败，请检查服务器地址")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during connection: ${e.message}", e)
                showToast("连接过程中出现错误")
            } finally {
                connectButton.isEnabled = true
                connectButton.text = "连接服务器"
            }
        }
    }

    private fun updateConnectionStatus(status: String, color: String) {
        connectionStatusLabel.text = status

        when (color) {
            "#4CAF50" -> {
                connectionStatusLabel.setTextColor(Color.parseColor("#2E7D32"))
                connectionStatusLabel.setBackgroundColor(Color.parseColor("#E8F5E8"))
            }
            "#F44336" -> {
                connectionStatusLabel.setTextColor(Color.parseColor("#C62828"))
                connectionStatusLabel.setBackgroundColor(Color.parseColor("#FFEBEE"))
            }
            "#FF9800" -> {
                connectionStatusLabel.setTextColor(Color.parseColor("#EF6C00"))
                connectionStatusLabel.setBackgroundColor(Color.parseColor("#FFF3E0"))
            }
        }
    }

    private fun showQuickActions(show: Boolean) {
        if (show) {
            quickActionsCard.visibility = View.VISIBLE
        } else {
            quickActionsCard.visibility = View.GONE
        }
    }

    private fun addToConnectionHistory(url: String) {
        val existing = connectionHistory.firstOrNull { it.url == url }
        if (existing != null) {
            connectionHistory.remove(existing)
        }

        connectionHistory.add(0, ConnectionHistory(
            url = url,
            lastConnected = System.currentTimeMillis(),
            protocol = if (url.startsWith("https://")) "HTTPS" else "HTTP"
        ))

        if (connectionHistory.size > 10) {
            connectionHistory.removeAt(connectionHistory.size - 1)
        }

        saveConnectionHistory()
        updateHistoryListView()
    }

    private fun setupHistoryListView() {
        try {
            historyListView.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                connectionHistory.map {
                    val displayUrl = it.url
                        .removePrefix("http://")
                        .removePrefix("https://")
                    "$displayUrl (${it.protocol})"
                }
            )

            historyListView.setOnItemClickListener { _, _, position, _ ->
                val history = connectionHistory[position]
                serverUrlEditText.setText(history.url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupHistoryListView: ${e.message}", e)
        }
    }

    private fun updateHistoryListView() {
        try {
            (historyListView.adapter as? ArrayAdapter<String>)?.apply {
                clear()
                addAll(connectionHistory.map {
                    val displayUrl = it.url
                        .removePrefix("http://")
                        .removePrefix("https://")
                    "$displayUrl (${it.protocol})"
                })
                notifyDataSetChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateHistoryListView: ${e.message}", e)
        }
    }

    private fun loadConnectionHistory() {
        try {
            val json = sharedPreferences.getString("connection_history", null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<ConnectionHistory>>() {}.type
                val history = gson.fromJson<List<ConnectionHistory>>(json, type)
                connectionHistory.clear()
                connectionHistory.addAll(history)
                updateHistoryListView()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadConnectionHistory: ${e.message}", e)
        }
    }

    private fun saveConnectionHistory() {
        try {
            val json = gson.toJson(connectionHistory)
            sharedPreferences.edit().putString("connection_history", json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error in saveConnectionHistory: ${e.message}", e)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        coroutineScope.cancel()
    }
}