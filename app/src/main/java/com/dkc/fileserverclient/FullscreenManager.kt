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

    @SuppressLint("InlinedApi")
    fun enterFullscreen() {
        isFullscreen = true

        // 隐藏状态栏和导航栏
        activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )

        // 设置全屏窗口标志
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 隐藏标题栏和其他UI元素
        titleBar.visibility = View.GONE
        fileTypeTextView.visibility = View.GONE

        // 横屏
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 保持屏幕常亮
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 隐藏系统UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        }

        // 更新全屏按钮图标
        fullscreenToggleButton.setImageResource(R.drawable.ic_fullscreen_exit)
    }

    @SuppressLint("InlinedApi")
    fun exitFullscreen() {
        isFullscreen = false

        // 显示状态栏和导航栏
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        // 清除全屏窗口标志
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // 显示标题栏
        titleBar.visibility = View.VISIBLE
        fileTypeTextView.visibility = View.VISIBLE

        // 竖屏
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 取消屏幕常亮
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 显示系统UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
        }

        // 更新全屏按钮图标
        fullscreenToggleButton.setImageResource(R.drawable.ic_fullscreen)
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