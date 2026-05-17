package com.dkc.fileserverclient

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var connectionStatusLabel: TextView
    private lateinit var quickActionsLayout: LinearLayout
    private lateinit var browseFilesButton: Button
    private lateinit var historyListView: ListView

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
    private var autoConnectEnabled = true

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var audioBackgroundManager: AudioBackgroundManager? = null
    private var isAudioServiceBound = false

    private lateinit var rootFrame: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private var currentGradientIndex = 0
    // 修改 gradientList，新增第6个纯白背景
    private val gradientList = listOf(
        createGradientDrawable(intArrayOf(0xFFFCE4EC.toInt(), 0xFFFFF0F5.toInt(), 0xFFF8BBD0.toInt()), GradientDrawable.Orientation.TL_BR),
        createGradientDrawable(intArrayOf(0xFFE8F0FE.toInt(), 0xFFD4E4FC.toInt(), 0xFFBBDEFB.toInt()), GradientDrawable.Orientation.TL_BR),
        createGradientDrawable(intArrayOf(0xFFE0F7FA.toInt(), 0xFFB2EBF2.toInt(), 0xFF80DEEA.toInt()), GradientDrawable.Orientation.TOP_BOTTOM),
        createGradientDrawable(intArrayOf(0xFFF3E5F5.toInt(), 0xFFE1BEE7.toInt(), 0xFFCE93D8.toInt()), GradientDrawable.Orientation.LEFT_RIGHT),
        createGradientDrawable(intArrayOf(0xFFFFF3E0.toInt(), 0xFFFFE0B2.toInt(), 0xFFFFCC80.toInt()), GradientDrawable.Orientation.BL_TR),
        createSolidWhiteDrawable()   // 新增纯白背景
    )
    private fun createSolidWhiteDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.WHITE)
        }
    }
    private val PREF_GRADIENT_INDEX = "gradient_index"
    private lateinit var effectManager: BackgroundEffectManager

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_AUTO_CONNECT = "auto_connect_enabled"
    }

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
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        rootFrame = findViewById(R.id.rootFrame)
        rootLayout = findViewById(R.id.rootLayout)

        currentGradientIndex = sharedPreferences.getInt(PREF_GRADIENT_INDEX, 0)

        effectManager = BackgroundEffectManager(this)
        effectManager.attachTo(rootFrame)

        applyGradient(currentGradientIndex)

        val intentServerUrl = intent.getStringExtra("SERVER_URL")
        if (!intentServerUrl.isNullOrEmpty()) {
            currentServerUrl = intentServerUrl
            Log.d(TAG, "从Intent获取服务器地址: $currentServerUrl")
        } else {
            autoConnectEnabled = sharedPreferences.getBoolean(PREF_AUTO_CONNECT, true)
            Log.d(TAG, "自动连接设置: $autoConnectEnabled")
        }

        audioBackgroundManager = AudioBackgroundManager(this)
        initViews()
        loadConnectionHistory()
        setupHistoryListView()

        if (intentServerUrl.isNullOrEmpty() && autoConnectEnabled && connectionHistory.isNotEmpty()) {
            coroutineScope.launch {
                delay(500)
                autoConnectToLastServer()
            }
        } else if (!intentServerUrl.isNullOrEmpty()) {
            serverUrlEditText.setText(currentServerUrl)
            coroutineScope.launch {
                delay(300)
                connectToServer(currentServerUrl, isSilent = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        effectManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        effectManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioBackgroundManager?.cleanup()
        audioBackgroundManager = null
        effectManager.onDestroy()
        coroutineScope.cancel()
    }

    private fun createGradientDrawable(colors: IntArray, orientation: GradientDrawable.Orientation): GradientDrawable {
        return GradientDrawable(orientation, colors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    private fun applyGradient(index: Int) {
        currentGradientIndex = index
        if (index == 5) {
            // 纯白背景：直接设置白色，无渐变
            rootFrame.setBackgroundColor(Color.WHITE)
        } else if (index in gradientList.indices) {
            rootFrame.background = gradientList[index]
        } else {
            rootFrame.background = gradientList[0]
        }
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        effectManager.applyEffectForGradient(index)
        sharedPreferences.edit { putInt(PREF_GRADIENT_INDEX, currentGradientIndex) }
    }

    private fun cycleGradient() {
        currentGradientIndex = (currentGradientIndex + 1) % gradientList.size
        applyGradient(currentGradientIndex)
    }

    private fun autoConnectToLastServer() {
        if (connectionHistory.isEmpty()) return
        val lastConnection = connectionHistory.first()
        serverUrlEditText.setText(lastConnection.url)
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
                sharedPreferences.edit { putBoolean(PREF_AUTO_CONNECT, autoConnectEnabled) }
                val status = if (autoConnectEnabled) "启用" else "禁用"
                showToast("已${status}自动连接")
                item.isChecked = autoConnectEnabled
                true
            }
            R.id.menu_toggle_bg -> {
                cycleGradient()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val autoConnectItem = menu.findItem(R.id.menu_auto_connect)
        if (autoConnectItem != null) {
            autoConnectItem.isChecked = autoConnectEnabled
            val iconRes = if (autoConnectEnabled) R.drawable.ic_auto_connect_on else R.drawable.ic_auto_connect_off
            autoConnectItem.icon = ContextCompat.getDrawable(this, iconRes)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        overrideActivityTransition()
    }

    private fun initViews() {
        serverUrlEditText = findViewById(R.id.serverUrlEditText)
        connectButton = findViewById(R.id.connectButton)
        connectionStatusLabel = findViewById(R.id.connectionStatusLabel)
        quickActionsLayout = findViewById(R.id.quickActionsLayout)
        browseFilesButton = findViewById(R.id.browseFilesButton)
        historyListView = findViewById(R.id.historyListView)

        mediaLibraryButton = findViewById(R.id.mediaLibraryButton)
        textLibraryButton = findViewById(R.id.textLibraryButton)
        videoLibraryButton = findViewById(R.id.videoLibraryButton)
        audioLibraryButton = findViewById(R.id.audioLibraryButton)

        connectButton.setOnClickListener { connectToServer(isSilent = false) }
        browseFilesButton.setOnClickListener { openFileList("all") }
        mediaLibraryButton.setOnClickListener { if (isConnected) openImageGallery() else showToast("请先连接到服务器") }
        textLibraryButton.setOnClickListener { if (isConnected) openTextLibrary() else showToast("请先连接到服务器") }
        videoLibraryButton.setOnClickListener { if (isConnected) openVideoLibrary() else showToast("请先连接到服务器") }
        audioLibraryButton.setOnClickListener {
            if (isConnected) {
                if (!checkNotificationPermission()) {
                    requestNotificationPermission()
                    return@setOnClickListener
                }
                startActivity(Intent(this, AudioLibraryActivity::class.java).apply { putExtra("SERVER_URL", currentServerUrl) })
                overrideActivityTransition()
            } else {
                showToast("请先连接到服务器")
            }
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.areNotificationsEnabled()
        } else true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
            showToast("请在设置中开启通知权限")
        }
    }

    private fun openImageGallery() {
        startActivity(Intent(this, ImageGalleryActivity::class.java).apply { putExtra("SERVER_URL", currentServerUrl) })
        overrideActivityTransition()
    }

    private fun openTextLibrary() {
        startActivity(Intent(this, TextLibraryActivity::class.java).apply { putExtra("SERVER_URL", currentServerUrl) })
        overrideActivityTransition()
    }

    private fun openVideoLibrary() {
        startActivity(Intent(this, VideoLibraryActivity::class.java).apply { putExtra("SERVER_URL", currentServerUrl) })
        overrideActivityTransition()
    }

    private fun overrideActivityTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun openFileList(fileType: String) {
        if (isConnected) {
            startActivity(Intent(this, FileListActivity::class.java).apply {
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("FILE_TYPE", fileType)
            })
            overrideActivityTransition()
        } else {
            showToast("请先连接到服务器")
        }
    }

    private fun connectToServer(isSilent: Boolean = false) {
        connectToServer(serverUrlEditText.text.toString().trim(), isSilent)
    }

    private fun connectToServer(serverUrl: String, isSilent: Boolean = false) {
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
                val success = withContext(Dispatchers.IO) { fileServerService.testConnection(serverUrl) }
                if (success) {
                    currentServerUrl = serverUrl
                    isConnected = true
                    updateConnectionStatus("已连接", "#4CAF50")
                    showQuickActions(true)
                    addToConnectionHistory(serverUrl)
                    if (!isSilent) showToast("✅ 连接成功！")
                    val slideIn = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.slide_in_left)
                    quickActionsLayout.startAnimation(slideIn)
                } else {
                    isConnected = false
                    updateConnectionStatus("连接失败", "#F44336")
                    showQuickActions(false)
                    if (!isSilent) showToast("❌ 连接失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接错误", e)
                updateConnectionStatus("连接错误", "#F44336")
                if (!isSilent) showToast("连接错误: ${e.message}")
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
        quickActionsLayout.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun addToConnectionHistory(url: String) {
        val existing = connectionHistory.firstOrNull { it.url == url }
        if (existing != null) connectionHistory.remove(existing)
        connectionHistory.add(0, ConnectionHistory(url, System.currentTimeMillis(), if (url.startsWith("https://")) "HTTPS" else "HTTP"))
        if (connectionHistory.size > 10) connectionHistory.removeAt(connectionHistory.size - 1)
        saveConnectionHistory()
        updateHistoryListView()
    }

    private fun setupHistoryListView() {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            connectionHistory.map { it.url.removePrefix("http://").removePrefix("https://") + " (${it.protocol})" }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.setBackgroundColor(Color.TRANSPARENT)
                if (view is TextView) {
                    view.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    view.setPadding(16, 12, 16, 12)
                }
                return view
            }
        }
        historyListView.adapter = adapter
        historyListView.setBackgroundColor(Color.TRANSPARENT)
        historyListView.setOnItemClickListener { _, _, position, _ ->
            val history = connectionHistory[position]
            serverUrlEditText.setText(history.url)
            connectToServer(history.url, isSilent = false)
        }
    }

    private fun updateHistoryListView() {
        (historyListView.adapter as? ArrayAdapter<String>)?.apply {
            clear()
            addAll(connectionHistory.map { it.url.removePrefix("http://").removePrefix("https://") + " (${it.protocol})" })
            notifyDataSetChanged()
        } ?: setupHistoryListView()
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
            Log.e(TAG, "加载历史记录失败", e)
        }
    }

    private fun saveConnectionHistory() {
        try {
            sharedPreferences.edit { putString("connection_history", gson.toJson(connectionHistory)) }
        } catch (e: Exception) {
            Log.e(TAG, "保存历史记录失败", e)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (audioBackgroundManager?.isServiceRunning() == true) {
            Log.d(TAG, "音频后台播放中，返回时保持服务")
        }
        super.onBackPressed()
    }
}