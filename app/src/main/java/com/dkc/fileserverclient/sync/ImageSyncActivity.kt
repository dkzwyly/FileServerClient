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
        // ★ 图片和视频统一进 data/图片
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

    private var localMedia: List<LocalMedia> = emptyList()
    private var onlyCamera = true
    private var isBusy = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) startScan()
        else {
            Toast.makeText(this, "需要读取图片和视频权限", Toast.LENGTH_SHORT).show()
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

    // ============================================================
    // 权限
    // ============================================================
    private fun checkPermissionAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startScan()
        else permissionLauncher.launch(perms)
    }

    // ============================================================
    // ★ 扫描 + 计算哈希（用 LocalMediaScanner）
    // ============================================================
    private fun startScan() {
        if (isBusy) return
        isBusy = true
        startButton.isEnabled = false

        scope.launch {
            try {
                updateStats("正在扫描本地图片和视频...")
                localMedia = LocalMediaScanner.scan(this@ImageSyncActivity, onlyCamera)

                if (localMedia.isEmpty()) {
                    updateStats("没有找到本地媒体")
                    adapter.update(emptyList())
                    return@launch
                }

                val imageCount = localMedia.count { !it.isVideo }
                val videoCount = localMedia.count { it.isVideo }
                updateStats("扫描到 $imageCount 张图片，$videoCount 个视频，计算哈希中...")

                val total = localMedia.size
                val sem = Semaphore(HASH_CONCURRENCY)
                var done = 0

                localMedia.map { media ->
                    scope.async(Dispatchers.IO) {
                        sem.withPermit {
                            val cacheKey = mediaCacheKey(media)
                            val cached = hashCache.get(cacheKey, media.size, media.dateModified)
                            val hash = cached ?: HashUtils.sha256(this@ImageSyncActivity, media.uri)?.also {
                                hashCache.put(cacheKey, it, media.size, media.dateModified)
                            }
                            media.hash = hash
                            withContext(Dispatchers.Main) {
                                done++
                                updateProgress(done, total)
                            }
                        }
                    }
                }.awaitAll()
                hashCache.save()

                val withHash = localMedia.filter { it.hash != null }
                updateStats("共 $total 个，已算哈希 ${withHash.size} 个")
                adapter.update(localMedia)
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
     * 图片和视频的 mediaId 是两套独立编号，可能重复。
     * 用负数编码视频，避免缓存 key 冲突：
     *   image: mediaId
     *   video: -mediaId - 1
     */
    private fun mediaCacheKey(media: LocalMedia): Long =
        if (media.isVideo) -media.mediaId - 1 else media.mediaId

    // ============================================================
    // 比对服务端
    // ============================================================
    private suspend fun compareWithServer(withHash: List<LocalMedia>) {
        if (withHash.isEmpty()) return
        updateStats("查询服务端哈希...")
        val hashes = withHash.mapNotNull { it.hash }
        val existing = fileServerService.checkHashes(serverUrl, hashes)

        var already = 0
        for (media in withHash) {
            if (media.hash != null && existing.containsKey(media.hash)) {
                media.alreadyOnServer = true
                already++
            }
        }

        adapter.update(localMedia)
        updateStats("本地 ${localMedia.size} 个，服务端已有 $already 个，待同步 ${localMedia.size - already} 个")
    }

    // ============================================================
// 开始同步
// ============================================================
    private fun startSync() {
        val pending = localMedia.filter {
            it.hash != null && !it.alreadyOnServer && !it.quickUploaded && !it.uploadQueued
        }
        if (pending.isEmpty()) {
            Toast.makeText(this, "没有需要同步的媒体", Toast.LENGTH_SHORT).show()
            return
        }
        if (isBusy) return
        isBusy = true

        scope.launch {
            try {
                var quickCount = 0
                var uploadCount = 0
                var done = 0

                val hashMap = fileServerService.checkHashes(serverUrl, pending.mapNotNull { it.hash })

                for (media in pending) {
                    val hash = media.hash ?: continue
                    if (hashMap.containsKey(hash)) {
                        val ok = fileServerService.quickUpload(
                            serverUrl = serverUrl,
                            hash = hash,
                            targetDir = TARGET_DIR,
                            fileName = media.displayName
                        )
                        if (ok) {
                            media.quickUploaded = true
                            quickCount++
                        } else {
                            media.errorMessage = "秒传失败"
                        }
                    } else {
                        // ★ 改为 Uri 流式上传：立即入队，不落盘，失败交给 TransferManager 重试
                        val safeName = media.displayName.ifBlank { "unknown_${media.mediaId}" }
                        TransferManager.enqueueUploadUri(
                            serverUrl = serverUrl,
                            uri = media.uri,
                            fileName = safeName,
                            fileSize = if (media.size > 0) media.size else -1L,
                            remoteDir = TARGET_DIR
                        )
                        media.uploadQueued = true
                        uploadCount++
                    }
                    done++
                    updateProgress(done, pending.size)
                }

                adapter.update(localMedia)
                updateStats("同步完成：秒传 $quickCount 个，已加入上传队列 $uploadCount 个")
            } catch (e: Exception) {
                Log.e(TAG, "sync error", e)
                updateStats("同步失败: ${e.message}")
            } finally {
                isBusy = false
            }
        }
    }
    private fun updateStats(text: String) { statsText.text = text }

    private fun updateProgress(done: Int, total: Int) {
        if (total <= 0) { progressBar.progress = 0; progressText.text = ""; return }
        progressBar.progress = (done * 100 / total).coerceIn(0, 100)
        progressText.text = "$done / $total"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}