package com.dkc.fileserverclient

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

class AudioPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioPlayerActivity"
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 100
    }

    private val viewModel: AudioPlayerViewModel by viewModels()

    // UI
    private lateinit var titleBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var fileNameTextView: TextView
    private lateinit var fileTypeTextView: TextView
    private lateinit var downloadButton: Button

    private lateinit var audioCoverView: ImageView
    private lateinit var musicVisualizerView: MusicVisualizerView
    private lateinit var mediaLoadingProgress: ProgressBar
    private lateinit var mediaControls: LinearLayout
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var fullscreenToggleButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView

    private lateinit var lyricsContainer: LinearLayout
    private lateinit var lyricsTitle: TextView
    private lateinit var currentLyricsLine: TextView
    private lateinit var nextLyricsLine: TextView
    private lateinit var lyricsSettingsButton: Button

    // 手势
    private lateinit var gestureDetector: GestureDetector
    private lateinit var gestureControlManager: GestureControlManager
    private lateinit var controlContainer: LinearLayout
    private lateinit var controlIcon: ImageView
    private lateinit var controlOverlay: TextView

    // 全屏
    private lateinit var fullscreenManager: FullscreenManager

    private var isLongPressDetected = false
    private var originalSpeed: Float = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_player)
        checkAndRequestRecordAudioPermission()
        viewModel.init(intent)   // 确保歌词等组件初始化完成
        initViews()
        setupFullscreen()
        setupGestureDetector()
        setupClickListeners()
        observeViewModel()
    }

    private fun initViews() {
        titleBar = findViewById(R.id.titleBar)
        backButton = findViewById(R.id.backButton)
        fileNameTextView = findViewById(R.id.fileNameTextView)
        fileTypeTextView = findViewById(R.id.fileTypeTextView)
        downloadButton = findViewById(R.id.downloadButton)

        audioCoverView = findViewById(R.id.audioCoverView)
        musicVisualizerView = findViewById(R.id.musicVisualizerView)
        mediaLoadingProgress = findViewById(R.id.mediaLoadingProgress)
        mediaControls = findViewById(R.id.mediaControls)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        fullscreenToggleButton = findViewById(R.id.fullscreenToggleButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)

        lyricsContainer = findViewById(R.id.lyricsContainer)
        lyricsTitle = findViewById(R.id.lyricsTitle)
        currentLyricsLine = findViewById(R.id.currentLyricsLine)
        nextLyricsLine = findViewById(R.id.nextLyricsLine)
        lyricsSettingsButton = findViewById(R.id.lyricsSettingsButton)

        // 不再调用 viewModel.lyricsManager.setListener(this)
        seekBar.max = 1000
        fileTypeTextView.text = "音频"
    }

    private fun setupFullscreen() {
        fullscreenManager = FullscreenManager(
            activity = this,
            titleBar = titleBar,
            fileTypeTextView = fileTypeTextView,
            fullscreenToggleButton = fullscreenToggleButton
        )
    }

    private fun setupGestureDetector() {
        controlOverlay = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        controlIcon = ImageView(this)
        controlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(controlIcon, LinearLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.CENTER
                setMargins(0, 0, 0, 8)
            })
            addView(controlOverlay, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER })
        }
        findViewById<FrameLayout>(R.id.mediaContainer)?.addView(controlContainer)

        val displayWidth = resources.displayMetrics.widthPixels
        val regionWidth = displayWidth / 3
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        gestureControlManager = GestureControlManager(
            activity = this,
            handler = Handler(Looper.getMainLooper()),
            audioManager = audioManager,
            controlOverlay = controlOverlay,
            controlIcon = controlIcon,
            controlContainer = controlContainer,
            regionWidth = regionWidth
        )

        gestureControlManager.setGestureListener(object : GestureControlManager.GestureListener {
            override fun onProgressControl(deltaX: Float, displayWidth: Int) {
                val duration = viewModel.duration.value ?: 0L
                if (duration > 0) {
                    val deltaProgress = (deltaX / displayWidth) * duration * 0.5f
                    val currentPos = viewModel.currentPosition.value ?: 0L
                    val newPosition = (currentPos + deltaProgress.toLong()).coerceIn(0, duration)
                    viewModel.seekTo(newPosition)
                    gestureControlManager.showControlOverlay(
                        "进度: ${formatTime(newPosition)} / ${formatTime(duration)}",
                        android.R.drawable.ic_media_play
                    )
                }
            }

            override fun onControlOverlayShow(text: String, iconRes: Int) {
                controlContainer.bringToFront()
            }

            override fun onSeekBarProgressUpdate(position: Long, duration: Long) {
                seekBar.progress = (position * 1000 / duration).toInt()
                currentTimeTextView.text = formatTime(position)
                durationTextView.text = formatTime(duration)
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControlsVisibility()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                viewModel.togglePlayback()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                isLongPressDetected = true
                originalSpeed = viewModel.playbackSpeed.value ?: 1.0f
                if ((viewModel.playbackSpeed.value ?: 1.0f) < 2.0f) {
                    viewModel.setPlaybackSpeed(2.0f)
                }
            }
        })

        findViewById<FrameLayout>(R.id.mediaContainer)?.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            gestureControlManager.handleTouchEvent(event, view.width)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressDetected = false
                    gestureControlManager.setupAudioManager()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressDetected) {
                        viewModel.setPlaybackSpeed(originalSpeed)
                        isLongPressDetected = false
                    }
                }
            }
            true
        }
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener { finish() }
        downloadButton.setOnClickListener { /* 可实现下载 */ }
        playPauseButton.setOnClickListener { viewModel.togglePlayback() }
        previousButton.setOnClickListener { viewModel.playPrevious() }
        nextButton.setOnClickListener { viewModel.playNext() }
        fullscreenToggleButton.setOnClickListener {
            if (fullscreenManager.isFullscreen()) fullscreenManager.exitFullscreen()
            else fullscreenManager.enterFullscreen()
        }
        lyricsSettingsButton.setOnClickListener { showLyricsSettingsDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = viewModel.duration.value ?: 0L
                    if (duration > 0) {
                        val newPosition = (duration * progress / 1000).toLong()
                        currentTimeTextView.text = formatTime(newPosition)
                        viewModel.seekTo(newPosition)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun observeViewModel() {
        viewModel.currentTrackName.observe(this) { fileNameTextView.text = it }
        viewModel.artistAlbum.observe(this) { lyricsTitle.text = it }
        viewModel.coverUrl.observe(this) { loadCover(it) }
        viewModel.isPlaying.observe(this) {
            playPauseButton.setImageResource(
                if (it) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            musicVisualizerView.setPlaying(it)
        }
        viewModel.playbackState.observe(this) { state ->
            mediaLoadingProgress.visibility = if (state == PlaybackState.BUFFERING) View.VISIBLE else View.GONE
        }
        viewModel.currentPosition.observe(this) { pos ->
            val dur = viewModel.duration.value ?: 0L
            if (dur > 0) {
                seekBar.progress = (pos * 1000 / dur).toInt()
                currentTimeTextView.text = formatTime(pos)
            }
        }
        viewModel.duration.observe(this) { dur -> durationTextView.text = formatTime(dur) }
        viewModel.currentLyricsLine.observe(this) { currentLyricsLine.text = it }
        viewModel.nextLyricsLine.observe(this) { nextLyricsLine.text = it }
        viewModel.spectrumData.observe(this) { musicVisualizerView.updateSpectrum(it) }
        viewModel.errorMessage.observe(this) {
            if (!it.isNullOrEmpty()) Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
        // 歌词文件选择对话框
        viewModel.lyricsFileSelection.observe(this) { files ->
            if (!files.isNullOrEmpty()) {
                showLyricsFileSelectionDialog(files)
                viewModel.lyricsFileSelection.value = null  // 消费事件
            }
        }
    }

    private fun loadCover(coverUrl: String?) {
        if (coverUrl.isNullOrEmpty()) {
            audioCoverView.setImageDrawable(null)
            audioCoverView.setBackgroundColor(Color.BLACK)
            return
        }
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
            .build()
        val request = ImageRequest.Builder(this)
            .data(coverUrl)
            .target(audioCoverView)
            .error(android.R.color.black)
            .placeholder(android.R.color.black)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        imageLoader.enqueue(request)
    }

    private fun toggleControlsVisibility() {
        if (mediaControls.visibility == View.VISIBLE) {
            mediaControls.visibility = View.GONE
            controlContainer.visibility = View.GONE
        } else {
            mediaControls.visibility = View.VISIBLE
            mediaControls.bringToFront()
        }
    }

    private fun showLyricsSettingsDialog() {
        val options = listOf("重新加载歌词", "选择歌词文件", "标记为无歌词", "隐藏歌词", "编辑歌曲信息", "上传封面", "删除封面")
        AlertDialog.Builder(this)
            .setTitle("歌词设置")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "重新加载歌词" -> viewModel.reloadLyrics()
                    "选择歌词文件" -> viewModel.showDirectoryLyricsFiles { files -> showLyricsFileSelectionDialog(files) }
                    "标记为无歌词" -> viewModel.markAsNoLyrics()
                    "隐藏歌词" -> lyricsContainer.visibility = View.GONE
                    "编辑歌曲信息" -> showEditMetadataDialog()
                    "上传封面" -> showCoverUploadDialog()
                    "删除封面" -> viewModel.confirmDeleteCover()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLyricsFileSelectionDialog(files: List<FileServerService.LyricsFileInfo>) {
        val names = files.map { it.name }.toMutableList().also { it.add("无歌词（如纯音乐）") }
        AlertDialog.Builder(this)
            .setTitle("选择歌词文件")
            .setItems(names.toTypedArray()) { _, i ->
                if (i == names.size - 1) {
                    viewModel.markAsNoLyrics()
                } else {
                    viewModel.selectLyricsFile(files[i]) { viewModel.reloadLyrics() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditMetadataDialog() {
        val currentMeta = viewModel.currentSongMetadata.value ?: return
        val view = layoutInflater.inflate(R.layout.dialog_edit_metadata, null)
        val titleInput = view.findViewById<EditText>(R.id.editTitle)
        val artistInput = view.findViewById<EditText>(R.id.editArtist)
        val albumInput = view.findViewById<EditText>(R.id.editAlbum)
        titleInput.setText(currentMeta.title)
        artistInput.setText(currentMeta.artist)
        albumInput.setText(currentMeta.album)

        AlertDialog.Builder(this)
            .setTitle("编辑歌曲信息")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                viewModel.saveMetadata(
                    titleInput.text.toString().trim(),
                    artistInput.text.toString().trim(),
                    albumInput.text.toString().trim()
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCoverUploadDialog() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "选择封面"), 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { viewModel.uploadCover(it) }
        }
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format("%d:%02d", minutes, seconds % 60)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onActivityPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.release()
    }

    private fun checkAndRequestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_RECORD_AUDIO)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 可视化内部适配权限变化
    }
}