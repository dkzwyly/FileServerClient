package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
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

    // 听书相关
    private lateinit var voiceMenuButton: ImageButton
    private var currentEngine: VoiceEngine? = null
    private var isAutoPlay = false
    private var audiobookService: AudiobookService? = null
    private var isBound = false
    private var selectedEngineType: EngineType? = null

    private var pendingAutoPlay = false
    private var lastUtteranceId = ""

    // 后台自动播放观察者
    private var autoPlayObserver: Observer<CharSequence>? = null

    // TTS 引擎选择
    private lateinit var ttsPrefs: SharedPreferences
    private var selectedEnginePackage: String? = null

    private enum class EngineType { LOCAL, CLOUD }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            audiobookService = (service as AudiobookService.LocalBinder).getService()
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
        ttsPrefs = getSharedPreferences("tts_prefs", MODE_PRIVATE)
        loadAppearancePrefs()
        selectedEnginePackage = ttsPrefs.getString(AudiobookService.PREF_TTS_ENGINE, null)

        initViews()
        setupIntentData()
        initViewModel()
        setupGestureDetector()
        setupObservers()
        setupLayoutListener()
        applyAppearance()

        textContentTextView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (textContentTextView.height > 0) recalcLinesPerPage()
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
                val progress = ((state.blockPage - 1).toFloat() + (state.subPage - 1).toFloat() / state.totalSubPages) /
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

        val seekBarSpeed = dialogView.findViewById<SeekBar>(R.id.seekBarSpeed)
        val speedValue = dialogView.findViewById<TextView>(R.id.speedValue)

        val originalFontSize = currentFontSize
        val originalBgColor = currentBackgroundColor
        val originalSpeed = prefs.getFloat("tts_speed", 1.0f)

        seekBarFontSize.max = 30 - 12
        seekBarFontSize.progress = (currentFontSize - 12).toInt()
        fontSizeValue.text = "${currentFontSize.toInt()}sp"

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

    // ========== 章节相关（完整实现） ==========
    private fun showChapterDialog() {
        statusLabel.isVisible = true
        statusLabel.text = "正在从服务器加载章节..."
        viewModel.loadChapters()
    }

    private fun showChapterList(chapters: List<TextPreviewViewModel.ChapterInfo>) {
        // 按字符偏移排序，确保顺序正确
        val sortedChapters = chapters.sortedBy { it.startCharOffset }
        // 根据当前绝对字符偏移找到所在章节索引
        val currentChapterIndex = if (sortedChapters.isNotEmpty()) {
            sortedChapters.indexOfLast { it.startCharOffset <= currentAbsoluteOffset }
                .takeIf { it != -1 } ?: -1
        } else -1

        // 调试日志：检查偏移量和章节索引
        Log.d("Chapter", "currentAbsoluteOffset=$currentAbsoluteOffset, currentChapterIndex=$currentChapterIndex")

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
        statusLabel.isVisible = false

        // 等待 ListView 布局完成后滚动到当前章节位置并高亮
        listView.post {
            if (currentChapterIndex >= 0) {
                listView.setItemChecked(currentChapterIndex, true)
                listView.setSelection(currentChapterIndex)
            }
        }
    }

    private fun showNoChaptersDialog() {
        AlertDialog.Builder(this)
            .setTitle("未发现章节")
            .setMessage("该文件可能没有章节标记，或者章节索引尚未构建。")
            .setPositiveButton("确定", null)
            .show()
        statusLabel.isVisible = false
    }

    // ========== 听书功能（保持不变） ==========
    private fun showVoiceEngineMenu() {
        PopupMenu(this, voiceMenuButton).apply {
            menu.add("本地 TTS")
            menu.add("云端语音服务")
            menu.add("选择TTS引擎")
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
                    "选择TTS引擎" -> showEngineSelectionDialog()
                }
                true
            }
            show()
        }
    }

    private fun showEngineSelectionDialog() {
        val tempTts = TextToSpeech(this, null)
        val engines = tempTts.engines.toList()
        tempTts.shutdown()

        if (engines.isEmpty()) {
            Toast.makeText(this, "没有可用的TTS引擎", Toast.LENGTH_SHORT).show()
            return
        }

        val engineNames = engines.map { "${it.label} (${it.name})" }.toTypedArray()
        val checkedItem = engines.indexOfFirst { it.name == selectedEnginePackage }

        AlertDialog.Builder(this)
            .setTitle("选择语音引擎")
            .setSingleChoiceItems(engineNames, if (checkedItem >= 0) checkedItem else -1) { dialog, which ->
                val chosenEngine = engines[which]
                selectedEnginePackage = chosenEngine.name
                ttsPrefs.edit().putString(AudiobookService.PREF_TTS_ENGINE, chosenEngine.name).apply()
                if (isBound && audiobookService != null) {
                    audiobookService?.reinitializeTts(chosenEngine.name)
                    audiobookService?.onTtsReadyListener = {
                        if (selectedEngineType == EngineType.LOCAL) tryStartAutoPlay()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ensureLocalEngineReady() {
        if (currentEngine == null && audiobookService != null) {
            switchToLocalTts()
        }
    }

    private fun switchToLocalTts() {
        currentEngine?.release()
        audiobookService?.let { service ->
            if (!service.isReady()) {
                service.reinitializeTts(selectedEnginePackage)
                service.onTtsReadyListener = { tryStartAutoPlay() }
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
                runOnUiThread {
                    statusLabel.text = "正在朗读"
                    if (isAutoPlay) preloadNextPageToQueue()
                }
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

    private fun preloadNextPageToQueue() {
        viewModel.peekNextPageContent { nextContent ->
            if (nextContent != null && isAutoPlay) {
                val nextId = "page_preload_${System.currentTimeMillis()}"
                (currentEngine as? LocalTtsEngine)?.appendPlay(nextContent.toString(), nextId)
            }
        }
    }

    private fun setupAutoPlayObserver() {
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

    // 状态切换
    private fun showLoadingState(message: String? = null) {
        loadingProgress.isVisible = true
        textContentTextView.isVisible = false
        errorTextView.isVisible = false
        statusLabel.isVisible = true
        statusLabel.text = message ?: "正在加载..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        errorTextView.isVisible = false
        statusLabel.isVisible = false
    }

    private fun showErrorState(message: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = message
        statusLabel.isVisible = false
    }

    // 历史记录
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