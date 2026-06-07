package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
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

class TextPreviewActivity : AppCompatActivity() {

    private lateinit var textContentTextView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var rootLayout: RelativeLayout
    private lateinit var chapterButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var progressTextView: TextView
    private lateinit var statusLabel: TextView

    private lateinit var viewModel: TextPreviewViewModel
    private lateinit var gestureDetector: GestureDetector

    private val isLayoutReady = AtomicBoolean(false)
    private var lastClickTime = 0L
    private val minClickInterval = 200L

    private lateinit var readingHistoryFile: File
    private lateinit var currentFileName: String
    private lateinit var currentFileUrl: String
    private lateinit var currentFilePath: String

    private var currentFontSize: Float = 16f
    private var currentBackgroundColor: Int = Color.WHITE
    private lateinit var prefs: SharedPreferences

    private var currentAbsoluteOffset: Int = 0

    // 听书相关成员变量
    private lateinit var voiceMenuButton: ImageButton
    private var currentEngine: VoiceEngine? = null
    private var isAutoPlay = false
    private var audiobookService: AudiobookService? = null
    private var isBound = false
    private var selectedEngineType: EngineType? = null

    private var pendingAutoPlay = false
    private var lastUtteranceId = ""

    // 新增：用于observeForever的观察者，以便后台也能触发自动播放
    private var autoPlayObserver: Observer<CharSequence>? = null

    private enum class EngineType { LOCAL, CLOUD }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            audiobookService = (service as AudiobookService.LocalBinder).getService()
            // 应用已保存的语速
            val savedSpeed = prefs.getFloat("tts_speed", 1.0f)
            audiobookService?.setSpeechRate(savedSpeed)

            if (selectedEngineType == EngineType.LOCAL) {
                ensureLocalEngineReady()
                if (audiobookService?.isReady() == true) {
                    tryStartAutoPlay()
                } else {
                    audiobookService?.onTtsReadyListener = {
                        tryStartAutoPlay()
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            audiobookService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveMode()
        setContentView(R.layout.activity_text_preview)

        prefs = getSharedPreferences("reading_prefs", MODE_PRIVATE)
        loadAppearancePrefs()

        initViews()
        setupIntentData()
        initViewModel()
        setupGestureDetector()
        setupObservers()
        setupLayoutListener()
        applyAppearance()

        textContentTextView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (textContentTextView.height > 0) {
                recalcLinesPerPage()
            }
        }
        loadReadingHistory()
    }

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }

    private fun loadAppearancePrefs() {
        currentFontSize = prefs.getFloat("font_size", 16f)
        currentBackgroundColor = prefs.getInt("bg_color", Color.WHITE)
    }

    private fun saveAppearancePrefs() {
        prefs.edit()
            .putFloat("font_size", currentFontSize)
            .putInt("bg_color", currentBackgroundColor)
            .apply()
    }

    private fun applyAppearance() {
        textContentTextView.textSize = currentFontSize
        rootLayout.setBackgroundColor(currentBackgroundColor)
        progressTextView.setTextColor(getContrastColor(currentBackgroundColor))
    }

    private fun getContrastColor(bgColor: Int): Int {
        val r = Color.red(bgColor)
        val g = Color.green(bgColor)
        val b = Color.blue(bgColor)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        return if (luminance > 0.5) Color.DKGRAY else Color.argb(0x80, 0xFF, 0xFF, 0xFF)
    }

    private fun initViews() {
        textContentTextView = findViewById(R.id.textContentTextView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorTextView = findViewById(R.id.errorTextView)
        rootLayout = findViewById(R.id.rootLayout)
        chapterButton = findViewById(R.id.chapterButton)
        settingsButton = findViewById(R.id.settingsButton)
        progressTextView = findViewById(R.id.progressTextView)
        statusLabel = findViewById(R.id.statusLabel)
        voiceMenuButton = findViewById(R.id.voiceMenuButton)

        supportActionBar?.hide()
        textContentTextView.isScrollContainer = false

        chapterButton.setBackgroundResource(android.R.color.transparent)
        chapterButton.setImageResource(android.R.color.transparent)
        chapterButton.alpha = 0.0f
        chapterButton.bringToFront()
        chapterButton.setOnClickListener { showChapterDialog() }
        chapterButton.isVisible = true

        settingsButton.setBackgroundResource(android.R.color.transparent)
        settingsButton.setImageResource(android.R.color.transparent)
        settingsButton.alpha = 0.0f
        settingsButton.bringToFront()
        settingsButton.setOnClickListener { showAppearanceDialog() }
        settingsButton.isVisible = true

        progressTextView.bringToFront()

        voiceMenuButton.setOnClickListener { showVoiceEngineMenu() }
    }

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
        viewModel = ViewModelProvider(this)[TextPreviewViewModel::class.java]
        viewModel.initialize(currentFileName, currentFileUrl, currentFilePath)
    }

    private fun setupObservers() {
        viewModel.pageContent.observe(this) { content ->
            textContentTextView.text = content
        }
        viewModel.loadingState.observe(this) { state ->
            if (state.isLoading) showLoadingState(state.message) else showContentState()
        }
        viewModel.errorMessage.observe(this) { error ->
            error?.let { showErrorState(it) }
        }
        viewModel.showChapterDialogEvent.observe(this) { chapters ->
            if (chapters.isNotEmpty()) showChapterList(chapters)
            else showNoChaptersDialog()
        }
        viewModel.currentPageState.observe(this) { state ->
            if (state != null) {
                saveReadingHistory(state.blockPage, state.subPage)
                val progress = ((state.blockPage - 1).toFloat() +
                        (state.subPage - 1).toFloat() / state.totalSubPages) /
                        state.totalBlockPages * 100f
                progressTextView.text = String.format("%.2f%%", progress)
            } else {
                progressTextView.text = "0.00%"
            }
        }
        viewModel.currentAbsoluteCharOffset.observe(this) { offset ->
            currentAbsoluteOffset = offset
        }
        setupAutoPlayObserver()
    }

    private fun setupLayoutListener() {
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isLayoutReady.get() && textContentTextView.height > 0) {
                    rootLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    isLayoutReady.set(true)

                    val width = textContentTextView.width - textContentTextView.paddingLeft - textContentTextView.paddingRight
                    val paint = textContentTextView.paint
                    val extra = textContentTextView.lineSpacingExtra
                    val multiplier = textContentTextView.lineSpacingMultiplier
                    viewModel.setDisplayParams(width, paint, extra, multiplier)

                    recalcLinesPerPage()
                    viewModel.loadTextContent()
                }
            }
        })
    }

    private fun recalcLinesPerPage() {
        val maxHeight = textContentTextView.height - textContentTextView.paddingTop - textContentTextView.paddingBottom
        if (maxHeight <= 0) return
        val lines = viewModel.calculateMaxLinesPerPage(maxHeight)
        if (lines > 0) {
            viewModel.setLinesPerPage(lines)
        }
    }

    private fun recalcLinesPerPageAndKeepPosition() {
        val width = textContentTextView.width - textContentTextView.paddingLeft - textContentTextView.paddingRight
        val paint = textContentTextView.paint
        val extra = textContentTextView.lineSpacingExtra
        val multiplier = textContentTextView.lineSpacingMultiplier
        viewModel.setDisplayParams(width, paint, extra, multiplier)

        val maxHeight = textContentTextView.height - textContentTextView.paddingTop - textContentTextView.paddingBottom
        if (maxHeight <= 0) return
        val lines = viewModel.calculateMaxLinesPerPage(maxHeight)
        if (lines > 0) {
            viewModel.onFontSizeChanged(lines)
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < minClickInterval) return true
                lastClickTime = currentTime

                val screenWidth = resources.displayMetrics.widthPixels
                val x = e.x

                if (x > screenWidth - 150 && e.y < 150) {
                    chapterButton.performClick()
                    return true
                }
                if (x > screenWidth - 150 && e.y > resources.displayMetrics.heightPixels - 150) {
                    settingsButton.performClick()
                    return true
                }

                if (x < screenWidth / 3) manualPrevPage()
                else if (x > screenWidth * 2 / 3) manualNextPage()
                return true
            }
        })

        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // ==================== 设置对话框（含语速调节） ====================
    private fun showAppearanceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reading_settings, null)
        val seekBarFontSize = dialogView.findViewById<SeekBar>(R.id.seekBarFontSize)
        val fontSizeValue = dialogView.findViewById<TextView>(R.id.fontSizeValue)
        val btnColorWhite = dialogView.findViewById<Button>(R.id.btnColorWhite)
        val btnColorCream = dialogView.findViewById<Button>(R.id.btnColorCream)
        val btnColorGreen = dialogView.findViewById<Button>(R.id.btnColorGreen)
        val btnColorBlack = dialogView.findViewById<Button>(R.id.btnColorBlack)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnApply = dialogView.findViewById<Button>(R.id.btnApply)

        // 语速控件
        val seekBarSpeed = dialogView.findViewById<SeekBar>(R.id.seekBarSpeed)
        val speedValue = dialogView.findViewById<TextView>(R.id.speedValue)

        val originalFontSize = currentFontSize
        val originalBgColor = currentBackgroundColor
        val originalSpeed = prefs.getFloat("tts_speed", 1.0f)

        // 字体大小 SeekBar
        seekBarFontSize.max = 30 - 12
        seekBarFontSize.progress = (currentFontSize - 12).toInt()
        fontSizeValue.text = "${currentFontSize.toInt()}sp"

        // 语速 SeekBar
        val speedProgress = ((originalSpeed - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
        seekBarSpeed.progress = speedProgress
        speedValue.text = String.format("%.1fx", originalSpeed)

        seekBarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = (progress + 12).toFloat()
                textContentTextView.textSize = size
                fontSizeValue.text = "${size.toInt()}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.5f + (progress / 100f) * 1.5f
                speedValue.text = String.format("%.1fx", speed)
                audiobookService?.setSpeechRate(speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnColorWhite.setOnClickListener {
            currentBackgroundColor = Color.WHITE
            rootLayout.setBackgroundColor(Color.WHITE)
            progressTextView.setTextColor(getContrastColor(Color.WHITE))
        }
        btnColorCream.setOnClickListener {
            currentBackgroundColor = Color.parseColor("#FAF0D7")
            rootLayout.setBackgroundColor(currentBackgroundColor)
            progressTextView.setTextColor(getContrastColor(currentBackgroundColor))
        }
        btnColorGreen.setOnClickListener {
            currentBackgroundColor = Color.parseColor("#C8E6C9")
            rootLayout.setBackgroundColor(currentBackgroundColor)
            progressTextView.setTextColor(getContrastColor(currentBackgroundColor))
        }
        btnColorBlack.setOnClickListener {
            currentBackgroundColor = Color.DKGRAY
            rootLayout.setBackgroundColor(currentBackgroundColor)
            progressTextView.setTextColor(getContrastColor(currentBackgroundColor))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            currentFontSize = originalFontSize
            currentBackgroundColor = originalBgColor
            audiobookService?.setSpeechRate(originalSpeed)
            applyAppearance()
            recalcLinesPerPageAndKeepPosition()
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            currentFontSize = (seekBarFontSize.progress + 12).toFloat()
            val finalSpeed = 0.5f + (seekBarSpeed.progress / 100f) * 1.5f
            saveAppearancePrefs()
            prefs.edit().putFloat("tts_speed", finalSpeed).apply()
            audiobookService?.setSpeechRate(finalSpeed)
            applyAppearance()
            recalcLinesPerPageAndKeepPosition()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---------- 章节相关 ----------
    private fun showChapterDialog() {
        statusLabel.isVisible = true
        statusLabel.text = "正在从服务器加载章节..."
        viewModel.loadChapters()
    }

    private fun showChapterList(chapters: List<TextPreviewViewModel.ChapterInfo>) {
        val sortedChapters = chapters.sortedBy { it.startCharOffset }
        val currentChapterIndex = if (sortedChapters.isNotEmpty()) {
            sortedChapters.indexOfLast { it.startCharOffset <= currentAbsoluteOffset }
                .takeIf { it != -1 } ?: -1
        } else -1

        val dialogView = layoutInflater.inflate(R.layout.dialog_chapter_list, null)
        val listView: ListView = dialogView.findViewById(R.id.chapterListView)

        val adapter = object : ArrayAdapter<TextPreviewViewModel.ChapterInfo>(
            this, android.R.layout.simple_list_item_1, sortedChapters
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val chapter = getItem(position)
                view.text = chapter?.title ?: ""
                view.textSize = 16f
                view.setPadding(16, 12, 16, 12)

                val isCurrent = position == currentChapterIndex
                view.setBackgroundColor(
                    if (isCurrent) Color.parseColor("#E8F0FE") else Color.TRANSPARENT
                )
                view.setTextColor(
                    if (isCurrent) Color.parseColor("#1A73E8") else Color.BLACK
                )
                return view
            }
        }

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        if (currentChapterIndex >= 0) {
            listView.setItemChecked(currentChapterIndex, true)
            listView.setSelection(currentChapterIndex)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val chapter = sortedChapters[position]
            viewModel.jumpToChapter(chapter)
            Toast.makeText(this@TextPreviewActivity, "跳转到: ${chapter.title}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showNoChaptersDialog() {
        AlertDialog.Builder(this)
            .setTitle("未发现章节")
            .setMessage("该文件可能没有章节标记，或者章节索引尚未构建。")
            .setPositiveButton("确定", null)
            .show()
    }

    // ---------- 听书相关 ----------
    private fun showVoiceEngineMenu() {
        PopupMenu(this, voiceMenuButton).apply {
            menu.add("本地 TTS")
            menu.add("云端语音服务")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "本地 TTS" -> {
                        selectedEngineType = EngineType.LOCAL
                        pendingAutoPlay = true
                        if (!isBound) {
                            bindService(Intent(this@TextPreviewActivity, AudiobookService::class.java),
                                serviceConnection, Context.BIND_AUTO_CREATE)
                            isBound = true
                        } else {
                            ensureLocalEngineReady()
                            tryStartAutoPlay()
                        }
                    }
                    "云端语音服务" -> {
                        selectedEngineType = EngineType.CLOUD
                        pendingAutoPlay = true
                        switchToCloudTts()
                        tryStartAutoPlay()
                    }
                }
                true
            }
            show()
        }
    }

    private fun ensureLocalEngineReady() {
        if (currentEngine == null && audiobookService != null) {
            switchToLocalTts()
        }
    }

    private fun switchToLocalTts() {
        currentEngine?.release()
        audiobookService?.let { service ->
            // 如果引擎未就绪，重新初始化以检测系统默认引擎
            if (!service.isReady()) {
                service.reinitializeTts()
                service.onTtsReadyListener = {
                    tryStartAutoPlay()
                }
            }
            currentEngine = LocalTtsEngine(service)
        }
    }

    private fun switchToCloudTts() {
        currentEngine?.release()
        currentEngine = CloudTtsEngine(this)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            audiobookService = null
        }
    }

    private fun tryStartAutoPlay() {
        if (!pendingAutoPlay) return
        val content = viewModel.pageContent.value
        if (content.isNullOrBlank()) return
        val engineReady = when (selectedEngineType) {
            EngineType.LOCAL -> audiobookService?.isReady() == true
            EngineType.CLOUD -> true
            else -> false
        }
        if (engineReady && currentEngine?.isPlaying() != true) {
            pendingAutoPlay = false
            isAutoPlay = true
            playCurrentPage()
        }
    }

    private fun playCurrentPage() {
        val content = viewModel.pageContent.value ?: run {
            pendingAutoPlay = true
            return
        }
        val engine = currentEngine ?: return
        val pageState = viewModel.currentPageState.value
        val utteranceId = "page_${pageState?.blockPage}_${pageState?.subPage}_${System.currentTimeMillis()}"
        if (utteranceId == lastUtteranceId && engine.isPlaying()) return

        lastUtteranceId = utteranceId
        engine.play(content.toString(), utteranceId, object : VoiceCallback {
            override fun onStart(utteranceId: String) {
                runOnUiThread { statusLabel.text = "正在朗读" }
            }
            override fun onComplete(utteranceId: String) {
                runOnUiThread {
                    if (isAutoPlay) {
                        viewModel.nextPage()
                    } else {
                        statusLabel.text = "朗读完成"
                    }
                }
            }
            override fun onError(utteranceId: String, error: String?) {
                runOnUiThread {
                    statusLabel.text = "错误: $error"
                    isAutoPlay = false
                    pendingAutoPlay = false
                }
            }
        })
    }

    private fun setupAutoPlayObserver() {
        // 使用observeForever，确保后台也能触发翻页播放
        autoPlayObserver = Observer { content ->
            if (content != null && pendingAutoPlay) {
                tryStartAutoPlay()
            } else if (content != null && isAutoPlay && currentEngine?.isPlaying() == false) {
                playCurrentPage()
            }
        }
        viewModel.pageContent.observeForever(autoPlayObserver!!)
    }

    private fun manualNextPage() {
        if (isAutoPlay) {
            currentEngine?.stop()
            isAutoPlay = false
        }
        pendingAutoPlay = false
        viewModel.nextPage()
    }

    private fun manualPrevPage() {
        if (isAutoPlay) {
            currentEngine?.stop()
            isAutoPlay = false
        }
        pendingAutoPlay = false
        viewModel.previousPage()
    }

    // ---------- 状态切换 ----------
    private fun showLoadingState(message: String? = null) {
        loadingProgress.isVisible = true
        textContentTextView.isVisible = false
        errorTextView.isVisible = false
        chapterButton.isVisible = true
        settingsButton.isVisible = true
        progressTextView.isVisible = true
        statusLabel.isVisible = true
        statusLabel.text = message ?: "正在加载..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        errorTextView.isVisible = false
        chapterButton.isVisible = true
        settingsButton.isVisible = true
        progressTextView.isVisible = true
        statusLabel.isVisible = false
    }

    private fun showErrorState(message: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = message
        chapterButton.isVisible = true
        settingsButton.isVisible = true
        progressTextView.isVisible = true
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
                            viewModel.restoreFromHistory(it.blockPage, it.absoluteCharOffset)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TextPreview", "加载历史记录失败", e)
            }
        }
    }

    private fun saveReadingHistory(blockPage: Int, subPage: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val offset = viewModel.currentAbsoluteCharOffset.value ?: 0
                val history = ReadingHistory(
                    fileName = currentFileName,
                    fileUrl = currentFileUrl,
                    blockPage = blockPage,
                    absoluteCharOffset = offset,
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
            saveReadingHistory(it.blockPage, it.subPage)
        }
    }

    override fun onDestroy() {
        // 移除observeForever观察者，防止内存泄漏
        autoPlayObserver?.let { viewModel.pageContent.removeObserver(it) }
        currentEngine?.release()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.blockPage, it.subPage)
        }
    }
}

data class ReadingHistory(
    val fileName: String,
    val fileUrl: String,
    val blockPage: Int,
    val absoluteCharOffset: Int,
    val timestamp: Long
) : java.io.Serializable