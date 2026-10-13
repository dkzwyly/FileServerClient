package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 流星：从右上斜向左下划过，带渐变尾迹。
 */
class MeteorShowerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Meteor(
        var x: Float,
        var y: Float,
        var length: Float,
        var speed: Float,
        var alpha: Float,
        var thickness: Float
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val meteors = mutableListOf<Meteor>()
    private var animator: ValueAnimator? = null
    private var enabled = false
    private var timeSinceSpawn = 0f
    private var spawnInterval = 30f

    private val density = resources.displayMetrics.density
    private var viewWidth = 0
    private var viewHeight = 0

    // 从右上到左下
    private val angleRad = Math.toRadians(145.0).toFloat()
    private val dirX = cos(angleRad)
    private val dirY = sin(angleRad)

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
    }

    private fun spawnMeteor() {
        val burst = 1 + Random.nextInt(2)   // 1 或 2 颗
        repeat(burst) {
            val startX = viewWidth * 1.2f
            val startY = -viewHeight * 0.15f + Random.nextFloat() * viewHeight * 0.5f

            meteors.add(
                Meteor(
                    x = startX,
                    y = startY,
                    length = (80f + Random.nextFloat() * 160f) * density,
                    speed = (9f + Random.nextFloat() * 12f) * density,
                    alpha = 0.75f + Random.nextFloat() * 0.25f,
                    thickness = (1.2f + Random.nextFloat() * 1.8f) * density
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (viewWidth == 0) return

        val iterator = meteors.iterator()
        while (iterator.hasNext()) {
            val m = iterator.next()
            m.x += dirX * m.speed
            m.y += dirY * m.speed
            m.alpha -= 0.008f

            if (m.alpha <= 0f || m.x < -viewWidth * 0.5f || m.y > viewHeight * 1.5f) {
                iterator.remove()
                continue
            }

            val tailX = m.x - dirX * m.length
            val tailY = m.y - dirY * m.length

            paint.strokeWidth = m.thickness
            paint.shader = LinearGradient(
                m.x, m.y, tailX, tailY,
                intArrayOf(
                    Color.argb((m.alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255),
                    Color.argb((m.alpha * 180).toInt().coerceIn(0, 255), 180, 210, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(m.x, m.y, tailX, tailY, paint)
        }
    }

    private fun startAnim() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L / 60
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    timeSinceSpawn += 1f
                    if (timeSinceSpawn >= spawnInterval) {
                        timeSinceSpawn = 0f
                        // 原来是 40f + Random.nextFloat() * 60f
                        spawnInterval = 18f + Random.nextFloat() * 32f
                        spawnMeteor()
                    }
                    if (enabled) invalidate()
                }
            }
        }
        if (animator?.isRunning != true) animator?.start()
    }

    fun setMeteorEnabled(enable: Boolean) {
        if (enabled == enable) return
        enabled = enable
        if (enable) {
            meteors.clear()
            startAnim()
            invalidate()
        } else {
            animator?.cancel()
            animator = null
            meteors.clear()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}