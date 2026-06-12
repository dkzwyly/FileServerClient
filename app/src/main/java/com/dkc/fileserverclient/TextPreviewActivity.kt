package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TextPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TextPreviewActivity"
    }

    private lateinit var textContentTextView: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorTextView: TextView
    private lateinit var rootLayout: RelativeLayout
    private lateinit var chapterButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var progressTextView: TextView
    private lateinit var statusLabel: TextView
    private lateinit var voiceMenuButton: ImageButton

    private var audiobookService: AudiobookService? = null
    private var isBound = false
    private var isLayoutReady = false
    private var isAutoPlay = false

    private lateinit var currentFileName: String
    private lateinit var currentFileUrl: String
    private lateinit var currentFilePath: String

    private var currentFontSize: Float = 16f
    private var currentBackgroundColor: Int = Color.WHITE
    private lateinit var prefs: SharedPreferences
    private lateinit var ttsPrefs: SharedPreferences

    private lateinit var gestureDetector: GestureDetector
    private var lastClickTime = 0L
    private val minClickInterval = 200L

    private lateinit var repository: PageRepository

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            audiobookService = (service as AudiobookService.LocalBinder).getService()
            // 同步文件信息到服务，确保通知携带正确 URL
            audiobookService?.setupFile(currentFileName, currentFileUrl, currentFilePath)
            if (isLayoutReady) updateServiceDisplayParams()
            if (isAutoPlay) audiobookService?.startAutoPlay()
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
        initViews()
        setupIntentData()
        setupGestureDetector()
        applyAppearance()

        repository = PageRepository.getInstance(applicationContext)

        // 收集状态流
        lifecycleScope.launch {
            launch {
                repository.pageContentFlow.collect { uiData ->
                    uiData?.let {
                        textContentTextView.text = it.content
                        updateProgressBar(it.state)
                    }
                }
            }
            launch {
                repository.loadingStateFlow.collect { state ->
                    if (state.isLoading) showLoadingState(state.message)
                    else showContentState()
                }
            }
            launch {
                repository.chaptersLoadedEvent.collect {
                    val chapters = repository.chaptersFlow.value
                    if (chapters.isNotEmpty()) {
                        showChapterList(chapters)
                        statusLabel.isVisible = false
                    }
                }
            }
            launch {
                repository.errorEvents.collect { msg ->
                    Toast.makeText(this@TextPreviewActivity, msg, Toast.LENGTH_SHORT).show()
                    showErrorState(msg)
                }
            }
        }

        // 初始化文件
        repository.setupFile(currentFileName, currentFileUrl, currentFilePath)

        // 绑定 Service
        val serviceIntent = Intent(this, AudiobookService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true

        // 监听布局完成以传递显示参数
        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isLayoutReady && textContentTextView.height > 0) {
                    rootLayout.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    isLayoutReady = true
                    updateServiceDisplayParams()
                }
            }
        })
    }

    // ★ 关键修复：从通知返回时正确处理 Intent
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 重新提取文件信息（使用最新数据）
        currentFileName = intent?.getStringExtra("FILE_NAME") ?: currentFileName
        currentFileUrl = intent?.getStringExtra("FILE_URL") ?: currentFileUrl
        currentFilePath = intent?.getStringExtra("FILE_PATH") ?: currentFilePath
        // 重置数据源
        repository.setupFile(currentFileName, currentFileUrl, currentFilePath)
        // 同步到服务，确保通知正确
        audiobookService?.setupFile(currentFileName, currentFileUrl, currentFilePath)
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun updateServiceDisplayParams() {
        val width = textContentTextView.width - textContentTextView.paddingLeft - textContentTextView.paddingRight
        val lines = calculateLinesPerPage()
        repository.updateDisplayParams(
            width, textContentTextView.paint,
            textContentTextView.lineSpacingExtra, textContentTextView.lineSpacingMultiplier, lines
        )
        audiobookService?.updateDisplayParams(
            width, textContentTextView.paint,
            textContentTextView.lineSpacingExtra, textContentTextView.lineSpacingMultiplier, lines
        )
    }

    private fun calculateLinesPerPage(): Int {
        val tv = textContentTextView
        val maxHeight = tv.height - tv.paddingTop - tv.paddingBottom
        if (maxHeight <= 0) return 20
        val width = tv.width - tv.paddingLeft - tv.paddingRight
        val paint = tv.paint
        val sample = android.text.StaticLayout.Builder.obtain("T\n", 0, 2, paint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
            .setIncludePad(true)
            .build()
        val lineHeight = sample.getLineBottom(0) - sample.getLineTop(0)
        if (lineHeight <= 0) return 20
        val maxPossible = (maxHeight / lineHeight).toInt() + 2
        val testLines = List(maxPossible) { "T" }.joinToString("\n")
        var low = 1
        var high = maxPossible
        var best = 1
        while (low <= high) {
            val mid = (low + high) / 2
            val text = testLines.lines().take(mid).joinToString("\n")
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
                .setIncludePad(true)
                .build()
            if (layout.height <= maxHeight) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best.coerceAtLeast(2)
    }

    private fun updateProgressBar(state: PageRepository.PageState) {
        val progress = ((state.blockPage - 1).toFloat() + (state.subPage - 1).toFloat() / state.totalSubPages) / state.totalBlockPages * 100f
        progressTextView.text = String.format("%.2f%%", progress)
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
        chapterButton.setOnClickListener { showChapterDialog() }
        settingsButton.setOnClickListener { showAppearanceDialog() }
        voiceMenuButton.setOnClickListener { showVoiceMenu() }
    }

    private fun setupIntentData() {
        currentFileName = intent.getStringExtra("FILE_NAME") ?: "未知文件"
        currentFileUrl = intent.getStringExtra("FILE_URL") ?: ""
        currentFilePath = intent.getStringExtra("FILE_PATH") ?: ""
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (System.currentTimeMillis() - lastClickTime < minClickInterval) return true
                lastClickTime = System.currentTimeMillis()
                val x = e.x
                val sw = resources.displayMetrics.widthPixels
                if (x > sw - 150 && e.y < 150) chapterButton.performClick()
                else if (x > sw - 150 && e.y > resources.displayMetrics.heightPixels - 150) settingsButton.performClick()
                else if (x < sw / 3) manualPrevPage()
                else if (x > sw * 2 / 3) manualNextPage()
                return true
            }
        })
        rootLayout.setOnTouchListener { _, ev -> gestureDetector.onTouchEvent(ev); true }
    }

    private fun manualNextPage() {
        if (isAutoPlay) {
            isAutoPlay = false
            audiobookService?.stopAutoPlay()
        }
        lifecycleScope.launch { repository.nextPage() }
    }

    private fun manualPrevPage() {
        if (isAutoPlay) {
            isAutoPlay = false
            audiobookService?.stopAutoPlay()
        }
        lifecycleScope.launch { repository.previousPage() }
    }

    private fun showVoiceMenu() {
        PopupMenu(this, voiceMenuButton).apply {
            menu.add("开始自动朗读")
            menu.add("停止朗读")
            menu.add("选择TTS引擎")
            setOnMenuItemClickListener {
                when (it.title) {
                    "开始自动朗读" -> {
                        isAutoPlay = true
                        audiobookService?.startAutoPlay()
                    }
                    "停止朗读" -> {
                        isAutoPlay = false
                        audiobookService?.stopAutoPlay()
                    }
                    "选择TTS引擎" -> showEngineSelectionDialog()
                }
                true
            }
            show()
        }
    }

    private fun showEngineSelectionDialog() {
        val tts = android.speech.tts.TextToSpeech(this, null)
        val engines = tts.engines.toList()
        tts.shutdown()
        if (engines.isEmpty()) {
            Toast.makeText(this, "没有可用引擎", Toast.LENGTH_SHORT).show()
            return
        }
        val names = engines.map { "${it.label} (${it.name})" }.toTypedArray()
        val checked = engines.indexOfFirst { it.name == ttsPrefs.getString(AudiobookService.PREF_TTS_ENGINE, null) }
        AlertDialog.Builder(this).setTitle("选择引擎")
            .setSingleChoiceItems(names, if (checked >= 0) checked else -1) { d, i ->
                ttsPrefs.edit().putString(AudiobookService.PREF_TTS_ENGINE, engines[i].name).apply()
                audiobookService?.reinitializeTts(engines[i].name)
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showChapterDialog() {
        statusLabel.isVisible = true
        statusLabel.text = "正在加载章节..."
        repository.loadChapters()
    }

    private fun showChapterList(chapters: List<PageRepository.ChapterInfo>) {
        val sorted = chapters.sortedBy { it.startCharOffset }
        val offset = repository.getCurrentPageState()?.absoluteCharOffset ?: 0
        val idx = sorted.indexOfLast { it.startCharOffset <= offset }
        val view = layoutInflater.inflate(R.layout.dialog_chapter_list, null)
        val listView = view.findViewById<ListView>(R.id.chapterListView)
        listView.adapter = object : ArrayAdapter<PageRepository.ChapterInfo>(this, android.R.layout.simple_list_item_1, sorted) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val tv = super.getView(pos, cv, parent) as TextView
                tv.text = getItem(pos)?.title ?: ""
                tv.setBackgroundColor(if (pos == idx) Color.parseColor("#E8F0FE") else Color.TRANSPARENT)
                tv.setTextColor(if (pos == idx) Color.parseColor("#1A73E8") else Color.BLACK)
                return tv
            }
        }
        AlertDialog.Builder(this).setView(view).setNegativeButton("取消", null).create().also {
            listView.setOnItemClickListener { _, _, p, _ ->
                lifecycleScope.launch { repository.jumpToChapter(sorted[p]) }
                it.dismiss()
            }
            it.show()
            if (idx >= 0) listView.setSelection(idx)
        }
    }

    private fun showAppearanceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reading_settings, null)
        val seekFont = dialogView.findViewById<SeekBar>(R.id.seekBarFontSize)
        val fontSizeVal = dialogView.findViewById<TextView>(R.id.fontSizeValue)
        val btnW = dialogView.findViewById<Button>(R.id.btnColorWhite)
        val btnC = dialogView.findViewById<Button>(R.id.btnColorCream)
        val btnG = dialogView.findViewById<Button>(R.id.btnColorGreen)
        val btnB = dialogView.findViewById<Button>(R.id.btnColorBlack)
        val cancelBtn = dialogView.findViewById<Button>(R.id.btnCancel)
        val applyBtn = dialogView.findViewById<Button>(R.id.btnApply)
        val seekSpeed = dialogView.findViewById<SeekBar>(R.id.seekBarSpeed)
        val speedVal = dialogView.findViewById<TextView>(R.id.speedValue)

        val origFont = currentFontSize
        val origBg = currentBackgroundColor
        val origSpeed = prefs.getFloat("tts_speed", 1.0f)

        seekFont.max = 18
        seekFont.progress = (origFont - 12).toInt()
        fontSizeVal.text = "${origFont.toInt()}sp"
        seekSpeed.progress = ((origSpeed - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
        speedVal.text = String.format("%.1fx", origSpeed)

        seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                fontSizeVal.text = "${p + 12}sp"
                textContentTextView.textSize = (p + 12).toFloat()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val speed = 0.5f + p / 100f * 1.5f
                speedVal.text = String.format("%.1fx", speed)
                audiobookService?.setSpeechRate(speed)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnW.setOnClickListener { currentBackgroundColor = Color.WHITE; rootLayout.setBackgroundColor(Color.WHITE) }
        btnC.setOnClickListener {
            currentBackgroundColor = Color.parseColor("#FAF0D7")
            rootLayout.setBackgroundColor(currentBackgroundColor)
        }
        btnG.setOnClickListener {
            currentBackgroundColor = Color.parseColor("#C8E6C9")
            rootLayout.setBackgroundColor(currentBackgroundColor)
        }
        btnB.setOnClickListener { currentBackgroundColor = Color.DKGRAY; rootLayout.setBackgroundColor(currentBackgroundColor) }

        val dialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()
        cancelBtn.setOnClickListener {
            currentFontSize = origFont
            currentBackgroundColor = origBg
            audiobookService?.setSpeechRate(origSpeed)
            applyAppearance()
            updateServiceDisplayParams()
            dialog.dismiss()
        }
        applyBtn.setOnClickListener {
            currentFontSize = (seekFont.progress + 12).toFloat()
            val speed = 0.5f + seekSpeed.progress / 100f * 1.5f
            saveAppearancePrefs()
            prefs.edit().putFloat("tts_speed", speed).apply()
            audiobookService?.setSpeechRate(speed)
            applyAppearance()
            textContentTextView.post { updateServiceDisplayParams() }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun loadAppearancePrefs() {
        currentFontSize = prefs.getFloat("font_size", 16f)
        currentBackgroundColor = prefs.getInt("bg_color", Color.WHITE)
    }

    private fun saveAppearancePrefs() {
        prefs.edit().putFloat("font_size", currentFontSize).putInt("bg_color", currentBackgroundColor).apply()
    }

    private fun applyAppearance() {
        textContentTextView.textSize = currentFontSize
        rootLayout.setBackgroundColor(currentBackgroundColor)
        progressTextView.setTextColor(getContrastColor(currentBackgroundColor))
    }

    private fun getContrastColor(bg: Int): Int {
        return if ((Color.red(bg) * 0.299 + Color.green(bg) * 0.587 + Color.blue(bg) * 0.114) > 127.5) Color.DKGRAY
        else Color.argb(0x80, 0xFF, 0xFF, 0xFF)
    }

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun showLoadingState(msg: String?) {
        loadingProgress.isVisible = true
        textContentTextView.isVisible = false
        errorTextView.isVisible = false
        statusLabel.isVisible = true
        statusLabel.text = msg ?: "加载中..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = true
        errorTextView.isVisible = false
        statusLabel.isVisible = false
    }

    private fun showErrorState(msg: String) {
        loadingProgress.isVisible = false
        textContentTextView.isVisible = false
        errorTextView.isVisible = true
        errorTextView.text = msg
        statusLabel.isVisible = false
    }
}