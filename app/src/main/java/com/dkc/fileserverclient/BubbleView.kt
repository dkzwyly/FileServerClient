package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.random.Random

class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Bubble(
        var x: Float,
        var y: Float,
        var radius: Float,
        var alpha: Int,
        var speedY: Float,
        var speedX: Float,
        var scale: Float = 1f
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AAE0F7FA")
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFFFF")
    }
    private val bubbles = mutableListOf<Bubble>()
    private var animator: ValueAnimator? = null
    private var isFadingOut = false
    private var fadeOutCallback: (() -> Unit)? = null
    private var enabled = false

    private val bubbleCount = 14
    private val minRadius = 8f
    private val maxRadius = 22f
    private val minSpeed = 0.2f
    private val maxSpeed = 0.6f
    private val swayRange = 0.2f
    private val minAlpha = 120
    private val maxAlpha = 200

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
        if (enabled && bubbles.isEmpty()) initBubbles()
    }

    private fun initBubbles() {
        bubbles.clear()
        for (i in 0 until bubbleCount) {
            val radius = (minRadius + Random.nextFloat() * (maxRadius - minRadius)) * density
            val speedY = -(minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)) * density // 向上
            val speedX = (Random.nextFloat() - 0.5f) * swayRange * density
            val alpha = minAlpha + Random.nextInt(maxAlpha - minAlpha)
            bubbles.add(Bubble(
                x = Random.nextFloat() * viewWidth,
                y = Random.nextFloat() * viewHeight,
                radius = radius,
                alpha = alpha,
                speedY = speedY,
                speedX = speedX
            ))
        }
    }

    private fun updateBubbles() {
        val iterator = bubbles.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            b.y += b.speedY
            b.x += b.speedX
            if (!isFadingOut) {
                if (b.y + b.radius < 0) {
                    b.y = viewHeight + b.radius
                    b.x = Random.nextFloat() * viewWidth
                }
                if (b.x < -b.radius * 2) b.x = viewWidth + b.radius * 2
                else if (b.x > viewWidth + b.radius * 2) b.x = -b.radius * 2
            }
            if (isFadingOut) {
                b.scale *= 0.94f
                if (b.scale <= 0.05f) iterator.remove()
            }
        }
        if (isFadingOut && bubbles.isEmpty()) {
            stopAnimationInternal()
            fadeOutCallback?.invoke()
            fadeOutCallback = null
            isFadingOut = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (b in bubbles) {
            paint.alpha = (b.alpha * b.scale).toInt().coerceIn(0, 255)
            val radius = b.radius * b.scale
            canvas.drawCircle(b.x, b.y, radius, paint)
            // 绘制高光
            highlightPaint.alpha = (120 * b.scale).toInt().coerceIn(0, 255)
            canvas.drawCircle(b.x - radius * 0.3f, b.y - radius * 0.3f, radius * 0.25f, highlightPaint)
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
                        updateBubbles()
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

    fun setBubbleEnabled(enable: Boolean) {
        if (enabled == enable && !enable) return
        enabled = enable
        if (enable) {
            isFadingOut = false
            fadeOutCallback = null
            if (viewWidth > 0 && viewHeight > 0) initBubbles()
            else bubbles.clear()
            startAnimationInternal()
        } else {
            if (bubbles.isNotEmpty()) {
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