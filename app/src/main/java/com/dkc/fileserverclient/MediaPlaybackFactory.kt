package com.dkc.fileserverclient

import android.os.Handler
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.ui.PlayerView

object MediaPlaybackFactory {

    fun createController(
        type: PlaybackType,
        httpClient: okhttp3.OkHttpClient,
        playerView: PlayerView? = null,
        videoLoadingProgress: ProgressBar? = null,
        playPauseButton: ImageButton? = null,
        seekBar: SeekBar? = null,
        currentTimeTextView: TextView? = null,
        durationTextView: TextView? = null,
        uiHandler: Handler
    ): MediaPlaybackController {
        // 仅保留视频控制器（音频已迁移至 Media3，不再使用此工厂）
        return VideoPlaybackController(
            httpClient = httpClient,
            playerView = playerView!!,
            videoLoadingProgress = videoLoadingProgress!!,
            playPauseButton = playPauseButton!!,
            seekBar = seekBar!!,
            currentTimeTextView = currentTimeTextView!!,
            durationTextView = durationTextView!!,
            uiHandler = uiHandler
        )
    }
}