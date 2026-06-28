@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "playlist_play_mode"
        private const val KEY_MODE_PREFIX = "mode_"
        const val EXTRA_PLAY_MODE = "PLAY_MODE"

        const val MODE_LIST = 0
        const val MODE_SINGLE = 1
        const val MODE_RANDOM = 2

        private const val PREFS_ANIMATION = "playlist_detail_animation"
        private const val KEY_ANIMATION_INDEX = "animation_index"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var adapter: PlaylistDetailAdapter
    private lateinit var prefs: SharedPreferences

    private var playlistId: String? = null
    private var serverUrl: String = ""
    private var currentPlayMode: Int = MODE_LIST

    // 新架构：Media3 控制器
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val animations = listOf(
        StaticBackgroundAnimation(),
        ShineAnimationSimple()
    )
    private var currentAnimationIndex = 0

    // Media3 播放器监听器（用于高亮更新）
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            runOnUiThread {
                updateHighlight()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE) {
                runOnUiThread {
                    adapter.setCurrentlyPlaying(null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)

        playlistId = intent.getStringExtra("PLAYLIST_ID")
        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""

        if (playlistId == null) {
            finish()
            return
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentPlayMode = prefs.getInt(KEY_MODE_PREFIX + playlistId, MODE_LIST)

        loadAnimationIndexFromPrefs()
        initViews()
        loadPlaylist()
    }

    override fun onStart() {
        super.onStart()
        // 连接到 MusicService (Media3)
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                mediaController?.addListener(playerListener)
                // 连接成功后刷新高亮
                updateHighlight()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        controllerFuture = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除监听（已由 onStop 处理）
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.playlist_detail_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_play_mode -> {
                showPlayModeDialog()
                return true
            }
            R.id.action_switch_animation -> {
                switchToNextAnimation()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadAnimationIndexFromPrefs() {
        val prefsAnim = getSharedPreferences(PREFS_ANIMATION, Context.MODE_PRIVATE)
        currentAnimationIndex = prefsAnim.getInt(KEY_ANIMATION_INDEX, 0)
        if (currentAnimationIndex >= animations.size) currentAnimationIndex = 0
    }

    private fun saveAnimationIndex() {
        getSharedPreferences(PREFS_ANIMATION, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ANIMATION_INDEX, currentAnimationIndex)
            .apply()
    }

    private fun switchToNextAnimation() {
        currentAnimationIndex = (currentAnimationIndex + 1) % animations.size
        val newAnimation = animations[currentAnimationIndex]
        adapter.setAnimation(newAnimation)
        saveAnimationIndex()
        val animationName = when (newAnimation) {
            is StaticBackgroundAnimation -> "静态蓝"
            is ShineAnimationSimple -> "扫光"
            else -> "未知"
        }
        Toast.makeText(this, "动画: $animationName", Toast.LENGTH_SHORT).show()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = PlaylistDetailAdapter(
            tracks = emptyList(),
            onItemClick = { track, index -> playTrack(track, index) },
            onRemoveClick = { track -> removeTrackFromPlaylist(track) },
            animation = animations[currentAnimationIndex]
        )
        recyclerView.adapter = adapter
    }

    private fun showPlayModeDialog() {
        val modes = arrayOf(
            getString(R.string.list_cycle),
            getString(R.string.single_cycle),
            getString(R.string.random_play)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.play_mode)
            .setSingleChoiceItems(modes, currentPlayMode) { dialog, which ->
                if (currentPlayMode != which) {
                    currentPlayMode = which
                    prefs.edit().putInt(KEY_MODE_PREFIX + playlistId, currentPlayMode).apply()
                    Toast.makeText(this, getString(R.string.play_mode_set, modes[which]), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadPlaylist() {
        val playlist = PlaylistManager.getPlaylist(playlistId!!)
        if (playlist == null) {
            Toast.makeText(this, "歌单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        supportActionBar?.title = playlist.name
        adapter.updateData(playlist.tracks)
        // 加载完成后尝试高亮当前正在播放的曲目
        updateHighlight()
    }

    // 新增：从 MediaController 获取当前播放媒体 ID 并更新高亮
    private fun updateHighlight() {
        val currentMediaId = mediaController?.currentMediaItem?.mediaId
        adapter.setCurrentlyPlaying(currentMediaId)
        if (currentMediaId != null) {
            val position = adapter.getPositionByTrackPath(currentMediaId)
            if (position != -1) {
                scrollToCenter(position)
            }
        }
    }

    private fun playTrack(track: AudioTrack, index: Int) {
        val playlist = PlaylistManager.getPlaylist(playlistId!!)
        val intent = Intent(this, AudioPlayerActivity::class.java).apply {
            putExtra("AUDIO_TRACK", track)
            putExtra("AUDIO_TRACKS", ArrayList(playlist?.tracks ?: emptyList()))
            putExtra("CURRENT_INDEX", index)
            putExtra("SERVER_URL", serverUrl)
            putExtra("FILE_NAME", track.name)
            putExtra("FILE_TYPE", "audio")
            putExtra("FROM_MUSIC_LIBRARY", true)
            putExtra(EXTRA_PLAY_MODE, currentPlayMode)
        }
        startActivity(intent)
    }

    private fun removeTrackFromPlaylist(track: AudioTrack) {
        playlistId?.let { id ->
            val success = PlaylistManager.removeTrackFromPlaylist(id, track.id)
            if (success) {
                Toast.makeText(this, "已从歌单移除", Toast.LENGTH_SHORT).show()
                loadPlaylist()
                // 如果移除的是当前播放的曲目，清除高亮
                val currentMediaId = mediaController?.currentMediaItem?.mediaId
                if (currentMediaId == track.path) {
                    adapter.setCurrentlyPlaying(null)
                }
            } else {
                Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scrollToCenter(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val targetTopRatio = 0.35f
        val targetView = layoutManager.findViewByPosition(position)
        if (targetView != null) {
            val itemHeight = targetView.height
            val recyclerHeight = recyclerView.height
            val targetTop = (recyclerHeight * targetTopRatio).toInt()
            val offset = targetTop - (itemHeight / 2)
            val currentTop = targetView.top
            val delta = currentTop - offset
            recyclerView.smoothScrollBy(0, delta)
        } else {
            layoutManager.scrollToPositionWithOffset(position, 0)
            recyclerView.post {
                val newTargetView = layoutManager.findViewByPosition(position)
                newTargetView?.let {
                    val itemHeight = it.height
                    val recyclerHeight = recyclerView.height
                    val targetTop = (recyclerHeight * targetTopRatio).toInt()
                    val offset = targetTop - (itemHeight / 2)
                    val currentTop = it.top
                    val delta = currentTop - offset
                    recyclerView.smoothScrollBy(0, delta)
                }
            }
        }
    }
}