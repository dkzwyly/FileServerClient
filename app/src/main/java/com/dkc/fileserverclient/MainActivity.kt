package com.dkc.fileserverclient

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var connectionStatusLabel: TextView
    private lateinit var quickActionsCard: MaterialCardView
    private lateinit var browseFilesButton: Button
    private lateinit var historyListView: ListView

    // 库按钮
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
    private var autoConnectEnabled = true // 默认启用自动连接

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    // 音频服务相关变量
    private var audioBackgroundManager: AudioBackgroundManager? = null
    private var isAudioServiceBound = false

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_AUTO_CONNECT = "auto_connect_enabled"
    }

    // 服务连接
    private val audioServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "音频服务连接成功")
            isAudioServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "音频服务断开连接")
            isAudioServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 检查是否从intent传入服务器地址
        val intentServerUrl = intent.getStringExtra("SERVER_URL")
        if (!intentServerUrl.isNullOrEmpty()) {
            currentServerUrl = intentServerUrl
            Log.d(TAG, "从Intent获取服务器地址: $currentServerUrl")
        } else {
            // 从SharedPreferences加载自动连接设置
            autoConnectEnabled = sharedPreferences.getBoolean(PREF_AUTO_CONNECT, true)
            Log.d(TAG, "自动连接设置: $autoConnectEnabled")
        }

        // 初始化音频后台管理器
        audioBackgroundManager = AudioBackgroundManager(this)

        initViews()
        loadConnectionHistory()
        setupHistoryListView()

        // 静默自动连接: 不弹出Toast，仅在状态栏显示
        if (intentServerUrl.isNullOrEmpty() && autoConnectEnabled && connectionHistory.isNotEmpty()) {
            coroutineScope.launch {
                delay(500) // 延迟确保UI加载完成
                autoConnectToLastServer()
            }
        } else if (!intentServerUrl.isNullOrEmpty()) {
            // 从Intent传入地址时也静默连接
            serverUrlEditText.setText(currentServerUrl)
            coroutineScope.launch {
                delay(300)
                connectToServer(currentServerUrl, isSilent = true)
            }
        }
    }

    /**
     * 自动连接到最近一次连接的服务器 (静默模式)
     */
    private fun autoConnectToLastServer() {
        if (connectionHistory.isEmpty()) {
            Log.d(TAG, "没有连接历史记录，跳过自动连接")
            return
        }

        val lastConnection = connectionHistory.first()
        Log.d(TAG, "静默自动连接到最近服务器: ${lastConnection.url}")

        serverUrlEditText.setText(lastConnection.url)

        // 静默连接，不显示Toast
        coroutineScope.launch {
            delay(300)
            connectToServer(lastConnection.url, isSilent = true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                openSettings()
                true
            }
            R.id.menu_about -> {
                showToast("文件服务器客户端 v1.0")
                true
            }
            R.id.menu_auto_connect -> {
                autoConnectEnabled = !autoConnectEnabled
                sharedPreferences.edit {
                    putBoolean(PREF_AUTO_CONNECT, autoConnectEnabled)
                }
                val status = if (autoConnectEnabled) "启用" else "禁用"
                showToast("已${status}自动连接")
                item.isChecked = autoConnectEnabled
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val autoConnectItem = menu.findItem(R.id.menu_auto_connect)
        if (autoConnectItem != null) {
            autoConnectItem.isChecked = autoConnectEnabled
            val iconRes = if (autoConnectEnabled) {
                R.drawable.ic_auto_connect_on
            } else {
                R.drawable.ic_auto_connect_off
            }
            autoConnectItem.icon = ContextCompat.getDrawable(this, iconRes)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        overrideActivityTransition()
    }

    private fun initViews() {
        serverUrlEditText = findViewById(R.id.serverUrlEditText)
        connectButton = findViewById(R.id.connectButton)
        connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
        quickActionsCard = findViewById(R.id.quickActionsCard)
        browseFilesButton = findViewById(R.id.browseFilesButton)
        historyListView = findViewById(R.id.historyListView)

        mediaLibraryButton = findViewById(R.id.mediaLibraryButton)
        textLibraryButton = findViewById(R.id.textLibraryButton)
        videoLibraryButton = findViewById(R.id.videoLibraryButton)
        audioLibraryButton = findViewById(R.id.audioLibraryButton)

        connectButton.setOnClickListener {
            // 手动连接时使用非静默模式，会显示Toast反馈
            connectToServer(isSilent = false)
        }

        browseFilesButton.setOnClickListener {
            openFileList("all")
        }

        mediaLibraryButton.setOnClickListener {
            if (isConnected) openImageGallery() else showToast("请先连接到服务器")
        }

        textLibraryButton.setOnClickListener {
            if (isConnected) openTextLibrary() else showToast("请先连接到服务器")
        }

        videoLibraryButton.setOnClickListener {
            if (isConnected) openVideoLibrary() else showToast("请先连接到服务器")
        }

        audioLibraryButton.setOnClickListener {
            if (isConnected) {
                if (!checkNotificationPermission()) {
                    requestNotificationPermission()
                    return@setOnClickListener
                }
                val intent = Intent(this, AudioLibraryActivity::class.java).apply {
                    putExtra("SERVER_URL", currentServerUrl)
                }
                startActivity(intent)
                overrideActivityTransition()
            } else {
                showToast("请先连接到服务器")
            }
        }
    }

    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return notificationManager.areNotificationsEnabled()
        }
        return true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
            showToast("请在设置中开启通知权限，以便在后台控制音频播放")
        }
    }

    private fun openImageGallery() {
        val intent = Intent(this, ImageGalleryActivity::class.java).apply {
            putExtra("SERVER_URL", currentServerUrl)
        }
        startActivity(intent)
        overrideActivityTransition()
    }

    private fun openTextLibrary() {
        val intent = Intent(this, TextLibraryActivity::class.java).apply {
            putExtra("SERVER_URL", currentServerUrl)
        }
        startActivity(intent)
        overrideActivityTransition()
    }

    private fun openVideoLibrary() {
        val intent = Intent(this, VideoLibraryActivity::class.java).apply {
            putExtra("SERVER_URL", currentServerUrl)
        }
        startActivity(intent)
        overrideActivityTransition()
    }

    private fun overrideActivityTransition() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun openFileList(fileType: String) {
        if (isConnected) {
            val intent = Intent(this, FileListActivity::class.java).apply {
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("FILE_TYPE", fileType)
            }
            startActivity(intent)
            overrideActivityTransition()
        } else {
            showToast("请先连接到服务器")
        }
    }

    /**
     * 连接到服务器（使用EditText中的地址）
     * @param isSilent 是否静默连接（不显示Toast）
     */
    private fun connectToServer(isSilent: Boolean = false) {
        val serverInput = serverUrlEditText.text.toString().trim()
        connectToServer(serverInput, isSilent)
    }

    /**
     * 连接到指定服务器
     * @param serverUrl 服务器地址
     * @param isSilent 是否静默连接（不显示Toast，适用于自动连接）
     */
    private fun connectToServer(serverUrl: String, isSilent: Boolean = false) {
        Log.d(TAG, "连接服务器: $serverUrl, 静默模式: $isSilent")

        if (serverUrl.isEmpty()) {
            if (!isSilent) showToast("请输入服务器地址")
            serverUrlEditText.requestFocus()
            return
        }

        coroutineScope.launch {
            updateConnectionStatus("正在连接...", "#FF9800")
            connectButton.isEnabled = false
            connectButton.text = "连接中..."

            try {
                val success = withContext(Dispatchers.IO) {
                    fileServerService.testConnection(serverUrl)
                }

                if (success) {
                    currentServerUrl = serverUrl
                    isConnected = true

                    updateConnectionStatus("已连接", "#4CAF50")
                    showQuickActions(true)
                    addToConnectionHistory(serverUrl)

                    if (!isSilent) showToast("✅ 连接成功！")

                    val slideIn = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.slide_in_left)
                    quickActionsCard.startAnimation(slideIn)
                } else {
                    isConnected = false
                    updateConnectionStatus("连接失败", "#F44336")
                    showQuickActions(false)
                    if (!isSilent) showToast("❌ 连接失败，请检查服务器地址")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接错误: ${e.message}", e)
                updateConnectionStatus("连接错误", "#F44336")
                if (!isSilent) showToast("连接过程中出现错误: ${e.message}")
            } finally {
                connectButton.isEnabled = true
                connectButton.text = "连接服务器"
            }
        }
    }

    private fun updateConnectionStatus(status: String, colorCode: String) {
        connectionStatusLabel.text = status
        when (colorCode) {
            "#4CAF50" -> {
                connectionStatusLabel.setTextColor(getColor(R.color.status_connected_text))
                connectionStatusLabel.setBackgroundColor(getColor(R.color.status_connected_bg))
            }
            "#F44336" -> {
                connectionStatusLabel.setTextColor(getColor(R.color.status_disconnected_text))
                connectionStatusLabel.setBackgroundColor(getColor(R.color.status_disconnected_bg))
            }
            "#FF9800" -> {
                connectionStatusLabel.setTextColor(getColor(R.color.status_connecting_text))
                connectionStatusLabel.setBackgroundColor(getColor(R.color.status_connecting_bg))
            }
        }
    }

    private fun showQuickActions(show: Boolean) {
        quickActionsCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun addToConnectionHistory(url: String) {
        val existing = connectionHistory.firstOrNull { it.url == url }
        if (existing != null) connectionHistory.remove(existing)

        connectionHistory.add(0, ConnectionHistory(
            url = url,
            lastConnected = System.currentTimeMillis(),
            protocol = if (url.startsWith("https://")) "HTTPS" else "HTTP"
        ))

        if (connectionHistory.size > 10) connectionHistory.removeAt(connectionHistory.size - 1)

        saveConnectionHistory()
        updateHistoryListView()
    }

    private fun setupHistoryListView() {
        historyListView.adapter = ArrayAdapter(
            this,
            R.layout.history_list_item,
            connectionHistory.map {
                val displayUrl = it.url.removePrefix("http://").removePrefix("https://")
                "$displayUrl (${it.protocol})"
            }
        )

        historyListView.setOnItemClickListener { _, _, position, _ ->
            val history = connectionHistory[position]
            serverUrlEditText.setText(history.url)
            // 点击历史后手动连接，显示反馈
            connectToServer(history.url, isSilent = false)
        }
    }

    private fun updateHistoryListView() {
        val adapter = historyListView.adapter
        if (adapter is ArrayAdapter<*>) {
            @Suppress("UNCHECKED_CAST")
            (adapter as ArrayAdapter<String>).apply {
                clear()
                addAll(connectionHistory.map {
                    val displayUrl = it.url.removePrefix("http://").removePrefix("https://")
                    "$displayUrl (${it.protocol})"
                })
                notifyDataSetChanged()
            }
        } else {
            setupHistoryListView()
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
            Log.e(TAG, "加载历史记录失败: ${e.message}", e)
        }
    }

    private fun saveConnectionHistory() {
        try {
            val json = gson.toJson(connectionHistory)
            sharedPreferences.edit { putString("connection_history", json) }
        } catch (e: Exception) {
            Log.e(TAG, "保存历史记录失败: ${e.message}", e)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun onAppExit() {
        Log.d(TAG, "清理音频服务")
        audioBackgroundManager?.shutdownService()
        if (isAudioServiceBound) {
            try {
                unbindService(audioServiceConnection)
                isAudioServiceBound = false
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "服务未绑定: ${e.message}")
            }
        }
    }

    override fun onBackPressed() {
        if (audioBackgroundManager?.isServiceRunning() == true) {
            Log.d(TAG, "音频后台播放中，返回时保持服务")
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioBackgroundManager?.cleanup()
        audioBackgroundManager = null
        coroutineScope.cancel()
    }
}