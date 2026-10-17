package com.dkc.fileserverclient

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File

class ShareReceiverActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShareReceiver"

        private const val PREFS_FS = "FileServerPrefs"
        private const val KEY_HEALTH_TIMEOUT_MS = 5_000L
    }

    private var serverUrl = ""
    private var sharedUris: List<Uri> = emptyList()

    /**
     * ★ 信号量：健康检查完成时 complete。
     * 所有后续逻辑都先 await() 这个信号，确保"连得上"才继续。
     */
    private val healthSignal = CompletableDeferred<Boolean>()

    private var loadingDialog: AlertDialog? = null

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                finish()
                return@registerForActivityResult
            }
            val targetPath = result.data?.getStringExtra("SELECTED_PATH") ?: ""
            confirmAndUpload(targetPath)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedUris = extractUris(intent)
        if (sharedUris.isEmpty()) {
            Toast.makeText(this, "没有可上传的文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            serverUrl = getServerUrl()
            if (serverUrl.isEmpty()) {
                // 从来没连过服务器 → 让用户输入
                showServerInputDialog()
                return@launch
            }

            // 显示加载，等待健康检查信号
            showLoading("连接服务器...")
            startHealthCheck()
            val healthy = healthSignal.await()   // ★ 信号量等待
            hideLoading()

            if (!healthy) {
                showServerUnreachableDialog()
                return@launch
            }

            // 服务器可达 → 进入目录选择
            launchDirectoryPicker()
        }
    }

    // ============================================================
    // 健康检查（信号量生产者）
    // ============================================================

    private fun startHealthCheck() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val url = "${serverUrl.removeSuffix("/")}/api/fileserver/health"
                Log.d(TAG, "Health check: $url")

                val request = Request.Builder().url(url).get().build()
                val client = UnsafeHttpClient.createUnsafeOkHttpClient()

                val success = withTimeoutOrNull(KEY_HEALTH_TIMEOUT_MS) {
                    client.newCall(request).execute().use { response ->
                        Log.d(TAG, "Health check 返回: ${response.code}")
                        response.isSuccessful
                    }
                } ?: false

                success
            } catch (e: Exception) {
                Log.e(TAG, "Health check 异常: ${e.message}", e)
                false
            }

            // ★ 发送信号（只发一次，幂等保护）
            if (!healthSignal.isCompleted) {
                healthSignal.complete(result)
            }
        }
    }

    // ============================================================
    // 读取 serverUrl（多 key 兼容）
    // ============================================================

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences(PREFS_FS, MODE_PRIVATE)

        // 1. 尝试各种可能的单 key
        val candidates = listOf(
            "last_server_url",
            "server_url",
            "current_server_url",
            "last_url",
            "url"
        )
        for (key in candidates) {
            val v = prefs.getString(key, "") ?: ""
            if (v.isNotEmpty()) return v
        }

        // 2. 尝试从连接历史里取第一条
        val historyKeys = listOf("server_history", "history", "connection_history")
        for (hKey in historyKeys) {
            val json = prefs.getString(hKey, "") ?: continue
            if (json.isEmpty()) continue
            try {
                val arr = org.json.JSONArray(json)
                if (arr.length() > 0) {
                    val first = arr.get(0)
                    if (first is String) return first
                    if (first is org.json.JSONObject) {
                        for (k in listOf("url", "serverUrl", "server_url")) {
                            if (first.has(k)) return first.getString(k)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return ""
    }

    // ============================================================
    // 用户手动输入服务器地址（首次连接 / prefs 被清）
    // ============================================================

    private fun showServerInputDialog() {
        val input = EditText(this).apply {
            hint = "https://192.168.1.100:8081"
            setText("http://")
            setSelection(text.length)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("输入服务器地址")
            .setMessage("未检测到已连接的服务器，请输入地址：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val url = input.text.toString().trim().trimEnd('/')
                if (url.isEmpty() || !url.startsWith("http")) {
                    Toast.makeText(this, "地址无效", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                // 保存下来
                getSharedPreferences(PREFS_FS, MODE_PRIVATE)
                    .edit()
                    .putString("last_server_url", url)
                    .apply()
                serverUrl = url

                // 输入地址后再走一次健康检查
                lifecycleScope.launch {
                    showLoading("连接服务器...")
                    val newSignal = CompletableDeferred<Boolean>()
                    lifecycleScope.launch(Dispatchers.IO) {
                        val ok = pingHealth(url)
                        newSignal.complete(ok)
                    }
                    val healthy = newSignal.await()
                    hideLoading()

                    if (!healthy) {
                        showServerUnreachableDialog()
                    } else {
                        launchDirectoryPicker()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private suspend fun pingHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${url.removeSuffix("/")}/api/fileserver/health")
                .get()
                .build()
            withTimeoutOrNull(KEY_HEALTH_TIMEOUT_MS) {
                UnsafeHttpClient.createUnsafeOkHttpClient()
                    .newCall(request).execute().use { it.isSuccessful }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "pingHealth error", e)
            false
        }
    }

    private fun showServerUnreachableDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("无法连接服务器")
            .setMessage(
                "服务器地址：\n$serverUrl\n\n" +
                        "健康检查失败。可能原因：\n" +
                        "• 服务器未启动\n" +
                        "• 网络不通 / 不在同一局域网\n" +
                        "• 地址或端口错误"
            )
            .setPositiveButton("重试") { _, _ ->
                retryHealthCheck()
            }
            .setNegativeButton("手动输入") { _, _ ->
                showServerInputDialog()
            }
            .setNeutralButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun retryHealthCheck() {
        lifecycleScope.launch {
            showLoading("重新连接...")
            val newSignal = CompletableDeferred<Boolean>()
            lifecycleScope.launch(Dispatchers.IO) {
                newSignal.complete(pingHealth(serverUrl))
            }
            val ok = newSignal.await()
            hideLoading()

            if (ok) {
                Toast.makeText(this@ShareReceiverActivity, "已连接", Toast.LENGTH_SHORT).show()
                launchDirectoryPicker()
            } else {
                showServerUnreachableDialog()
            }
        }
    }

    // ============================================================
    // 加载指示器
    // ============================================================

    private fun showLoading(msg: String) {
        hideLoading()
        loadingDialog = MaterialAlertDialogBuilder(this)
            .setMessage(msg)
            .setCancelable(false)
            .show()
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    // ============================================================
    // 进入目录选择器
    // ============================================================

    private fun launchDirectoryPicker() {
        val intent = Intent(this, DirectoryPickerActivity::class.java).apply {
            putExtra("SERVER_URL", serverUrl)
        }
        directoryPickerLauncher.launch(intent)
    }

    // ============================================================
    // 目录返回后确认并上传
    // ============================================================

    private fun confirmAndUpload(targetPath: String) {
        lifecycleScope.launch {
            val totalSize = withContext(Dispatchers.IO) {
                sharedUris.sumOf { querySize(it) }
            }
            val sizeText = formatSize(totalSize)
            val displayDir = if (targetPath.isEmpty()) "/（根目录）" else "/$targetPath"

            MaterialAlertDialogBuilder(this@ShareReceiverActivity)
                .setTitle("确认上传")
                .setMessage(
                    "共 ${sharedUris.size} 个文件（约 $sizeText）\n\n" +
                            "保存位置：$displayDir\n\n" +
                            "确定加入上传队列吗？"
                )
                .setPositiveButton("上传") { _, _ -> startUpload(targetPath) }
                .setNegativeButton("取消") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun startUpload(targetPath: String) {
        lifecycleScope.launch {
            var queued = 0
            val failed = mutableListOf<String>()

            withContext(Dispatchers.IO) {
                for (uri in sharedUris) {
                    val pair = copyToCache(uri)
                    if (pair == null) {
                        failed.add(queryDisplayName(uri) ?: uri.toString())
                        continue
                    }
                    try {
                        com.dkc.fileserverclient.transfer.TransferManager.enqueueUpload(
                            serverUrl = serverUrl,
                            localFile = pair.first,
                            remoteDir = targetPath
                        )
                        queued++
                        Log.d(TAG, "已入队: ${pair.second} -> $targetPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "入队失败: ${pair.second}", e)
                        failed.add(pair.second)
                    }
                }
            }

            val msg = buildString {
                if (queued > 0) append("已加入上传队列 $queued / ${sharedUris.size} 个")
                else append("全部入队失败")
                if (failed.isNotEmpty()) {
                    append("\n\n失败: ${failed.take(3).joinToString(", ")}")
                    if (failed.size > 3) append(" 等 ${failed.size} 个")
                }
            }
            Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ============================================================
    // Intent / 文件工具
    // ============================================================

    private fun extractUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun copyToCache(uri: Uri): Pair<File, String>? {
        return try {
            val originalName = queryDisplayName(uri) ?: "share_${System.currentTimeMillis()}"
            val nameWithExt = if (originalName.contains('.')) originalName else {
                val mime = contentResolver.getType(uri) ?: ""
                val ext = when {
                    mime.startsWith("image/") -> ".jpg"
                    mime.startsWith("video/") -> ".mp4"
                    mime.startsWith("audio/") -> ".mp3"
                    mime == "application/pdf" -> ".pdf"
                    mime == "application/zip" -> ".zip"
                    else -> ".bin"
                }
                originalName + ext
            }

            val tempDir = File(cacheDir, "share_temp").apply { mkdirs() }
            var tempFile = File(tempDir, nameWithExt)
            if (tempFile.exists()) {
                val dot = nameWithExt.lastIndexOf('.')
                val base = if (dot > 0) nameWithExt.substring(0, dot) else nameWithExt
                val ext = if (dot > 0) nameWithExt.substring(dot) else ""
                tempFile = File(tempDir, "${base}_${System.currentTimeMillis()}$ext")
            }

            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            tempFile to tempFile.name
        } catch (e: Exception) {
            Log.e(TAG, "复制失败: $uri", e)
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var len = bytes.toDouble()
        var order = 0
        while (len >= 1024 && order < units.size - 1) {
            order++
            len /= 1024
        }
        return String.format("%.2f %s", len, units[order])
    }
}