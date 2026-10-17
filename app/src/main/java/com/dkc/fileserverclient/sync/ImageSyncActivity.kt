package com.dkc.fileserverclient.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dkc.fileserverclient.FileServerService
import com.dkc.fileserverclient.R
import com.dkc.fileserverclient.transfer.TransferManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

class ImageSyncActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImageSyncActivity"
        private const val TARGET_DIR = "data/图片"
        private const val HASH_CONCURRENCY = 4
    }

    private lateinit var serverUrl: String
    private lateinit var backButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var statsText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var scanAllButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var recyclerView: RecyclerView

    private val fileServerService by lazy { FileServerService(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var hashCache: LocalHashCache
    private lateinit var adapter: SyncAdapter

    private var localImages: List<LocalImage> = emptyList()
    private var onlyCamera = true
    private var isBusy = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startScan()
            else {
                Toast.makeText(this, "需要读取图片权限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_sync)

        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (serverUrl.isEmpty()) { finish(); return }

        hashCache = LocalHashCache(this).apply { load() }

        initViews()
        checkPermissionAndScan()
    }

    private fun initViews() {
        backButton = findViewById(R.id.syncBackButton)
        refreshButton = findViewById(R.id.syncRefreshButton)
        statsText = findViewById(R.id.syncStatsText)
        progressBar = findViewById(R.id.syncProgressBar)
        progressText = findViewById(R.id.syncProgressText)
        scanAllButton = findViewById(R.id.syncScanAllButton)
        startButton = findViewById(R.id.syncStartButton)
        recyclerView = findViewById(R.id.syncRecyclerView)

        adapter = SyncAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { checkPermissionAndScan() }
        scanAllButton.setOnClickListener {
            onlyCamera = !onlyCamera
            scanAllButton.text = if (onlyCamera) "扫描全部相册" else "只扫相机"
            checkPermissionAndScan()
        }
        startButton.setOnClickListener { startSync() }
    }

    private fun checkPermissionAndScan() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            startScan()
        else permissionLauncher.launch(perm)
    }

    // ============================================================
    // 扫描 + 计算哈希
    // ============================================================
    private fun startScan() {
        if (isBusy) return
        isBusy = true
        startButton.isEnabled = false
        scope.launch {
            try {
                updateStats("正在扫描本地图片...")
                localImages = LocalImageScanner.scan(this@ImageSyncActivity, onlyCamera)

                if (localImages.isEmpty()) {
                    updateStats("没有找到本地图片")
                    adapter.update(emptyList())
                    return@launch
                }

                // 计算哈希（带缓存）
                updateStats("计算哈希 0/${localImages.size}")
                val sem = Semaphore(HASH_CONCURRENCY)
                var done = 0
                val total = localImages.size

                val jobs = localImages.map { img ->
                    scope.async(Dispatchers.IO) {
                        sem.withPermit {
                            // 缓存命中
                            val cached = hashCache.get(img.mediaId, img.size, img.dateModified)
                            val hash = cached ?: HashUtils.sha256(this@ImageSyncActivity, img.uri)?.also {
                                hashCache.put(img.mediaId, it, img.size, img.dateModified)
                            }
                            img.hash = hash
                            withContext(Dispatchers.Main) {
                                done++
                                updateProgress(done, total)
                            }
                        }
                    }
                }
                jobs.awaitAll()
                hashCache.save()

                val withHash = localImages.filter { it.hash != null }
                updateStats("共 ${total} 张，已算哈希 ${withHash.size} 张")

                adapter.update(localImages)

                // 自动比对服务端
                compareWithServer(withHash)

            } catch (e: Exception) {
                Log.e(TAG, "scan error", e)
                updateStats("扫描失败: ${e.message}")
            } finally {
                isBusy = false
                startButton.isEnabled = true
            }
        }
    }

    /**
     * 批量查询服务端已有哈希。
     */
    private suspend fun compareWithServer(withHash: List<LocalImage>) {
        if (withHash.isEmpty()) return
        updateStats("查询服务端哈希...")
        val hashes = withHash.mapNotNull { it.hash }
        val existing = fileServerService.checkHashes(serverUrl, hashes)

        var already = 0
        for (img in withHash) {
            if (img.hash != null && existing.containsKey(img.hash)) {
                img.alreadyOnServer = true
                already++
            }
        }

        adapter.update(localImages)
        updateStats("本地 ${localImages.size} 张，服务端已有 $already 张，待同步 ${localImages.size - already} 张")
    }

    // ============================================================
    // 开始同步（秒传 + 上传）
    // ============================================================
    private fun startSync() {
        val pending = localImages.filter {
            it.hash != null && !it.alreadyOnServer && !it.quickUploaded && !it.uploadQueued
        }
        if (pending.isEmpty()) {
            Toast.makeText(this, "没有需要同步的图片", Toast.LENGTH_SHORT).show()
            return
        }
        if (isBusy) return
        isBusy = true

        scope.launch {
            try {
                var quickCount = 0
                var uploadCount = 0
                var done = 0

                // 1. 先查一次服务端哈希（可能上次统计后服务端有新上传）
                val hashMap = fileServerService.checkHashes(
                    serverUrl,
                    pending.mapNotNull { it.hash }
                )

                for (img in pending) {
                    val hash = img.hash ?: continue

                    if (hashMap.containsKey(hash)) {
                        // 秒传
                        val ok = fileServerService.quickUpload(
                            serverUrl = serverUrl,
                            hash = hash,
                            targetDir = TARGET_DIR,
                            fileName = img.displayName
                        )
                        if (ok) {
                            img.quickUploaded = true
                            quickCount++
                        } else {
                            img.errorMessage = "秒传失败"
                        }
                    } else {
                        // 真实上传
                        val tempFile = copyUriToFile(img.uri, img.displayName)
                        if (tempFile != null) {
                            TransferManager.enqueueUpload(
                                serverUrl = serverUrl,
                                localFile = tempFile,
                                remoteDir = TARGET_DIR
                            )
                            img.uploadQueued = true
                            uploadCount++
                        } else {
                            img.errorMessage = "复制到缓存失败"
                        }
                    }

                    done++
                    updateProgress(done, pending.size)
                }

                adapter.update(localImages)
                updateStats("同步完成：秒传 $quickCount 个，上传队列 $uploadCount 个")

            } catch (e: Exception) {
                Log.e(TAG, "sync error", e)
                updateStats("同步失败: ${e.message}")
            } finally {
                isBusy = false
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================
    private suspend fun copyUriToFile(
        uri: android.net.Uri,
        displayName: String
    ): File? = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(cacheDir, "sync_temp").apply { mkdirs() }
            val f = File(tempDir, displayName)
            contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            if (f.exists()) f else null
        } catch (e: Exception) {
            Log.e(TAG, "复制失败", e)
            null
        }
    }

    private fun updateStats(text: String) {
        statsText.text = text
    }

    private fun updateProgress(done: Int, total: Int) {
        if (total <= 0) { progressBar.progress = 0; progressText.text = ""; return }
        val percent = (done * 100 / total).coerceIn(0, 100)
        progressBar.progress = percent
        progressText.text = "$done / $total"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}