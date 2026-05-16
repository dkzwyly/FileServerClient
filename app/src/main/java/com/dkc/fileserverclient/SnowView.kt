package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val snowflakes = mutableListOf<Snowflake>()
    private var animator: ValueAnimator? = null

    private val snowCount = 60          // 雪花数量
    private val minWidth = 2f           // 最小宽度(dp)
    private val maxWidth = 5f           // 最大宽度(dp)
    private val minHeight = 8f          // 最小高度(dp)
    private val maxHeight = 18f          // 最大高度(dp)
    private val minSpeed = 2f            // 最小速度(dp/帧)
    private val maxSpeed = 6f            // 最大速度(dp/帧)
    private val windRange = 0.5f         // 水平飘移幅度(dp/帧)

    private var viewWidth = 0
    private var viewHeight = 0

    data class Snowflake(
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var speedY: Float,
        var speedX: Float,
        var angle: Float          // 旋转角度
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null) // 硬件加速提升性能
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        initSnowflakes()
    }

    private fun initSnowflakes() {
        snowflakes.clear()
        val density = resources.displayMetrics.density
        for (i in 0 until snowCount) {
            val width = (minWidth + Random.nextFloat() * (maxWidth - minWidth)) * density
            val height = (minHeight + Random.nextFloat() * (maxHeight - minHeight)) * density
            val speedY = (minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)) * density
            val speedX = (Random.nextFloat() - 0.5f) * windRange * density
            val angle = Random.nextFloat() * 360f
            snowflakes.add(
                Snowflake(
                    x = Random.nextFloat() * viewWidth,
                    y = Random.nextFloat() * viewHeight,
                    width = width,
                    height = height,
                    speedY = speedY,
                    speedX = speedX,
                    angle = angle
                )
            )
        }
    }

    private fun updateSnowflakes() {
        for (flake in snowflakes) {
            flake.y += flake.speedY
            flake.x += flake.speedX

            // 超出底部则重置到顶部
            if (flake.y > viewHeight) {
                flake.y = -flake.height
                flake.x = Random.nextFloat() * viewWidth
            }
            // 超出左右边界则重置到对面（可选）
            if (flake.x < -flake.width) {
                flake.x = viewWidth + flake.width
            } else if (flake.x > viewWidth + flake.width) {
                flake.x = -flake.width
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (flake in snowflakes) {
            canvas.save()
            canvas.translate(flake.x + flake.width / 2, flake.y + flake.height / 2)
            canvas.rotate(flake.angle)
            canvas.drawRect(
                -flake.width / 2,
                -flake.height / 2,
                flake.width / 2,
                flake.height / 2,
                paint
            )
            canvas.restore()
        }
    }

    fun startAnimation() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L / 60   // 约60fps
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

    fun stopAnimation() {
        animator?.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}