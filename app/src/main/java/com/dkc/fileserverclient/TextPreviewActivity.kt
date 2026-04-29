package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class TextPreviewActivity : AppCompatActivity(), AudiobookService.Callback {

    private lateinit var textContentTextView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var rootLayout: RelativeLayout
    private lateinit var chapterButton: ImageButton
    private lateinit var statusLabel: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var audioButton: ImageButton

    private lateinit var viewModel: TextPreviewViewModel
    private val isFirstLayoutComplete = AtomicBoolean(false)
    private var linesPerPage = 20
    private var lastClickTime = 0L
    private val minClickInterval = 200L

    // 听书服务
    private var audiobookService: AudiobookService? = null
    private var isBound = false
    private var isAutoReading = false   // 是否自动播放中
    private var pendingAutoRead = false  // 等待新页面加载后自动朗读
    private var currentClientPage = 1   // 用于 utteranceId

    // 历史记录
    private lateinit var readingHistoryFile: File
    private lateinit var currentFileName: String
    private lateinit var currentFileUrl: String
    private lateinit var currentFilePath: String

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            audiobookService = (service as AudiobookService.LocalBinder).getService()
            audiobookService?.callback = this@TextPreviewActivity
            isBound = true
            Log.d("TextPreview", "听书服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audiobookService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_preview)

        initViews()
        setupIntentData()
        initViewModel()
        setupLayoutListener()
        setupObservers()
        applyWindowInsets()
        applyDisplaySettings()
        loadReadingHistory()
        calculateLinesPerPageAndLoad()
        bindAudiobookService()
    }

    private fun bindAudiobookService() {
        val intent = Intent(this, AudiobookService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun initViews() {
        textContentTextView = findViewById(R.id.textContentTextView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        pageIndicator = findViewById(R.id.pageIndicator)
        rootLayout = findViewById(R.id.rootLayout)
        chapterButton = findViewById(R.id.chapterButton)
        statusLabel = findViewById(R.id.statusLabel)
        settingsButton = findViewById(R.id.settingsButton)
        audioButton = findViewById(R.id.audioButton)

        supportActionBar?.hide()
        textContentTextView.isScrollContainer = false

        // 章节按钮（右上角透明）
        chapterButton.setBackgroundResource(android.R.color.transparent)
        chapterButton.setImageResource(android.R.color.transparent)
        chapterButton.alpha = 0.0f
        chapterButton.bringToFront()
        chapterButton.setOnClickListener { showChapterDialog() }
        chapterButton.isVisible = true

        // 底部透明按钮
        setupTransparentButton(settingsButton) { showDisplaySettingsDialog() }
        setupTransparentButton(audioButton) { onAudioButtonClicked() }
        updateAudioButtonIcon()

        pageIndicator.textSize = 12f
        pageIndicator.setBackgroundColor(Color.TRANSPARENT)
        pageIndicator.setTextColor(Color.parseColor("#666666"))

        // 触摸翻页
        setupTouchListener()
    }

    private fun setupTransparentButton(button: ImageButton, onClick: () -> Unit) {
        button.setBackgroundResource(android.R.color.transparent)
        button.setImageResource(android.R.color.transparent)
        button.alpha = 0.0f
        button.isVisible = true
        button.setOnClickListener { onClick() }
    }

    private fun updateAudioButtonIcon() {
        val iconRes = if (isAutoReading) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        audioButton.setImageResource(iconRes)
    }

    private fun onAudioButtonClicked() {
        if (!isBound || audiobookService == null) {
            Toast.makeText(this, "语音服务未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (isAutoReading) {
            audiobookService?.pause()
            isAutoReading = false
            pendingAutoRead = false
            updateAudioButtonIcon()
            Toast.makeText(this, "已暂停", Toast.LENGTH_SHORT).show()
        } else {
            val text = textContentTextView.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "没有可朗读的内容", Toast.LENGTH_SHORT).show()
                return
            }
            isAutoReading = true
            pendingAutoRead = false
            updateAudioButtonIcon()
            audiobookService?.updateNotification("《$currentFileName》")
            audiobookService?.play(text, "page_$currentClientPage")
        }
    }

    // ---------- 听书回调 ----------
    override fun onPlaybackStart() {
        Log.d("TextPreview", "朗读开始")
        isAutoReading = true
        updateAudioButtonIcon()
    }

    override fun onPlaybackComplete(utteranceId: String) {
        Log.d("TextPreview", "朗读完成: $utteranceId")
        if (!isAutoReading) return

        val pageInfo = viewModel.pageInfo.value
        if (pageInfo != null && pageInfo.currentPage >= pageInfo.totalPages) {
            audiobookService?.stop()
            isAutoReading = false
            pendingAutoRead = false
            updateAudioButtonIcon()
            Toast.makeText(this, "已读完", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.nextPage()
        pendingAutoRead = true
    }

    override fun onPlaybackPause() {
        isAutoReading = false
        updateAudioButtonIcon()
    }

    override fun onPlaybackStop() {
        isAutoReading = false
        pendingAutoRead = false
        updateAudioButtonIcon()
    }

    override fun onPlaybackError(error: String?) {
        Toast.makeText(this, error ?: "播放出错", Toast.LENGTH_SHORT).show()
        isAutoReading = false
        pendingAutoRead = false
        updateAudioButtonIcon()
    }

    // ---------- ViewModel 观察者 ----------
    private fun setupObservers() {
        viewModel.pageContent.observe(this) { content ->
            textContentTextView.text = content
            if (pendingAutoRead && isAutoReading && !content.isNullOrBlank()) {
                pendingAutoRead = false
                audiobookService?.play(content, "page_$currentClientPage")
            }
        }

        viewModel.pageInfo.observe(this) { pageInfo ->
            val progressText = "${pageInfo.currentPage}/${pageInfo.totalPages} (${pageInfo.progress}%)"
            pageIndicator.text = progressText
            currentClientPage = pageInfo.currentPage
        }

        viewModel.loadingState.observe(this) { loadingState ->
            if (loadingState.isLoading) showLoadingState(loadingState.message)
            else showContentState()
        }

        viewModel.errorMessage.observe(this) { errorMsg ->
            if (errorMsg != null) showErrorState(errorMsg)
        }

        viewModel.chapters.observe(this) { chapters ->
            if (chapters.isNotEmpty()) showChapterList(chapters)
            else showNoChaptersDialog()
        }

        viewModel.currentPageState.observe(this) { pageState ->
            pageState?.let { saveReadingHistory(it.serverPage, it.clientPage) }
        }
    }

    // ---------- 触摸翻页 ----------
    private fun setupTouchListener() {
        rootLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> return@setOnTouchListener true
                MotionEvent.ACTION_UP -> {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime < minClickInterval) return@setOnTouchListener true
                    lastClickTime = currentTime

                    val screenWidth = resources.displayMetrics.widthPixels
                    val x = event.x
                    val y = event.y

                    val chapterButtonRect = android.graphics.Rect(screenWidth - 150, 0, screenWidth, 150)
                    if (chapterButtonRect.contains(x.toInt(), y.toInt())) {
                        chapterButton.performClick()
                        return@setOnTouchListener true
                    }

                    if (x < screenWidth / 3) {
                        viewModel.previousPage()
                        if (isAutoReading) {
                            audiobookService?.pause()
                            pendingAutoRead = true
                        }
                        return@setOnTouchListener true
                    } else if (x > screenWidth * 2 / 3) {
                        viewModel.nextPage()
                        if (isAutoReading) {
                            audiobookService?.pause()
                            pendingAutoRead = true
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    // ---------- 显示设置（含语速） ----------
    private fun showDisplaySettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("阅读设置")
            .setItems(arrayOf("字体大小", "背景颜色", "朗读速度")) { _, which ->
                when (which) {
                    0 -> showFontSizeDialog()
                    1 -> showBackgroundColorDialog()
                    2 -> showSpeechRateDialog()
                }
            }
            .show()
    }

    private fun showSpeechRateDialog() {
        val currentRate = audiobookService?.let { ReadingSettings.getSpeechRate(this) } ?: 1.0f
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }
        val previewText = TextView(this).apply {
            text = "当前语速：${"%.1f".format(currentRate)}x"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        val seekBar = SeekBar(this).apply {
            max = 15  // 0.5 ~ 2.0 步长 0.1
            progress = ((currentRate - 0.5f) / 0.1f).toInt().coerceIn(0, 15)
        }
        val rangeLabel = TextView(this).apply {
            text = "0.5x                             2.0x"
            setPadding(0, 0, 0, 10)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        layout.addView(previewText)
        layout.addView(seekBar)
        layout.addView(rangeLabel)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + progress * 0.1f
                previewText.text = "当前语速：${"%.1f".format(rate)}x"
                audiobookService?.setSpeechRate(rate)
                ReadingSettings.setSpeechRate(this@TextPreviewActivity, rate)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("朗读速度")
            .setView(layout)
            .setCancelable(true)
            .show()
    }

    private fun showFontSizeDialog() {
        val currentSize = ReadingSettings.getFontSize(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }
        val previewText = TextView(this).apply {
            text = "预览字体大小：${currentSize.toInt()}sp"
            textSize = currentSize
            setPadding(0, 0, 0, 20)
            gravity = Gravity.CENTER
        }
        val seekBar = SeekBar(this).apply {
            max = 30
            progress = currentSize.toInt()
        }
        val rangeLabel = TextView(this).apply {
            text = "10sp                             30sp"
            setPadding(0, 0, 0, 10)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        layout.addView(previewText)
        layout.addView(seekBar)
        layout.addView(rangeLabel)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val selectedSize = progress.coerceAtLeast(10).toFloat()
                previewText.text = "预览字体大小：${selectedSize.toInt()}sp"
                previewText.textSize = selectedSize
                textContentTextView.textSize = selectedSize
                ReadingSettings.setFontSize(this@TextPreviewActivity, selectedSize)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("调整字体大小")
            .setView(layout)
            .setCancelable(true)
            .create()
        dialog.setOnDismissListener { recalculatePagingAfterSettingsChange() }
        dialog.show()
    }

    private fun showBackgroundColorDialog() {
        val colors = arrayOf("纯白", "护眼米色", "深色", "纯黑")
        val bgValues = arrayOf(
            ReadingSettings.BG_WHITE,
            ReadingSettings.BG_EYE_CARE,
            ReadingSettings.BG_DARK,
            ReadingSettings.BG_BLACK
        )
        val currentColor = ReadingSettings.getBackgroundColor(this)
        val checked = bgValues.indexOfFirst { it == currentColor }.let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("选择背景颜色")
            .setSingleChoiceItems(colors, checked) { dialog, which ->
                val bgColor = bgValues[which]
                ReadingSettings.setBackgroundColor(this, bgColor)
                applyBackgroundColor(bgColor)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyDisplaySettings() {
        val fontSize = ReadingSettings.getFontSize(this)
        textContentTextView.textSize = fontSize
        val bgColor = ReadingSettings.getBackgroundColor(this)
        applyBackgroundColor(bgColor)
    }

    private fun applyBackgroundColor(bgColor: Int) {
        rootLayout.setBackgroundColor(bgColor)
        val textColor = ReadingSettings.getTextColorForBg(bgColor)
        textContentTextView.setTextColor(textColor)
        pageIndicator.setTextColor(textColor)
    }

    private fun recalculatePagingAfterSettingsChange() {
        rootLayout.post {
            linesPerPage = calculateMaxLines()
            viewModel.loadTextContent(linesPerPage)
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomBar = findViewById<LinearLayout>(R.id.bottomBar)
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop,
                bottomBar.paddingRight, systemBars.bottom
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
    }

    // ---------- 数据与布局 ----------
    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFilePath = intent.getStringExtra("FILE_PATH") ?: ""

        val historyDir = File(filesDir, "reading_history")
        if (!historyDir.exists()) historyDir.mkdirs()
        val safeFileName = currentFileName.replace("[^a-zA-Z0-9]".toRegex(), "_")
        readingHistoryFile = File(historyDir, "history_${safeFileName}.dat")
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this).get(TextPreviewViewModel::class.java)
        viewModel.initialize(currentFileName, currentFileUrl, currentFilePath)
    }

    private fun setupLayoutListener() {
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            if (!isFirstLayoutComplete.get()) {
                isFirstLayoutComplete.set(true)
                calculateLinesPerPageAndLoad()
            }
        }
    }

    private fun calculateLinesPerPageAndLoad() {
        if (isFirstLayoutComplete.get()) {
            linesPerPage = calculateMaxLines()
            viewModel.loadTextContent(linesPerPage)
        }
    }

    private fun calculateMaxLines(): Int {
        return try {
            if (textContentTextView.height == 0) textContentTextView.measure(0, 0)
            val height = textContentTextView.measuredHeight
            val lineHeight = textContentTextView.lineHeight
            val paddingTop = textContentTextView.paddingTop
            val paddingBottom = textContentTextView.paddingBottom
            val availableHeight = height - paddingTop - paddingBottom
            val maxLines = (availableHeight / lineHeight).toInt()
            (maxLines - 2).coerceAtLeast(1)
        } catch (e: Exception) {
            18
        }
    }

    // ---------- 章节 ----------
    private fun showChapterDialog() {
        statusLabel.isVisible = true
        statusLabel.text = "正在从服务器加载章节..."
        CoroutineScope(Dispatchers.Main).launch { viewModel.loadChapters() }
    }

    private fun showChapterList(chapters: List<TextPreviewViewModel.ChapterInfo>) {
        val chapterTitles = chapters.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("章节跳转 (${chapters.size}章)")
            .setItems(chapterTitles) { _, which ->
                val chapter = chapters[which]
                viewModel.jumpToChapter(chapter)
                Toast.makeText(this, "跳转到: ${chapter.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNoChaptersDialog() {
        AlertDialog.Builder(this)
            .setTitle("未发现章节")
            .setMessage("该文件可能没有章节标记，或者章节索引尚未构建。")
            .setPositiveButton("确定", null)
            .show()
    }

    // ---------- UI 状态 ----------
    private fun showLoadingState(message: String? = null) {
        loadingProgress.isVisible = true
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = false
        chapterButton.isVisible = true
        statusLabel.isVisible = true
        statusLabel.text = message ?: "正在加载..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        pageIndicator.isVisible = true
        errorTextView.isVisible = false
        chapterButton.isVisible = true
        statusLabel.isVisible = false
    }

    private fun showErrorState(message: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        pageIndicator.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = message
        chapterButton.isVisible = true
        statusLabel.isVisible = false
    }

    // ---------- 历史记录 ----------
    private fun loadReadingHistory() {
        if (readingHistoryFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(readingHistoryFile)).use { ois ->
                    val history = ois.readObject() as? ReadingHistory
                    history?.let {
                        if (it.fileName == currentFileName || it.fileUrl == currentFileUrl) {
                            viewModel.restoreFromHistory(it.serverPage, it.clientPage)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TextPreview", "加载历史记录失败", e)
            }
        }
    }

    private fun saveReadingHistory(serverPage: Int, clientPage: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val history = ReadingHistory(
                    fileName = currentFileName,
                    fileUrl = currentFileUrl,
                    serverPage = serverPage,
                    clientPage = clientPage,
                    timestamp = System.currentTimeMillis()
                )
                ObjectOutputStream(FileOutputStream(readingHistoryFile)).use { oos ->
                    oos.writeObject(history)
                }
            } catch (e: Exception) {
                Log.e("TextPreview", "保存历史记录失败", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.serverPage, it.clientPage)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            audiobookService?.callback = null
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, AudiobookService::class.java))
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.serverPage, it.clientPage)
        }
    }
}

// 历史记录数据类
data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val serverPage: Int,
    val clientPage: Int,
    val timestamp: Long
) : java.io.Serializable