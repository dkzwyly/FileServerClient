@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var adapter: PlaylistDetailAdapter

    private var playlistId: String? = null
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist_detail)

        playlistId = intent.getStringExtra("PLAYLIST_ID")
        serverUrl = intent.getStringExtra("SERVER_URL") ?: ""

        if (playlistId == null) {
            finish()
            return
        }

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
        }
        startActivity(intent)
    }

    private fun removeTrackFromPlaylist(track: AudioTrack) {
        playlistId?.let { id ->
            val success = PlaylistManager.removeTrackFromPlaylist(id, track.id)
            if (success) {
                Toast.makeText(this, "已从歌单移除", Toast.LENGTH_SHORT).show()
                loadPlaylist() // 重新加载
            } else {
                Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}