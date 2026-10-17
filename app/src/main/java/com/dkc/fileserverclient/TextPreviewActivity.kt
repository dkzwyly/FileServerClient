package com.dkc.fileserverclient

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var statusInfoTextView: TextView

    private var audiobookService: AudiobookService? = null
    private var isBound = false
    private var isLayoutReady = false

    private lateinit var currentFileName: String
    private lateinit var currentFileUrl: String
    private lateinit var currentFilePath: String

    // ★ 两套独立配置
    private var dayFontSize: Float = 16f
    private var dayBgColor: Int = Color.WHITE
    private var nightFontSize: Float = 16f
    private var nightBgColor: Int = Color.parseColor("#1A1A1A")

    /** 系统当前是否处于深色模式 */
    private val isNightMode: Boolean
        get() {
            val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return nightMask == Configuration.UI_MODE_NIGHT_YES
        }

    /** 当前生效的字体大小（根据系统模式自动选） */
    private val currentFontSize: Float
        get() = if (isNightMode) nightFontSize else dayFontSize

    /** 当前生效的背景色 */
    private val currentBackgroundColor: Int
        get() = if (isNightMode) nightBgColor else dayBgColor

    private lateinit var prefs: SharedPreferences
    private lateinit var ttsPrefs: SharedPreferences

    private lateinit var gestureDetector: GestureDetector
    private var lastClickTime = 0L
    private val minClickInterval = 200L

    private lateinit var repository: PageRepository

    // ---- 状态栏（时间 + 电量）----
    private var batteryPercent = -1
    private var statusReceiverRegistered = false
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
            }
            updateStatusInfo()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            audiobookService = (service as AudiobookService.LocalBinder).getService()
            val svcFileUrl = audiobookService?.getFileUrl()
            if (svcFileUrl.isNullOrEmpty() || svcFileUrl != currentFileUrl) {
                repository.setupFile(currentFileName, currentFileUrl, currentFilePath)
                audiobookService?.setupFile(currentFileName, currentFileUrl, currentFilePath)
            }
            if (isLayoutReady) updateServiceDisplayParams()
            updatePlayStateUI()
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
                    if (state.isLoading) showLoadingState(state.message) else showContentState()
                }
            }
            launch {
                repository.chaptersLoadedEvent.collect {
                    val chapters = repository.chaptersFlow.value
                    statusLabel.isVisible = false
                    if (chapters.isNotEmpty()) {
                        showChapterList(chapters)
                    } else {
                        Toast.makeText(this@TextPreviewActivity, "暂无可用章节", Toast.LENGTH_SHORT).show()
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

        val serviceIntent = Intent(this, AudiobookService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true

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

    // ============================================================
    // ★ 系统深浅色切换：自动重新应用
    // ============================================================

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重新应用当前模式对应的字体/背景
        applyAppearance()
        // 字体大小可能变化，需要重新分页
        if (isLayoutReady) {
            textContentTextView.post { updateServiceDisplayParams() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentFileName = intent?.getStringExtra("FILE_NAME") ?: currentFileName
        currentFileUrl = intent?.getStringExtra("FILE_URL") ?: currentFileUrl
        currentFilePath = intent?.getStringExtra("FILE_PATH") ?: currentFilePath
        if (audiobookService?.getFileUrl() != currentFileUrl) {
            repository.setupFile(currentFileName, currentFileUrl, currentFilePath)
            audiobookService?.setupFile(currentFileName, currentFileUrl, currentFilePath)
        }
    }

    override fun onStart() {
        super.onStart()
        registerStatusReceiver()
    }

    override fun onResume() {
        super.onResume()
        updatePlayStateUI()
    }

    override fun onStop() {
        unregisterStatusReceiver()
        super.onStop()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    // ---------------- 时间 + 电量 ----------------

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        statusReceiverRegistered = true
        updateStatusInfo()
    }

    private fun unregisterStatusReceiver() {
        if (!statusReceiverRegistered) return
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        statusReceiverRegistered = false
    }

    private fun updateStatusInfo() {
        if (!::statusInfoTextView.isInitialized) return
        val time = timeFormat.format(Date())
        val battery = if (batteryPercent in 0..100) "$batteryPercent%" else "--%"
        statusInfoTextView.text = "$time  $battery"
    }

    // ---------------- 分页与显示参数 ----------------

    private fun updateServiceDisplayParams() {
        val width = textContentTextView.width - textContentTextView.paddingLeft - textContentTextView.paddingRight
        val lines = calculateLinesPerPage()
        repository.updateDisplayParams(
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
        var low = 1; var high = maxPossible; var best = 1
        while (low <= high) {
            val mid = (low + high) / 2
            val text = testLines.lines().take(mid).joinToString("\n")
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
                .setIncludePad(true)
                .build()
            if (layout.height <= maxHeight) { best = mid; low = mid + 1 } else { high = mid - 1 }
        }
        return best.coerceAtLeast(2)
    }

    private fun updateProgressBar(state: PageRepository.PageState) {
        val progress = ((state.blockPage - 1).toFloat() + (state.subPage - 1).toFloat() / state.totalSubPages) / state.totalBlockPages * 100f
        progressTextView.text = String.format("%.2f%%", progress)
    }

    // ---------------- 视图初始化 ----------------

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
        statusInfoTextView = findViewById(R.id.statusInfoTextView)
        supportActionBar?.hide()
        chapterButton.setOnClickListener { showChapterDialog() }
        settingsButton.setOnClickListener { showAppearanceDialog() }
        voiceMenuButton.setOnClickListener { showVoiceMenu() }
        updateStatusInfo()
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
        if (audiobookService?.isAutoPlaying() == true) {
            audiobookService?.stopAutoPlay()
            updatePlayStateUI()
        }
        lifecycleScope.launch { repository.nextPage() }
    }

    private fun manualPrevPage() {
        if (audiobookService?.isAutoPlaying() == true) {
            audiobookService?.stopAutoPlay()
            updatePlayStateUI()
        }
        lifecycleScope.launch { repository.previousPage() }
    }

    private fun updatePlayStateUI() {
        val isPlaying = audiobookService?.isAutoPlaying() ?: false
        voiceMenuButton.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    // ---------------- 菜单与对话框 ----------------

    private fun showVoiceMenu() {
        PopupMenu(this, voiceMenuButton).apply {
            menu.add("开始自动朗读")
            menu.add("停止朗读")
            menu.add("选择TTS引擎")
            setOnMenuItemClickListener {
                when (it.title) {
                    "开始自动朗读" -> {
                        audiobookService?.startAutoPlay()
                        updatePlayStateUI()
                    }
                    "停止朗读" -> {
                        audiobookService?.stopAutoPlay()
                        updatePlayStateUI()
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

    // ---------------- 外观设置对话框 ----------------

    /**
     * 修改的是「当前系统模式对应的那一套」：
     * - 系统深色 → 编辑 nightFontSize / nightBgColor
     * - 系统浅色 → 编辑 dayFontSize / dayBgColor
     * 打开对话框时锁定模式（防止编辑期间系统模式变化导致语义混乱）。
     */
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

        // ★ 打开时锁定本次编辑目标是白天还是黑夜
        val editingNight = isNightMode

        // 快照
        val origDayFont = dayFontSize
        val origDayBg = dayBgColor
        val origNightFont = nightFontSize
        val origNightBg = nightBgColor
        val origSpeed = prefs.getFloat("tts_speed", 1.0f)

        // 当前正在编辑的字体 / 背景
        var editingFont = if (editingNight) nightFontSize else dayFontSize
        var editingBg = if (editingNight) nightBgColor else dayBgColor

        // 把编辑值立即写回内存字段，让预览生效
        fun pushFont(f: Float) {
            if (editingNight) nightFontSize = f else dayFontSize = f
            textContentTextView.textSize = f
        }
        fun pushBg(c: Int) {
            if (editingNight) nightBgColor = c else dayBgColor = c
            rootLayout.setBackgroundColor(c)
            progressTextView.setTextColor(getContrastColor(c))
            statusInfoTextView.setTextColor(getContrastColor(c))
        }

        // 初始化控件
        seekFont.max = 18
        seekFont.progress = (editingFont - 12).toInt().coerceIn(0, 18)
        fontSizeVal.text = "${editingFont.toInt()}sp"
        textContentTextView.textSize = editingFont

        seekSpeed.progress = ((origSpeed - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
        speedVal.text = String.format("%.1fx", origSpeed)

        seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                editingFont = (p + 12).toFloat()
                fontSizeVal.text = "${p + 12}sp"
                pushFont(editingFont)
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

        btnW.setOnClickListener { editingBg = Color.WHITE; pushBg(editingBg) }
        btnC.setOnClickListener { editingBg = Color.parseColor("#FAF0D7"); pushBg(editingBg) }
        btnG.setOnClickListener { editingBg = Color.parseColor("#C8E6C9"); pushBg(editingBg) }
        btnB.setOnClickListener { editingBg = Color.DKGRAY; pushBg(editingBg) }

        val dialog = AlertDialog.Builder(this).setView(dialogView).setCancelable(false).create()

        cancelBtn.setOnClickListener {
            // 回滚
            dayFontSize = origDayFont
            dayBgColor = origDayBg
            nightFontSize = origNightFont
            nightBgColor = origNightBg
            audiobookService?.setSpeechRate(origSpeed)
            applyAppearance()
            updateServiceDisplayParams()
            dialog.dismiss()
        }

        applyBtn.setOnClickListener {
            val speed = 0.5f + seekSpeed.progress / 100f * 1.5f
            prefs.edit().putFloat("tts_speed", speed).apply()
            audiobookService?.setSpeechRate(speed)

            saveAppearancePrefs()
            applyAppearance()
            textContentTextView.post { updateServiceDisplayParams() }
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---------------- 外观持久化 ----------------

    /**
     * 只加载两套配置，不再保存 / 读取"当前模式"。
     * 兼容旧版本：如果两套 key 都没建立，用旧的 font_size / bg_color 作初值。
     */
    private fun loadAppearancePrefs() {
        val legacyFont = prefs.getFloat("font_size", 16f)
        val legacyBg = prefs.getInt("bg_color", Color.WHITE)

        dayFontSize = prefs.getFloat("day_font_size", legacyFont)
        dayBgColor = prefs.getInt("day_bg_color", legacyBg)
        nightFontSize = prefs.getFloat("night_font_size", legacyFont)
        nightBgColor = prefs.getInt("night_bg_color", Color.parseColor("#1A1A1A"))
    }

    private fun saveAppearancePrefs() {
        prefs.edit()
            .putFloat("day_font_size", dayFontSize)
            .putInt("day_bg_color", dayBgColor)
            .putFloat("night_font_size", nightFontSize)
            .putInt("night_bg_color", nightBgColor)
            .apply()
    }

    private fun applyAppearance() {
        textContentTextView.textSize = currentFontSize
        rootLayout.setBackgroundColor(currentBackgroundColor)
        progressTextView.setTextColor(getContrastColor(currentBackgroundColor))
        statusInfoTextView.setTextColor(getContrastColor(currentBackgroundColor))
    }

    private fun getContrastColor(bg: Int): Int {
        return if ((Color.red(bg) * 0.299 + Color.green(bg) * 0.587 + Color.blue(bg) * 0.114) > 127.5) Color.DKGRAY
        else Color.argb(0x80, 0xFF, 0xFF, 0xFF)
    }

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ---------------- 状态显示 ----------------

    private fun showLoadingState(msg: String?) {
        loadingProgress.isVisible = true; textContentTextView.isVisible = false; errorTextView.isVisible = false
        statusLabel.isVisible = true; statusLabel.text = msg ?: "加载中..."
    }

    private fun showContentState() {
        loadingProgress.isVisible = false; textContentTextView.isVisible = true; errorTextView.isVisible = false
        statusLabel.isVisible = false
    }

    private fun showErrorState(msg: String) {
        loadingProgress.isVisible = false; textContentTextView.isVisible = false; errorTextView.isVisible = true
        errorTextView.text = msg; statusLabel.isVisible = false
    }
}