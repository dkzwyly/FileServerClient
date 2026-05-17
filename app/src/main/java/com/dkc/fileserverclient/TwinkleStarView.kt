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

class TwinkleStarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Star(
        var x: Float,
        var y: Float,
        var size: Float,
        var alpha: Int,
        var speedX: Float,
        var speedY: Float,
        var rotation: Float,
        var rotSpeed: Float,
        var twinklePhase: Float,
        var scale: Float = 1f
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF9C4")  // 亮金黄色（原来是 #E1BEE7 淡紫色）
    }
    private val stars = mutableListOf<Star>()
    private var animator: ValueAnimator? = null
    private var isFadingOut = false
    private var fadeOutCallback: (() -> Unit)? = null
    private var enabled = false

    // 修改：增加星星数量，增大尺寸范围，提高透明度
    private val starCount = 40               // 原来 28
    private val minSize = 6f                 // 原来 4f
    private val maxSize = 16f                // 原来 10f
    private val minSpeed = 0.05f
    private val maxSpeed = 0.2f
    private val rotSpeedRange = 1f
    private val minAlpha = 180               // 原来 100，提高最小亮度
    private val maxAlpha = 255               // 原来 220，提高最大亮度

    private var viewWidth = 0
    private var viewHeight = 0
    private var density = 1f
    private val starPath = Path()

    init {
        density = resources.displayMetrics.density
        buildStarPath()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun buildStarPath() {
        starPath.reset()
        val outerRadius = 1f
        val innerRadius = 0.4f
        val points = 5
        val angleStep = 360.0 / points
        for (i in 0 until points * 2) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = Math.toRadians(i * angleStep / 2).toFloat()
            val x = radius * cos(angle)
            val y = radius * sin(angle)
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (enabled && stars.isEmpty()) initStars()
    }

    private fun initStars() {
        stars.clear()
        for (i in 0 until starCount) {
            val size = (minSize + Random.nextFloat() * (maxSize - minSize)) * density
            val speedX = (Random.nextFloat() - 0.5f) * maxSpeed * density
            val speedY = (Random.nextFloat() - 0.5f) * maxSpeed * density
            val alpha = minAlpha + Random.nextInt(maxAlpha - minAlpha)
            val rotation = Random.nextFloat() * 360f
            val rotSpeed = (Random.nextFloat() - 0.5f) * rotSpeedRange
            val twinklePhase = Random.nextFloat() * 360f
            stars.add(Star(
                x = Random.nextFloat() * viewWidth,
                y = Random.nextFloat() * viewHeight,
                size = size,
                alpha = alpha,
                speedX = speedX,
                speedY = speedY,
                rotation = rotation,
                rotSpeed = rotSpeed,
                twinklePhase = twinklePhase
            ))
        }
    }

    private fun updateStars() {
        val iterator = stars.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            s.x += s.speedX
            s.y += s.speedY
            s.rotation += s.rotSpeed
            if (!isFadingOut) {
                if (s.x < -s.size * 2) s.x = viewWidth + s.size * 2
                if (s.x > viewWidth + s.size * 2) s.x = -s.size * 2
                if (s.y < -s.size * 2) s.y = viewHeight + s.size * 2
                if (s.y > viewHeight + s.size * 2) s.y = -s.size * 2
            }
            if (isFadingOut) {
                s.scale *= 0.94f
                if (s.scale <= 0.05f) iterator.remove()
            }
        }
        if (isFadingOut && stars.isEmpty()) {
            stopAnimationInternal()
            fadeOutCallback?.invoke()
            fadeOutCallback = null
            isFadingOut = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        val time = System.currentTimeMillis() % 3000 / 3000f * 360f
        for (s in stars) {
            // 闪烁效果：正弦变化，调整亮度范围使星星更明显（最低亮度从0.5提高到0.7）
            val twinkle = (sin(Math.toRadians((s.twinklePhase + time).toDouble())) * 0.5 + 0.5).toFloat()
            // 修改：让暗部不那么暗 (0.7 ~ 1.0 范围)
            val finalAlpha = (s.alpha * s.scale * (0.7f + twinkle * 0.3f)).toInt().coerceIn(0, 255)
            paint.alpha = finalAlpha
            canvas.save()
            canvas.translate(s.x, s.y)
            canvas.rotate(s.rotation)
            canvas.scale(s.size * s.scale, s.size * s.scale)
            canvas.drawPath(starPath, paint)
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
                        updateStars()
                        invalidate()
                    }
                }
            }
        }
        if (animator?.isRunning != true) animator?.start()
    }

    private fun stopAnimationInternal() {
        animator?.cancel()
        isFadingOut = false
    }

    fun setStarEnabled(enable: Boolean) {
        if (enabled == enable && !enable) return
        enabled = enable
        if (enable) {
            isFadingOut = false
            fadeOutCallback = null
            if (viewWidth > 0 && viewHeight > 0) initStars()
            else stars.clear()
            startAnimationInternal()
        } else {
            if (stars.isNotEmpty()) {
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