package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin
import kotlin.random.Random

/**
 * 夜雾：大面积柔和的青灰色雾气，缓慢横向漂移，朦胧不刺眼。
 */
class NightMistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class MistBlob(
        var x: Float,
        var y: Float,
        var radius: Float,
        var alpha: Float,
        var speedX: Float,
        var phase: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobs = mutableListOf<MistBlob>()
    private var animator: ValueAnimator? = null
    private var enabled = false
    private var time = 0f

    private val density = resources.displayMetrics.density
    private var viewWidth = 0
    private var viewHeight = 0

    private val count = 18

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (enabled) initBlobs()
    }

    private fun initBlobs() {
        blobs.clear()
        for (i in 0 until count) {
            blobs.add(
                MistBlob(
                    x = Random.nextFloat() * viewWidth,
                    y = Random.nextFloat() * viewHeight,
                    radius = (180f + Random.nextFloat() * 260f) * density,   // 原来是 140f + 220f
                    alpha = 0.12f + Random.nextFloat() * 0.18f,   // 原来是 0.05f + 0.10f
                    speedX = (0.15f + Random.nextFloat() * 0.35f) * density,
                    phase = Random.nextFloat() * 2f * Math.PI.toFloat()
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (viewWidth == 0) return

        for (b in blobs) {
            b.x += b.speedX
            if (b.x - b.radius > viewWidth) {
                b.x = -b.radius
                b.y = Random.nextFloat() * viewHeight
            }

            val breathe = 0.9f + 0.1f * sin(b.phase + time * 0.5f)   // 原来是 0.8f + 0.2f
            val alpha = (b.alpha * breathe * 255).toInt().coerceIn(0, 255)

            paint.shader = RadialGradient(
                b.x, b.y, b.radius,
                intArrayOf(
                    Color.argb(alpha, 200, 220, 225),
                    Color.argb((alpha * 0.5f).toInt(), 180, 210, 215),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(b.x, b.y, b.radius, paint)
        }
    }

    private fun startAnim() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L / 60
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    time += 0.02f
                    if (enabled) invalidate()
                }
            }
        }
        if (animator?.isRunning != true) animator?.start()
    }

    fun setMistEnabled(enable: Boolean) {
        if (enabled == enable) return
        enabled = enable
        if (enable) {
            if (viewWidth > 0 && viewHeight > 0) initBlobs()
            startAnim()
            invalidate()
        } else {
            animator?.cancel()
            animator = null
            blobs.clear()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}