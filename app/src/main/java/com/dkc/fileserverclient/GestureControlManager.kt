package com.dkc.fileserverclient

import android.app.Activity
import android.media.AudioManager
import android.os.Handler
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class GestureControlManager(
    private val activity: Activity,
    private val handler: Handler,
    private val audioManager: AudioManager,
    private val controlOverlay: TextView,
    private val controlIcon: ImageView,
    private val controlContainer: LinearLayout,
    private val regionWidth: Int
) {
    // 滑动控制相关变量
    private var startX = 0f
    private var startY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var isSwiping = false
    private var swipeRegion = -1 // 0:左, 1:中, 2:右

    // 音量控制相关
    private var volumeChangeAccumulator = 0f
    private var maxVolume: Int = 0
    private var currentVolume: Int = 0

    // 灵敏度设置
    var progressSensitivity = 0.5f
    var volumeSensitivity = 1.2f
    var brightnessSensitivity = 0.6f

    private val hideControlRunnable = Runnable { hideControlOverlay() }

    // 监听器接口
    interface GestureListener {
        fun onProgressControl(deltaX: Float, displayWidth: Int)
        fun onControlOverlayShow(text: String, iconRes: Int = 0)
        fun onSeekBarProgressUpdate(position: Long, duration: Long)
    }

    private var gestureListener: GestureListener? = null

    fun setGestureListener(listener: GestureListener) {
        this.gestureListener = listener
    }

    fun setupAudioManager() {
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun handleTouchEvent(event: MotionEvent, displayWidth: Int) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastMoveX = event.x
                lastMoveY = event.y
                isSwiping = false
                swipeRegion = -1

                // 更新当前系统音量状态
                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // 取消所有延迟任务
                handler.removeCallbacks(hideControlRunnable)
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - lastMoveX
                val deltaY = event.y - lastMoveY

                // 检测滑动开始
                if (!isSwiping && (abs(deltaX) > 5 || abs(deltaY) > 5)) {
                    isSwiping = true

                    // 确定滑动类型和区域（只确定一次）
                    if (swipeRegion == -1) {
                        if (abs(deltaX) > abs(deltaY) * 1.5) {
                            // 水平滑动 - 进度控制
                            swipeRegion = 1
                        } else {
                            // 垂直滑动 - 根据起始位置判断是亮度还是音量
                            swipeRegion = if (startX < regionWidth) 0 else 2
                        }
                    }
                }

                if (isSwiping && swipeRegion != -1) {
                    when (swipeRegion) {
                        0 -> { // 左侧垂直滑动 - 亮度控制
                            handleBrightnessControl(deltaY)
                        }
                        1 -> { // 水平滑动 - 进度控制
                            gestureListener?.onProgressControl(deltaX, displayWidth)
                        }
                        2 -> { // 右侧垂直滑动 - 音量控制
                            handleVolumeControl(deltaY)
                        }
                    }
                }

                lastMoveX = event.x
                lastMoveY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 如果是水平滑动结束，快速隐藏控制栏
                if (isSwiping && swipeRegion == 1) {
                    handler.postDelayed({ hideControlOverlay() }, 500)
                } else {
                    // 其他滑动立即隐藏
                    hideControlOverlay()
                }

                // 重置状态
                isSwiping = false
                swipeRegion = -1
                volumeChangeAccumulator = 0f
            }
        }
    }

    private fun handleBrightnessControl(deltaY: Float) {
        val window = activity.window
        val layoutParams = window.attributes

        // 计算亮度变化（向下滑动降低亮度，向上滑动提高亮度）
        val brightnessChange = -deltaY / 800f * brightnessSensitivity

        // 更新亮度值（范围：0.0 - 1.0）
        var newBrightness = layoutParams.screenBrightness + brightnessChange
        if (layoutParams.screenBrightness < 0) {
            // 如果之前没有设置过亮度，使用系统默认
            newBrightness = 0.5f + brightnessChange
        }
        newBrightness = newBrightness.coerceIn(0.01f, 1.0f)

        // 设置新的亮度
        layoutParams.screenBrightness = newBrightness
        window.attributes = layoutParams

        // 显示亮度提示
        showControlOverlay("亮度: ${(newBrightness * 100).toInt()}%", android.R.drawable.ic_menu_edit)
    }

    private fun handleVolumeControl(deltaY: Float) {
        // 累计变化量
        volumeChangeAccumulator += -deltaY / 800f * volumeSensitivity

        // 当累计变化量足够改变至少1个音量单位时
        if (abs(volumeChangeAccumulator) >= 1.0f / maxVolume) {
            currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // 计算需要改变的音量单位数
            val volumeUnitsToChange = (volumeChangeAccumulator * maxVolume).toInt()

            if (volumeUnitsToChange != 0) {
                var newVolume = currentVolume + volumeUnitsToChange
                newVolume = newVolume.coerceIn(0, maxVolume)

                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                // 显示音量提示
                val volumePercent = (newVolume * 100 / maxVolume).toInt()
                showControlOverlay("音量: $volumePercent%", R.drawable.ic_volume)

                // 重置累计器，保留余数
                volumeChangeAccumulator -= volumeUnitsToChange.toFloat() / maxVolume
            }
        }
    }

    fun showControlOverlay(text: String, iconRes: Int = 0) {
        controlOverlay.text = text
        if (iconRes != 0) {
            controlIcon.setImageResource(iconRes)
        }
        controlContainer.visibility = LinearLayout.VISIBLE
        gestureListener?.onControlOverlayShow(text, iconRes)

        // 2秒后自动隐藏
        handler.removeCallbacks(hideControlRunnable)
        handler.postDelayed(hideControlRunnable, 2000)
    }

    private fun hideControlOverlay() {
        controlContainer.visibility = LinearLayout.GONE
    }

    fun clear() {
        handler.removeCallbacks(hideControlRunnable)
        controlContainer.visibility = LinearLayout.GONE
        isSwiping = false
        swipeRegion = -1
        volumeChangeAccumulator = 0f
    }
}