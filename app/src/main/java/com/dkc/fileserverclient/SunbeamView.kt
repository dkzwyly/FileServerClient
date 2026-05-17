package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class SunbeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 光源位置（左上角）
    private var lightX = 0f
    private var lightY = 0f

    // 光束参数
    private val beamCount = 16                     // 16条光束均匀覆盖360°
    private var maxLength = 800f
    private val nearWidth = 40f
    private val farWidth = 250f

    private val angles = mutableListOf<Float>()
    private val beamPaths = mutableListOf<Path>()
    private val paints = mutableListOf<Paint>()

    private var animator: ValueAnimator? = null
    private var currentRotation = 0f

    private var viewWidth = 0
    private var viewHeight = 0
    private val density = context.resources.displayMetrics.density

    init {
        for (i in 0 until beamCount) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                val color = when (i % 3) {
                    0 -> Color.parseColor("#EEFFD966")
                    1 -> Color.parseColor("#DDFFB347")
                    else -> Color.parseColor("#CCFFA500")
                }
                this.color = color
                alpha = 200
            }
            paints.add(paint)
            beamPaths.add(Path())
        }

        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h

        lightX = 40f * density
        lightY = 60f * density

        val dx = viewWidth - lightX
        val dy = viewHeight - lightY
        maxLength = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat() * 1.1f

        angles.clear()
        val step = 360f / beamCount
        for (i in 0 until beamCount) {
            angles.add(i * step)
        }

        buildAllBeamPaths()
        startRotationAnimation()
    }

    private fun buildAllBeamPaths() {
        for (i in 0 until beamCount) {
            val path = beamPaths[i]
            path.reset()
            val angleDeg = angles[i]
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            val nearHalf = nearWidth / 2f
            val perpX = -sinA
            val perpY = cosA
            val x1 = lightX + perpX * nearHalf
            val y1 = lightY + perpY * nearHalf
            val x2 = lightX - perpX * nearHalf
            val y2 = lightY - perpY * nearHalf

            val farHalf = farWidth / 2f
            val farX = lightX + cosA * maxLength
            val farY = lightY + sinA * maxLength
            val x3 = farX + perpX * farHalf
            val y3 = farY + perpY * farHalf
            val x4 = farX - perpX * farHalf
            val y4 = farY - perpY * farHalf

            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            path.lineTo(x4, y4)
            path.lineTo(x3, y3)
            path.close()
        }
    }

    private fun startRotationAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 45000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                currentRotation = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth == 0) return

        canvas.save()
        canvas.rotate(currentRotation, lightX, lightY)
        for (i in 0 until beamCount) {
            canvas.drawPath(beamPaths[i], paints[i])
        }
        canvas.restore()
    }

    fun startAnimation() {
        if (animator?.isRunning != true) {
            startRotationAnimation()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}