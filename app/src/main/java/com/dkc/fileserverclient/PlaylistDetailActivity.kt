@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "playlist_play_mode"
        private const val KEY_MODE_PREFIX = "mode_"
        const val EXTRA_PLAY_MODE = "PLAY_MODE"

        const val MODE_LIST = 0
        const val MODE_SINGLE = 1
        const val MODE_RANDOM = 2
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var adapter: PlaylistDetailAdapter
    private lateinit var prefs: SharedPreferences

    private var playlistId: String? = null
    private var serverUrl: String = ""
    private var currentPlayMode: Int = MODE_LIST

    // 播放服务相关
    private var playbackService: AudioPlaybackService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as AudioPlaybackService.AudioServiceBinder).getService()
            playbackService?.addPlaybackListener(playbackListener)
            val currentTrack = playbackService?.getCurrentTrack()
            adapter.setCurrentlyPlaying(currentTrack?.id)
            currentTrack?.let {
                val position = adapter.getPositionByTrackId(it.id)
                if (position != -1) {
                    scrollToCenter(position)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.removePlaybackListener(playbackListener)
            playbackService = null
            isBound = false
        }
    }

    private val playbackListener = object : AudioPlaybackListener {
        override fun onTrackChanged(track: AudioTrack, index: Int) {
            runOnUiThread {
                adapter.setCurrentlyPlaying(track.id)
                val position = adapter.getPositionByTrackId(track.id)
                if (position != -1) {
                    scrollToCenter(position)
                }
            }
        }

        override fun onPlaybackStateChanged(status: AudioPlaybackStatus) {}
        override fun onPlaybackError(error: String) {}
        override fun onPlaybackEnded() {}
        override fun onAudioBuffering(isBuffering: Boolean) {}
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

        initViews()
        loadPlaylist()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioPlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isBound = true
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackService?.removePlaybackListener(playbackListener)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = PlaylistDetailAdapter(
            tracks = emptyList(),
            onItemClick = { track, index ->
                playTrack(track, index)
            },
            onRemoveClick = { track ->
                removeTrackFromPlaylist(track)
            }
        )
        recyclerView.adapter = adapter
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
        }
        return super.onOptionsItemSelected(item)
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
                val currentPlayingId = playbackService?.getCurrentTrack()?.id
                if (currentPlayingId == track.id) {
                    adapter.setCurrentlyPlaying(null)
                }
            } else {
                Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 滚动到列表指定位置并使其偏上显示（例如屏幕高度的 35% 处）
     */
    private fun scrollToCenter(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val targetView = layoutManager.findViewByPosition(position)
        // 可调整偏上的比例，值越小越靠上，0.35 表示顶部在屏幕 35% 处
        val targetTopRatio = 0.35f
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