package com.dkc.fileserverclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File
import java.util.*

class AudioPlayerActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 100
    }

    private val viewModel: AudioPlayerViewModel by viewModels()

    private lateinit var audioCoverView: ImageView
    private lateinit var mediaLoadingProgress: ProgressBar

    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeTextView: TextView
    private lateinit var durationTextView: TextView

    private lateinit var lyricsContainer: LinearLayout
    private lateinit var lyricsTitle: TextView
    private lateinit var currentLyricsLine: TextView
    private lateinit var nextLyricsLine: TextView
    private lateinit var lyricsSettingsButton: Button

    // 频谱视图及开关按钮
    private lateinit var musicVisualizerView: MusicVisualizerView
    private lateinit var visualizerToggleButton: ImageButton

    private var currentCoverPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_player)
        enableImmersiveMode()
        checkAndRequestRecordAudioPermission()
        viewModel.init(intent)
        initViews()
        setupClickListeners()
        observeViewModel()

        viewModel.finishEvent.observe(this) { finish ->
            if (finish) finish()
        }
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView?.apply {
                rootWindowInsets?.let {
                    if (it.isVisible(WindowInsets.Type.statusBars())) {
                        window.insetsController?.apply {
                            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    private fun checkAndRequestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_RECORD_AUDIO)
            }
        }
    }

    private fun initViews() {
        audioCoverView = findViewById(R.id.audioCoverView)
        mediaLoadingProgress = findViewById(R.id.mediaLoadingProgress)
        playPauseButton = findViewById(R.id.playPauseButton)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        seekBar = findViewById(R.id.seekBar)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        durationTextView = findViewById(R.id.durationTextView)
        lyricsContainer = findViewById(R.id.lyricsContainer)
        lyricsTitle = findViewById(R.id.lyricsTitle)
        currentLyricsLine = findViewById(R.id.currentLyricsLine)
        nextLyricsLine = findViewById(R.id.nextLyricsLine)
        lyricsSettingsButton = findViewById(R.id.lyricsSettingsButton)

        musicVisualizerView = findViewById(R.id.musicVisualizerView)
        visualizerToggleButton = findViewById(R.id.visualizerToggleButton)

        seekBar.max = 1000
    }

    private fun setupClickListeners() {
        playPauseButton.setOnClickListener { viewModel.togglePlayback() }
        previousButton.setOnClickListener { viewModel.playPrevious() }
        nextButton.setOnClickListener { viewModel.playNext() }
        lyricsSettingsButton.setOnClickListener { showLyricsSettingsDialog() }

        // 频谱开关按钮
        visualizerToggleButton.setOnClickListener {
            viewModel.toggleVisualizer(viewModel.visualizerEnabled.value != true)
        }

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
        viewModel.artistAlbum.observe(this) { lyricsTitle.text = it }
        viewModel.coverLocalPath.observe(this) { path ->
            if (path != currentCoverPath) {
                currentCoverPath = path
                if (!path.isNullOrEmpty()) {
                    loadCoverFromFile(path)
                } else {
                    audioCoverView.setImageDrawable(null)
                    audioCoverView.setBackgroundColor(Color.BLACK)
                }
            }
        }
        viewModel.isPlaying.observe(this) {
            playPauseButton.setImageResource(
                if (it) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
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
        viewModel.errorMessage.observe(this) {
            if (!it.isNullOrEmpty()) Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
        viewModel.lyricsFileSelection.observe(this) { files ->
            if (!files.isNullOrEmpty()) {
                showLyricsFileSelectionDialog(files)
                viewModel.lyricsFileSelection.value = null
            }
        }

        // 频谱开关状态图标切换
        viewModel.visualizerEnabled.observe(this) { enabled ->
            visualizerToggleButton.setImageResource(
                if (enabled) R.drawable.ic_visualizer_on else R.drawable.ic_visualizer_off
            )
        }

        // 传递频谱运行状态给可视化View（控制衰减动画）
        viewModel.visualizerActive.observe(this) { active ->
            musicVisualizerView.setPlaying(active)
        }

        // 实时频谱数据
        viewModel.spectrumData.observe(this) { spectrum ->
            musicVisualizerView.updateSpectrum(spectrum)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.updateIntent(intent)
    }

    private fun loadCoverFromFile(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            audioCoverView.setImageDrawable(null)
            audioCoverView.setBackgroundColor(Color.BLACK)
            return
        }
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
            .build()
        val request = ImageRequest.Builder(this)
            .data(file)
            .target(audioCoverView)
            .error(android.R.color.black)
            .placeholder(android.R.color.black)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        imageLoader.enqueue(request)
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
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes % 60, seconds % 60)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds % 60)
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
        viewModel.release()
        super.onDestroy()
    }
}