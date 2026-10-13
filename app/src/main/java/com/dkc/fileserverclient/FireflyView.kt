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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 萤火虫：暖黄色微光点，缓慢漂移 + 闪烁，适合深粉夜色。
 */
class FireflyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Firefly(
        var baseX: Float,
        var baseY: Float,
        var radius: Float,
        var phase: Float,
        var phaseSpeed: Float,
        var driftX: Float,
        var driftY: Float,
        var driftPhase: Float,
        var driftSpeed: Float
    )

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val fireflies = mutableListOf<Firefly>()
    private var animator: ValueAnimator? = null
    private var time = 0f
    private var enabled = false

    private val count = 18
    private val density = resources.displayMetrics.density
    private var viewWidth = 0
    private var viewHeight = 0

    private val coreColor = Color.argb(255, 255, 240, 180)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (enabled) initFireflies()
    }

    private fun initFireflies() {
        fireflies.clear()
        for (i in 0 until count) {
            val x = Random.nextFloat() * viewWidth
            val y = viewHeight * 0.3f + Random.nextFloat() * viewHeight * 0.7f
            fireflies.add(
                Firefly(
                    baseX = x,
                    baseY = y,
                    radius = (1.8f + Random.nextFloat() * 2f) * density,
                    phase = Random.nextFloat() * 2f * Math.PI.toFloat(),
                    phaseSpeed = 0.8f + Random.nextFloat() * 1.5f,
                    driftX = (10f + Random.nextFloat() * 25f) * density,
                    driftY = (8f + Random.nextFloat() * 20f) * density,
                    driftPhase = Random.nextFloat() * 2f * Math.PI.toFloat(),
                    driftSpeed = 0.2f + Random.nextFloat() * 0.5f
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (viewWidth == 0) return

        for (f in fireflies) {
            val blink = 0.5f + 0.5f * sin(f.phase + time * f.phaseSpeed)
            val dx = sin(f.driftPhase + time * f.driftSpeed) * f.driftX
            val dy = cos(f.driftPhase + time * f.driftSpeed * 0.7f) * f.driftY
            val x = f.baseX + dx
            val y = f.baseY + dy

            val baseAlpha = (100 + blink * 155).toInt().coerceIn(0, 255)
            val glowRadius = f.radius * (4f + blink * 2.5f)
            val glowAlpha = (baseAlpha * 0.35f).toInt().coerceIn(0, 255)

            glowPaint.shader = RadialGradient(
                x, y, glowRadius,
                intArrayOf(
                    Color.argb(glowAlpha, 255, 220, 140),
                    Color.argb((glowAlpha * 0.4f).toInt(), 255, 200, 120),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, glowRadius, glowPaint)

            corePaint.color = Color.argb(
                baseAlpha,
                Color.red(coreColor),
                Color.green(coreColor),
                Color.blue(coreColor)
            )
            canvas.drawCircle(x, y, f.radius, corePaint)
        }
    }

    private fun startAnim() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L / 60
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    time += 0.05f
                    if (enabled) invalidate()
                }
            }
        }
        if (animator?.isRunning != true) animator?.start()
    }

    fun setFireflyEnabled(enable: Boolean) {
        if (enabled == enable) return
        enabled = enable
        if (enable) {
            if (viewWidth > 0 && viewHeight > 0) initFireflies()
            startAnim()
            invalidate()
        } else {
            animator?.cancel()
            animator = null
            fireflies.clear()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}