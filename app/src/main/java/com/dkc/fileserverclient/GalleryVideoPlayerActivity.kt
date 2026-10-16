@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.dkc.fileserverclient

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 相册里专用视频播放器：全屏、极简、不复用 VideoPlayerActivityV2。
 */
class GalleryVideoPlayerActivity : AppCompatActivity(), Player.Listener {

    private lateinit var playerView: PlayerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var fileNameText: TextView

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery_video_player)

        playerView = findViewById(R.id.playerView)
        loadingProgress = findViewById(R.id.loadingProgress)
        errorText = findViewById(R.id.errorText)
        backButton = findViewById(R.id.backButton)
        fileNameText = findViewById(R.id.fileNameText)

        val fileUrl = intent.getStringExtra("FILE_URL") ?: ""
        val fileName = intent.getStringExtra("FILE_NAME") ?: "视频"

        fileNameText.text = fileName
        backButton.setOnClickListener { finish() }

        if (fileUrl.isEmpty()) {
            showError("无效的视频地址")
            return
        }

        initPlayer()
        playVideo(fileUrl)
    }

    private fun initPlayer() {
        val client = UnsafeHttpClient.createUnsafeOkHttpClient()
        val httpDataSourceFactory = OkHttpDataSource.Factory(client)
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(defaultDataSourceFactory))
            .build()
            .apply {
                playerView.player = this
                addListener(this@GalleryVideoPlayerActivity)
            }
    }

    private fun playVideo(url: String) {
        loadingProgress.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun showError(msg: String) {
        loadingProgress.visibility = View.GONE
        errorText.text = msg
        errorText.visibility = View.VISIBLE
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> loadingProgress.visibility = View.VISIBLE
            Player.STATE_READY -> loadingProgress.visibility = View.GONE
            Player.STATE_ENDED -> loadingProgress.visibility = View.GONE
            Player.STATE_IDLE -> loadingProgress.visibility = View.GONE
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        showError("播放错误: ${error.message}")
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}