package com.dkc.fileserverclient

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageButton

class FullscreenManager(
    private val activity: Activity,
    private val titleBar: LinearLayout,
    private val fileTypeTextView: TextView,
    private val fullscreenToggleButton: ImageButton
) {
    private var isFullscreen = false
    private var fullscreenChangeListener: ((Boolean) -> Unit)? = null

    fun setFullscreenChangeListener(listener: (Boolean) -> Unit) {
        fullscreenChangeListener = listener
    }

    @SuppressLint("InlinedApi")
    fun enterFullscreen() {
        isFullscreen = true

        activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        titleBar.visibility = View.GONE
        fileTypeTextView.visibility = View.GONE
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }

        fullscreenToggleButton.setImageResource(R.drawable.ic_fullscreen_exit)
        fullscreenChangeListener?.invoke(true)
    }

    @SuppressLint("InlinedApi")
    fun exitFullscreen() {
        isFullscreen = false

        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        titleBar.visibility = View.VISIBLE
        fileTypeTextView.visibility = View.VISIBLE
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        }

        fullscreenToggleButton.setImageResource(R.drawable.ic_fullscreen)
        fullscreenChangeListener?.invoke(false)
    }

    fun isFullscreen(): Boolean = isFullscreen

    fun onBackPressed(): Boolean {
        return if (isFullscreen) {
            exitFullscreen()
            true
        } else {
            false
        }
    }
}