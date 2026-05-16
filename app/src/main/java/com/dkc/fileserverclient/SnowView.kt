package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
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
        var width: Float,
        var height: Float,
        var alpha: Int,           // 透明度 0-255
        var speedY: Float,
        var speedX: Float,
        var rotation: Float,
        var scale: Float = 1f     // 淡出时缩小用
    )

    private val snowflakes = mutableListOf<Snowflake>()
    private var animator: ValueAnimator? = null
    private var isFadingOut = false
    private var fadeOutCallback: (() -> Unit)? = null

    // 参数调优（更真实、更少、更慢）
    private val snowCount = 24                 // 减少数量
    private val minWidth = 2f                  // dp
    private val maxWidth = 4f
    private val minHeight = 6f
    private val maxHeight = 14f
    private val minSpeed = 0.8f                // 降低速度
    private val maxSpeed = 2.2f
    private val windRange = 0.3f               // 轻微水平飘移
    private val minAlpha = 160                 // 半透明
    private val maxAlpha = 220

    private var viewWidth = 0
    private var viewHeight = 0
    private var density = 1f

    init {
        density = resources.displayMetrics.density
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        initSnowflakes()
    }

    private fun initSnowflakes() {
        snowflakes.clear()
        for (i in 0 until snowCount) {
            val width = (minWidth + Random.nextFloat() * (maxWidth - minWidth)) * density
            val height = (minHeight + Random.nextFloat() * (maxHeight - minHeight)) * density
            val speedY = (minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)) * density
            val speedX = (Random.nextFloat() - 0.5f) * windRange * density
            val alpha = (minAlpha + Random.nextInt(maxAlpha - minAlpha)).coerceIn(0, 255)
            val rotation = Random.nextFloat() * 360f
            snowflakes.add(
                Snowflake(
                    x = Random.nextFloat() * viewWidth,
                    y = Random.nextFloat() * viewHeight,
                    width = width,
                    height = height,
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
        for (flake in snowflakes) {
            flake.y += flake.speedY
            flake.x += flake.speedX
            // 边缘重置（无淡出时）
            if (!isFadingOut) {
                if (flake.y > viewHeight) {
                    flake.y = -flake.height
                    flake.x = Random.nextFloat() * viewWidth
                }
                if (flake.x < -flake.width) {
                    flake.x = viewWidth + flake.width
                } else if (flake.x > viewWidth + flake.width) {
                    flake.x = -flake.width
                }
            }
            // 淡出时逐步缩小
            if (isFadingOut) {
                flake.scale *= 0.94f   // 逐渐缩小
                if (flake.scale <= 0.05f) flake.scale = 0f
            }
        }
        // 淡出完成后清除所有雪花并停止动画
        if (isFadingOut && snowflakes.all { it.scale <= 0.01f }) {
            snowflakes.clear()
            stopAnimationInternal()
            fadeOutCallback?.invoke()
            fadeOutCallback = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (flake in snowflakes) {
            if (flake.scale <= 0f) continue
            paint.alpha = (flake.alpha * flake.scale).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(flake.x + flake.width / 2, flake.y + flake.height / 2)
            canvas.rotate(flake.rotation)
            canvas.scale(flake.scale, flake.scale)
            // 绘制圆角矩形，更像冰晶
            val halfW = flake.width / 2
            val halfH = flake.height / 2
            canvas.drawRoundRect(
                -halfW, -halfH, halfW, halfH,
                halfW * 0.3f, halfH * 0.3f,
                paint
            )
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
                    updateSnowflakes()
                    invalidate()
                }
            }
        }
        if (!(animator?.isRunning == true)) {
            animator?.start()
        }
    }

    private fun stopAnimationInternal() {
        animator?.cancel()
        isFadingOut = false
    }

    fun startAnimation() {
        if (snowflakes.isEmpty() && viewWidth > 0) initSnowflakes()
        isFadingOut = false
        startAnimationInternal()
    }

    fun stopAnimation() {
        stopAnimationInternal()
        snowflakes.clear()
    }

    fun stopWithFade(callback: (() -> Unit)? = null) {
        if (snowflakes.isEmpty()) {
            callback?.invoke()
            return
        }
        fadeOutCallback = callback
        isFadingOut = true
        // 如果动画未运行，启动它以便执行淡出
        if (animator?.isRunning != true) startAnimationInternal()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimationInternal()
    }
}