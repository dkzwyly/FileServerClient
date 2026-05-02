package com.dkc.fileserverclient

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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

    // 字体与背景偏好
    private var currentFontSize: Float = 16f
    private var currentBackgroundColor: Int = Color.WHITE
    private lateinit var prefs: SharedPreferences

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

        supportActionBar?.hide()
        textContentTextView.isScrollContainer = false

        // 章节按钮透明化
        chapterButton.setBackgroundResource(android.R.color.transparent)
        chapterButton.setImageResource(android.R.color.transparent)
        chapterButton.alpha = 0.0f
        chapterButton.bringToFront()
        chapterButton.setOnClickListener { showChapterDialog() }
        chapterButton.isVisible = true

        // 设置按钮透明化（与章节按钮一致）
        settingsButton.setBackgroundResource(android.R.color.transparent)
        settingsButton.setImageResource(android.R.color.transparent)
        settingsButton.alpha = 0.0f
        settingsButton.bringToFront()
        settingsButton.setOnClickListener { showAppearanceDialog() }
        settingsButton.isVisible = true

        progressTextView.bringToFront()
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
        viewModel.chapters.observe(this) { chapters ->
            if (chapters.isNotEmpty()) showChapterList(chapters)
            else showNoChaptersDialog()
        }
        viewModel.currentPageState.observe(this) { state ->
            state?.let { saveReadingHistory(it.blockPage, it.subPage) }
        }
        viewModel.pageInfo.observe(this) { info ->
            progressTextView.text = "${info.progress}%"
        }
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

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < minClickInterval) return true
                lastClickTime = currentTime

                val screenWidth = resources.displayMetrics.widthPixels
                val x = e.x

                // 右上角章节按钮
                if (x > screenWidth - 150 && e.y < 150) {
                    chapterButton.performClick()
                    return true
                }
                // 右下角设置按钮
                if (x > screenWidth - 150 && e.y > resources.displayMetrics.heightPixels - 150) {
                    settingsButton.performClick()
                    return true
                }

                if (x < screenWidth / 3) viewModel.previousPage()
                else if (x > screenWidth * 2 / 3) viewModel.nextPage()
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

        // 记录原始值，用于取消时恢复
        val originalFontSize = currentFontSize
        val originalBgColor = currentBackgroundColor

        seekBarFontSize.max = 30 - 12
        seekBarFontSize.progress = (currentFontSize - 12).toInt()
        fontSizeValue.text = "${currentFontSize.toInt()}sp"

        seekBarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = (progress + 12).toFloat()
                textContentTextView.textSize = size
                fontSizeValue.text = "${size.toInt()}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 背景色按钮点击立即预览
        btnColorWhite.setOnClickListener {
            currentBackgroundColor = Color.WHITE
            rootLayout.setBackgroundColor(Color.WHITE)
        }
        btnColorCream.setOnClickListener {
            currentBackgroundColor = Color.parseColor("#FAF0D7")
            rootLayout.setBackgroundColor(currentBackgroundColor)
        }
        btnColorGreen.setOnClickListener {
            currentBackgroundColor = Color.parseColor("#C8E6C9")
            rootLayout.setBackgroundColor(currentBackgroundColor)
        }
        btnColorBlack.setOnClickListener {
            currentBackgroundColor = Color.DKGRAY
            rootLayout.setBackgroundColor(currentBackgroundColor)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            // 恢复原始值
            currentFontSize = originalFontSize
            currentBackgroundColor = originalBgColor
            applyAppearance()
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            currentFontSize = (seekBarFontSize.progress + 12).toFloat()
            saveAppearancePrefs()
            applyAppearance()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ---------- 原有方法保持不变 ----------
    private fun showChapterDialog() {
        statusLabel.isVisible = true
        statusLabel.text = "正在从服务器加载章节..."
        viewModel.loadChapters()
    }

    private fun showChapterList(chapters: List<TextPreviewViewModel.ChapterInfo>) {
        val titles = chapters.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("章节跳转 (${chapters.size}章)")
            .setItems(titles) { _, which ->
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

    private fun loadReadingHistory() {
        if (readingHistoryFile.exists()) {
            try {
                ObjectInputStream(FileInputStream(readingHistoryFile)).use { ois ->
                    val history = ois.readObject() as? ReadingHistory
                    history?.let {
                        if (it.fileName == currentFileName || it.fileUrl == currentFileUrl) {
                            viewModel.restoreFromHistory(it.blockPage, it.subPage)
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
                val history = ReadingHistory(
                    fileName = currentFileName,
                    fileUrl = currentFileUrl,
                    blockPage = blockPage,
                    subPage = subPage,
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
        super.onDestroy()
        viewModel.getCurrentPageState()?.let {
            saveReadingHistory(it.blockPage, it.subPage)
        }
    }
    data class ReadingHistory(
        val fileName: String,
        val fileUrl: String,
        val blockPage: Int,
        val subPage: Int,
        val timestamp: Long
    ) : java.io.Serializable
}