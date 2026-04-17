// MusicVisualizerView.kt
package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class MusicVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var amplitudes = FloatArray(32) { 0.1f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var isPlaying = true

    /**
     * 更新频谱数据
     */
    fun updateSpectrum(newAmplitudes: FloatArray) {
        if (!isPlaying) {
            // 暂停时逐渐衰减到零
            var allZero = true
            for (i in amplitudes.indices) {
                if (amplitudes[i] > 0.01f) {
                    allZero = false
                    break
                }
            }
            if (!allZero) {
                for (i in amplitudes.indices) {
                    amplitudes[i] = amplitudes[i] * 0.9f
                }
                invalidate()
            }
            return
        }
        if (newAmplitudes.size != amplitudes.size) return
        for (i in amplitudes.indices) {
            amplitudes[i] = amplitudes[i] * 0.7f + newAmplitudes[i] * 0.3f
        }
        invalidate()
    }

    /**
     * 设置播放状态（播放时正常显示频谱，暂停时逐渐归零）
     */
    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (!playing) {
            // 暂停时启动衰减动画
            post(object : Runnable {
                override fun run() {
                    var changed = false
                    for (i in amplitudes.indices) {
                        if (amplitudes[i] > 0.01f) {
                            amplitudes[i] = amplitudes[i] * 0.8f
                            changed = true
                        } else {
                            amplitudes[i] = 0f
                        }
                    }
                    if (changed) {
                        invalidate()
                        postDelayed(this, 50)
                    }
                }
            })
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val barWidth = width.toFloat() / amplitudes.size
        val maxBarHeight = height.toFloat() * 0.8f

        for (i in amplitudes.indices) {
            val left = i * barWidth + 4f
            val right = (i + 1) * barWidth - 4f
            val barHeight = amplitudes[i] * maxBarHeight
            val top = height - barHeight
            rect.set(left, top, right, height.toFloat())

            val hue = 180f + amplitudes[i] * 180f
            val color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            paint.color = color

            canvas.drawRoundRect(rect, 8f, 8f, paint)
        }
    }
}