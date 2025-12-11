@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class AudioLibraryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: ImageButton

    private val fileServerService by lazy { FileServerService(this) }
    private val audioList = mutableListOf<FileSystemItem>()
    private val filteredAudioList = mutableListOf<FileSystemItem>()
    private lateinit var adapter: AudioLibraryAdapter
    private var currentServerUrl = ""

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val audioLibraryPath = "data/音乐"  // 固定音频目录路径

    companion object {
        private const val TAG = "AudioLibraryActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_library)

        currentServerUrl = intent.getStringExtra("SERVER_URL") ?: ""
        if (currentServerUrl.isEmpty()) {
            finish()
            return
        }

        initViews()
        loadAudios()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.audioRecyclerView)
        statusText = findViewById(R.id.audioStatusText)
        titleText = findViewById(R.id.audioTitleText)
        backButton = findViewById(R.id.backButton)
        searchEditText = findViewById(R.id.searchEditText)
        clearSearchButton = findViewById(R.id.clearSearchButton)

        titleText.text = "音频库"

        // 设置返回按钮
        backButton.setOnClickListener {
            finish()
        }

        // 设置搜索功能
        setupSearch()

        // 使用线性布局管理器
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = AudioLibraryAdapter(currentServerUrl, filteredAudioList) { audioItem ->
            playAudio(audioItem)
        }
        recyclerView.adapter = adapter
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAudios(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        clearSearchButton.setOnClickListener {
            searchEditText.setText("")
            filterAudios("")
        }

        // 初始隐藏清除按钮
        clearSearchButton.visibility = View.GONE
    }

    private fun filterAudios(query: String) {
        val searchQuery = query.trim()

        // 显示/隐藏清除按钮
        clearSearchButton.visibility = if (searchQuery.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        filteredAudioList.clear()

        if (searchQuery.isEmpty()) {
            filteredAudioList.addAll(audioList)
        } else {
            filteredAudioList.addAll(audioList.filter { audio ->
                audio.name.contains(searchQuery, true) ||
                        audio.extension.contains(searchQuery, true)
            })
        }

        adapter.notifyDataSetChanged()

        // 更新状态文本
        if (searchQuery.isNotEmpty()) {
            statusText.text = "找到 ${filteredAudioList.size} 个匹配的音频文件"
        } else {
            statusText.text = "共找到 ${audioList.size} 个音频文件"
        }
    }

    private fun loadAudios() {
        coroutineScope.launch {
            statusText.text = "正在加载音频..."

            try {
                Log.d(TAG, "开始加载音频目录: $audioLibraryPath")

                val allItems = withContext(Dispatchers.IO) {
                    fileServerService.getFileList(currentServerUrl, audioLibraryPath)
                }

                Log.d(TAG, "获取到 ${allItems.size} 个项目")

                // 过滤出音频文件
                audioList.clear()
                audioList.addAll(allItems.filter { item ->
                    !item.isDirectory && AudioUtils.isAudioFile(item)
                })

                Log.d(TAG, "过滤后得到 ${audioList.size} 个音频文件")

                // 更新过滤列表
                filteredAudioList.clear()
                filteredAudioList.addAll(audioList)

                if (audioList.isEmpty()) {
                    statusText.text = "没有找到音频文件"
                } else {
                    statusText.text = "共找到 ${audioList.size} 个音频文件"
                    adapter.notifyDataSetChanged()

                    // 显示第一个音频文件的信息用于调试
                    if (audioList.isNotEmpty()) {
                        val firstAudio = audioList[0]
                        Log.d(TAG, "第一个音频: ${firstAudio.name}, 路径: ${firstAudio.path}, 大小: ${firstAudio.sizeFormatted}")
                    }
                }

            } catch (e: Exception) {
                statusText.text = "加载失败: ${e.message}"
                Log.e(TAG, "加载音频异常", e)
            }
        }
    }

    private fun playAudio(audioItem: FileSystemItem) {
        try {
            // 转换为AudioTrack
            val audioTrack = AudioTrack.fromFileSystemItem(audioItem, currentServerUrl)

            // 获取音频文件列表并转换为AudioTrack列表
            val audioTracks = AudioUtils.convertToAudioTracks(filteredAudioList, currentServerUrl)
            val currentIndex = filteredAudioList.indexOf(audioItem)

            // 获取音频文件所在目录
            val directory = getDirectoryFromPath(audioItem.path)
            Log.d(TAG, "音频目录: $directory")

            // 设置自动连播 - 传递AudioTrack列表
            val intent = Intent(this, PreviewActivity::class.java).apply {
                putExtra("FILE_NAME", audioItem.name)
                putExtra("FILE_URL", audioTrack.url)
                putExtra("FILE_TYPE", "audio")
                putExtra("FILE_PATH", audioItem.path)  // 完整路径，用于歌词查找

                // 关键：传递AudioTrack列表，这会触发音频模式
                putExtra("AUDIO_TRACK", audioTrack)
                putExtra("AUDIO_TRACKS", ArrayList(audioTracks))

                putExtra("CURRENT_INDEX", currentIndex)
                putExtra("SERVER_URL", currentServerUrl)
                putExtra("CURRENT_PATH", directory)

                // 从音乐库进入的标志
                putExtra("FROM_MUSIC_LIBRARY", true)
                putExtra("SHOULD_AUTO_PLAY", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败", e)
        }
    }

    // 辅助方法：从路径中提取目录
    private fun getDirectoryFromPath(filePath: String): String {
        return try {
            val file = java.io.File(filePath)
            val parent = file.parent ?: ""
            Log.d(TAG, "从路径 $filePath 提取目录: $parent")
            parent
        } catch (e: Exception) {
            Log.e(TAG, "提取目录失败", e)
            ""
        }
    }



    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        Log.d(TAG, "onDestroy")
    }
}