package com.dkc.fileserverclient.effect

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

/**
 * 月光：朦胧柔和的冷白光晕，不带条纹。
 * 位置与 SunbeamView 的太阳一致（左上角 50dp, 70dp）。
 */
class MoonlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val animator: ValueAnimator

    private var enabled = false
    private var phase = 0f

    private var viewWidth = 0
    private var viewHeight = 0
    private val density = context.resources.displayMetrics.density

    private var moonX = 0f
    private var moonY = 0f
    private var moonRadius = 0f

    // 与 SunbeamView 一致：左上角 50dp, 70dp
    private val anchorX = 50f
    private val anchorY = 70f
    private val baseRadiusDp = 120f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                if (enabled) invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h

        // 与 SunbeamView 一样使用固定 dp 偏移
        moonX = anchorX * density
        moonY = anchorY * density
        moonRadius = baseRadiusDp * density
    }

    override fun onDraw(canvas: Canvas) {
        if (viewWidth == 0 || viewHeight == 0) return

        // 呼吸：8 秒一次，只做轻微明暗变化
        val breathe = 0.90f + 0.10f * sin(phase * 2f * Math.PI.toFloat())

        // 多层柔光叠加，形成朦胧感
        drawSoftGlow(canvas, moonRadius * 3.0f, (45 * breathe).toInt())
        drawSoftGlow(canvas, moonRadius * 1.8f, (75 * breathe).toInt())
        drawSoftGlow(canvas, moonRadius * 1.0f, (120 * breathe).toInt())

        // 月核，依然柔和
        drawMoonCore(canvas, moonRadius * 0.55f)
    }

    private fun drawSoftGlow(canvas: Canvas, radius: Float, alpha: Int) {
        val shader = RadialGradient(
            moonX, moonY, radius,
            intArrayOf(
                Color.argb(alpha, 225, 232, 255),
                Color.argb((alpha * 0.40f).toInt(), 205, 218, 250),
                Color.argb((alpha * 0.10f).toInt(), 190, 205, 245),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.40f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawCircle(moonX, moonY, radius, paint)
    }

    private fun drawMoonCore(canvas: Canvas, radius: Float) {
        val shader = RadialGradient(
            moonX, moonY, radius,
            intArrayOf(
                Color.argb(220, 255, 255, 255),
                Color.argb(180, 245, 248, 255),
                Color.argb(100, 235, 242, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawCircle(moonX, moonY, radius, paint)
    }

    fun setMoonEnabled(enable: Boolean) {
        if (enabled == enable) return
        enabled = enable
        if (enable) {
            if (animator.isRunning.not()) animator.start()
            invalidate()
        } else {
            animator.cancel()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}