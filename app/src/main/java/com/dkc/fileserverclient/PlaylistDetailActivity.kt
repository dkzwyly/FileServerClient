@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistDetailActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "playlist_play_mode"
        private const val KEY_MODE_PREFIX = "mode_"
        const val EXTRA_PLAY_MODE = "PLAY_MODE"

        // 播放模式常量
        const val MODE_LIST = 0      // 列表循环
        const val MODE_SINGLE = 1    // 单曲循环
        const val MODE_RANDOM = 2    // 随机播放
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var adapter: PlaylistDetailAdapter
    private lateinit var prefs: SharedPreferences

    private var playlistId: String? = null
    private var serverUrl: String = ""
    private var currentPlayMode: Int = MODE_LIST   // 默认列表循环

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
        // 读取保存的播放模式
        currentPlayMode = prefs.getInt(KEY_MODE_PREFIX + playlistId, MODE_LIST)

        initViews()
        loadPlaylist()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = PlaylistDetailAdapter(
            tracks = emptyList(),
            serverUrl = serverUrl,
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

    /**
     * 显示播放模式选择对话框
     */
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
                    // 保存到 SharedPreferences
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
        val intent = Intent(this, PreviewActivity::class.java).apply {
            putExtra("AUDIO_TRACK", track)
            putExtra("AUDIO_TRACKS", ArrayList(playlist?.tracks ?: emptyList()))
            putExtra("CURRENT_INDEX", index)
            putExtra("SERVER_URL", serverUrl)
            putExtra("FILE_NAME", track.name)
            putExtra("FILE_TYPE", "audio")
            putExtra("FROM_MUSIC_LIBRARY", true)
            putExtra("SHOULD_AUTO_PLAY", true)
            // 传递播放模式
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
            } else {
                Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}