package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var connectionStatusLabel: TextView
    private lateinit var quickActionsCard: androidx.cardview.widget.CardView
    private lateinit var browseFilesButton: Button
    private lateinit var historyListView: ListView

    private val fileServerService by lazy { FileServerService(this) }
    private val connectionHistory = mutableListOf<ConnectionHistory>()
    private val sharedPreferences: SharedPreferences by lazy {
        getSharedPreferences("FileServerPrefs", Context.MODE_PRIVATE)
    }
    private val gson = Gson()
    private var currentServerUrl = ""
    private var isConnected = false

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConnectionHistory()
        setupHistoryListView()
    }

    private fun initViews() {
        serverUrlEditText = findViewById(R.id.serverUrlEditText)
        connectButton = findViewById(R.id.connectButton)
        connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
        quickActionsCard = findViewById(R.id.quickActionsCard)
        browseFilesButton = findViewById(R.id.browseFilesButton)
        historyListView = findViewById(R.id.historyListView)

        connectButton.setOnClickListener {
            connectToServer()
        }

        browseFilesButton.setOnClickListener {
            if (isConnected) {
                val intent = Intent(this, FileListActivity::class.java).apply {
                    putExtra("SERVER_URL", currentServerUrl)
                }
                startActivity(intent)
            } else {
                showToast("请先连接到服务器")
            }
        }
    }

    private fun connectToServer() {
        val serverInput = serverUrlEditText.text.toString().trim()
        if (serverInput.isEmpty()) {
            showToast("请输入完整的服务器地址")
            return
        }

        coroutineScope.launch {
            updateConnectionStatus("正在连接服务器...", "#FF9800")

            val success = withContext(Dispatchers.IO) {
                fileServerService.testConnection(serverInput)
            }

            if (success) {
                currentServerUrl = serverInput
                isConnected = true

                updateConnectionStatus("已连接", "#4CAF50")
                showQuickActions(true)
                addToConnectionHistory(serverInput)

                showToast("服务器连接成功！")
            } else {
                isConnected = false
                updateConnectionStatus("连接失败", "#F44336")
                showQuickActions(false)
                showToast("无法连接到服务器，请检查地址是否正确")
            }
        }
    }

    private fun updateConnectionStatus(status: String, color: String) {
        connectionStatusLabel.text = status
        // 这里可以设置颜色，简化处理
    }

    private fun showQuickActions(show: Boolean) {
        quickActionsCard.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
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

        // 只保留最近10条记录
        if (connectionHistory.size > 10) {
            connectionHistory.removeAt(connectionHistory.size - 1)
        }

        saveConnectionHistory()
        updateHistoryListView()
    }

    private fun setupHistoryListView() {
        historyListView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            connectionHistory.map { it.url }
        )

        historyListView.setOnItemClickListener { _, _, position, _ ->
            val history = connectionHistory[position]
            serverUrlEditText.setText(history.url)
        }
    }

    private fun updateHistoryListView() {
        (historyListView.adapter as? ArrayAdapter<String>)?.apply {
            clear()
            addAll(connectionHistory.map { it.url })
            notifyDataSetChanged()
        }
    }

    private fun loadConnectionHistory() {
        val json = sharedPreferences.getString("connection_history", null)
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<List<ConnectionHistory>>() {}.type
            val history = gson.fromJson<List<ConnectionHistory>>(json, type)
            connectionHistory.clear()
            connectionHistory.addAll(history)
            updateHistoryListView()
        }
    }

    private fun saveConnectionHistory() {
        val json = gson.toJson(connectionHistory)
        sharedPreferences.edit().putString("connection_history", json).apply()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}