package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class SnowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private data class Snowflake(
        var x: Float,
        var y: Float,
        var size: Float,
        var alpha: Int,
        var speedY: Float,
        var speedX: Float,
        var rotation: Float,
        var scale: Float = 1f
    )

    private val snowflakes = mutableListOf<Snowflake>()
    private var animator: ValueAnimator? = null
    private var isFadingOut = false
    private var fadeOutCallback: (() -> Unit)? = null
    private var enabled = false

    private val snowflakePath = Path()
    private val pointsCount = 6

    private val snowCount = 18
    private val minSize = 6f
    private val maxSize = 14f
    private val minSpeed = 0.4f
    private val maxSpeed = 1.0f
    private val windRange = 0.2f
    private val minAlpha = 140
    private val maxAlpha = 220

    private var viewWidth = 0
    private var viewHeight = 0
    private var density = 1f

    init {
        density = resources.displayMetrics.density
        buildSnowflakePath()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun buildSnowflakePath() {
        snowflakePath.reset()
        val angleStep = 360.0 / pointsCount
        val outerRadius = 1f
        val innerRadius = 0.4f
        for (i in 0 until pointsCount) {
            val angle1 = Math.toRadians(i * angleStep).toFloat()
            val x1 = outerRadius * cos(angle1)
            val y1 = outerRadius * sin(angle1)
            val angle2 = Math.toRadians(i * angleStep + angleStep / 2).toFloat()
            val x2 = innerRadius * cos(angle2)
            val y2 = innerRadius * sin(angle2)
            if (i == 0) snowflakePath.moveTo(x1, y1)
            else snowflakePath.lineTo(x1, y1)
            snowflakePath.lineTo(x2, y2)
        }
        snowflakePath.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (enabled && snowflakes.isEmpty()) {
            initSnowflakes()
        }
    }

    private fun initSnowflakes() {
        snowflakes.clear()
        for (i in 0 until snowCount) {
            val size = (minSize + Random.nextFloat() * (maxSize - minSize)) * density
            val speedY = (minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)) * density
            val speedX = (Random.nextFloat() - 0.5f) * windRange * density
            val alpha = (minAlpha + Random.nextInt(maxAlpha - minAlpha)).coerceIn(0, 255)
            val rotation = Random.nextFloat() * 360f
            snowflakes.add(
                Snowflake(
                    x = Random.nextFloat() * viewWidth,
                    y = Random.nextFloat() * viewHeight,
                    size = size,
                    alpha = alpha,
                    speedY = speedY,
                    speedX = speedX,
                    rotation = rotation,
                    scale = 1f
                )
            )
        }
    }

    private fun updateSnowflakes() {
        val iterator = snowflakes.iterator()
        while (iterator.hasNext()) {
            val flake = iterator.next()
            flake.y += flake.speedY
            flake.x += flake.speedX
            if (!isFadingOut) {
                if (flake.y > viewHeight) {
                    flake.y = -flake.size * 2
                    flake.x = Random.nextFloat() * viewWidth
                }
                if (flake.x < -flake.size * 2) {
                    flake.x = viewWidth + flake.size * 2
                } else if (flake.x > viewWidth + flake.size * 2) {
                    flake.x = -flake.size * 2
                }
            }
            if (isFadingOut) {
                flake.scale *= 0.94f
                if (flake.scale <= 0.05f) {
                    iterator.remove()
                }
            }
        }
        if (isFadingOut && snowflakes.isEmpty()) {
            stopAnimationInternal()
            fadeOutCallback?.invoke()
            fadeOutCallback = null
            isFadingOut = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (flake in snowflakes) {
            paint.alpha = (flake.alpha * flake.scale).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(flake.x, flake.y)
            canvas.rotate(flake.rotation)
            canvas.scale(flake.size * flake.scale, flake.size * flake.scale)
            canvas.drawPath(snowflakePath, paint)
            canvas.restore()
        }
    }

    private fun startAnimationInternal() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L / 60
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (enabled || isFadingOut) {
                        updateSnowflakes()
                        invalidate()
                    }
                }
            }
        }
        if (animator?.isRunning != true) {
            animator?.start()
        }
    }

    private fun stopAnimationInternal() {
        animator?.cancel()
        isFadingOut = false
    }

    fun setSnowEnabled(enable: Boolean) {
        if (enabled == enable && !enable) return
        enabled = enable
        if (enable) {
            // 启用：取消任何淡出，清空现有雪花，重新初始化
            isFadingOut = false
            fadeOutCallback = null
            if (viewWidth > 0 && viewHeight > 0) {
                initSnowflakes()
            } else {
                snowflakes.clear()
            }
            startAnimationInternal()
        } else {
            // 禁用：如果还有雪花，开始淡出
            if (snowflakes.isNotEmpty()) {
                isFadingOut = true
                if (animator?.isRunning != true) startAnimationInternal()
            } else {
                stopAnimationInternal()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimationInternal()
    }
}