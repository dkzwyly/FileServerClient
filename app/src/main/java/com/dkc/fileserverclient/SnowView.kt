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
        var size: Float,          // 外接圆半径（dp）
        var alpha: Int,           // 透明度 0-255
        var speedY: Float,        // 垂直速度（px/帧）
        var speedX: Float,        // 水平飘移速度
        var rotation: Float,      // 当前旋转角度
        var scale: Float = 1f     // 淡出缩放
    )

    private val snowflakes = mutableListOf<Snowflake>()
    private var animator: ValueAnimator? = null
    private var isFadingOut = false
    private var fadeOutCallback: (() -> Unit)? = null

    // 雪花形状的 Path（六角星形）
    private val snowflakePath = Path()
    private val pointsCount = 6   // 六个臂

    // 参数调节（更慢、更少、更真实）
    private val snowCount = 18                // 雪花数量（减少以提升性能）
    private val minSize = 6f                  // 最小半径 (dp)
    private val maxSize = 14f                 // 最大半径 (dp)
    private val minSpeed = 0.4f               // 更慢：0.4 dp/帧
    private val maxSpeed = 1.0f               // 更慢：1.0 dp/帧
    private val windRange = 0.2f              // 轻微水平飘移 (dp/帧)
    private val minAlpha = 140                // 半透
    private val maxAlpha = 220

    private var viewWidth = 0
    private var viewHeight = 0
    private var density = 1f

    init {
        density = resources.displayMetrics.density
        buildSnowflakePath()   // 预构建路径
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 构建一个六角星形雪花路径（中心在原点，半径为 1）
     */
    private fun buildSnowflakePath() {
        snowflakePath.reset()
        val angleStep = 360.0 / pointsCount   // 60度
        val outerRadius = 1f
        val innerRadius = 0.4f                // 内凹比例，形成星形

        for (i in 0 until pointsCount) {
            val angle1 = Math.toRadians(i * angleStep).toFloat()
            val x1 = outerRadius * cos(angle1)
            val y1 = outerRadius * sin(angle1)

            val angle2 = Math.toRadians(i * angleStep + angleStep / 2).toFloat()
            val x2 = innerRadius * cos(angle2)
            val y2 = innerRadius * sin(angle2)

            if (i == 0) {
                snowflakePath.moveTo(x1, y1)
            } else {
                snowflakePath.lineTo(x1, y1)
            }
            snowflakePath.lineTo(x2, y2)
        }
        snowflakePath.close()
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
        for (flake in snowflakes) {
            flake.y += flake.speedY
            flake.x += flake.speedX
            // 边界重置（仅在未淡出时）
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
            // 淡出时逐渐缩小
            if (isFadingOut) {
                flake.scale *= 0.95f
                if (flake.scale < 0.01f) flake.scale = 0f
            }
        }
        // 淡出完成后清除
        if (isFadingOut && snowflakes.all { it.scale <= 0f }) {
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
                duration = 1000L / 60   // 约60fps
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    updateSnowflakes()
                    invalidate()
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
        if (animator?.isRunning != true) startAnimationInternal()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimationInternal()
    }
}