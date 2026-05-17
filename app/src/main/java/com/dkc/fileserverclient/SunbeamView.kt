package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SunbeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lightX = 0f
    private var lightY = 0f
    private var maxLength = 800f

    // 光束数量：增加使过渡更平滑
    private val beamCount = 32
    // 近端宽度（光源处）设为0，让光束从点发散更自然
    private val nearWidth = 0f
    private val farWidth = 180f   // 远端宽度适当减小，避免太粗

    private val angles = mutableListOf<Float>()
    private val beamPaths = mutableListOf<Path>()
    private val paints = mutableListOf<Paint>()

    // 颜色池：暖色渐变起始色（亮金黄 -> 橙红）
    private val startColors = listOf(
        Color.parseColor("#CCFFD700"),  // 金黄
        Color.parseColor("#CCFFB347"),  // 橘黄
        Color.parseColor("#CCFF8C00")   // 深橙
    )
    // 远端完全透明
    private val endColor = Color.TRANSPARENT

    private var animator: ValueAnimator? = null
    private var currentRotation = 0f

    // 可选：动态脉冲的透明度/亮度因子
    private var intensityFactor = 1f
    private var pulseAnimator: ValueAnimator? = null

    private var viewWidth = 0
    private var viewHeight = 0
    private val density = context.resources.displayMetrics.density

    init {
        for (i in 0 until beamCount) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                // 启用硬件加速下可用的混合模式，模拟光晕叠加
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
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

        // 光源位置：左上角留一点边距
        lightX = 50f * density
        lightY = 70f * density

        val dx = viewWidth - lightX
        val dy = viewHeight - lightY
        maxLength = sqrt(dx * dx + dy * dy).toFloat() * 1.15f

        angles.clear()
        val step = 360f / beamCount
        for (i in 0 until beamCount) {
            angles.add(i * step)
        }

        buildAllBeamPaths()
        updateAllBeamGradients()   // 初始化渐变

        startRotationAnimation()
        // startPulseAnimation()     // 可选：启动脉冲效果
    }

    private fun buildAllBeamPaths() {
        for (i in 0 until beamCount) {
            val path = beamPaths[i]
            path.reset()
            val angleDeg = angles[i]
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            // 近端（光源点）宽度几乎为0 -> 四个点退化成一个点？不，我们构建一个梯形，但近端宽度为0时变成三角形
            // 为了让光束柔和，我们从光源处的一点展开成扇形，但梯形顶点重合在光源处反而看起来像三角形
            // 这里使用三角形+渐变也能达到柔和效果。如果想要轻微宽度，可保留小值。
            val nearHalf = nearWidth / 2f
            val perpX = -sinA
            val perpY = cosA

            // 近端两点（若 nearWidth=0，则两点重合于光源）
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

            // 构建四边形或三角形
            if (nearWidth == 0f) {
                // 三角形：光源点 -> 远端两个端点
                path.moveTo(lightX, lightY)
                path.lineTo(x3, y3)
                path.lineTo(x4, y4)
            } else {
                path.moveTo(x1, y1)
                path.lineTo(x2, y2)
                path.lineTo(x4, y4)
                path.lineTo(x3, y3)
            }
            path.close()
        }
    }

    private fun updateAllBeamGradients() {
        for (i in 0 until beamCount) {
            val angleDeg = angles[i]
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val farX = lightX + cosA * maxLength
            val farY = lightY + sinA * maxLength

            // 选择颜色：根据角度取模让相邻光束有细微色差，但整体融合
            val colorIndex = (i / 4) % startColors.size
            val startColor = startColors[colorIndex]

            // 渐变从光源位置到远端，透明度从完全不透明到完全透明
            val shader = LinearGradient(
                lightX, lightY,
                farX, farY,
                startColor, endColor,
                Shader.TileMode.CLAMP
            )
            paints[i].shader = shader
            // 基础alpha动态调整（脉冲时会重设）, 但渐变已经控制了透明度，这里不需要额外alpha
            paints[i].alpha = 255   // 让渐变完全决定透明度
        }
    }

    // 可选：整体透明度/亮度随正弦波动，产生“呼吸”感
    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                intensityFactor = animation.animatedValue as Float
                // 动态调整每条光束的渐变alpha强度？简单起见调整整体透明度或重新生成渐变
                for (paint in paints) {
                    // 方式1：直接修改alpha（但渐变中的alpha会被覆盖，所以需要重新生成渐变）
                    // 方式2：重新生成渐变并传入新的起始颜色alpha
                    // 这里重新生成所有渐变（性能可接受，32条）
                    updateAllBeamGradientsWithIntensity(intensityFactor)
                }
                invalidate()
            }
            start()
        }
    }

    private fun updateAllBeamGradientsWithIntensity(factor: Float) {
        for (i in 0 until beamCount) {
            val angleDeg = angles[i]
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val farX = lightX + cosA * maxLength
            val farY = lightY + sinA * maxLength

            val colorIndex = (i / 4) % startColors.size
            // 提取原始颜色RGB，保留原色相，仅调节alpha
            val originalColor = startColors[colorIndex]
            val alpha = (Color.alpha(originalColor) * factor).toInt().coerceIn(0, 255)
            val startColor = Color.argb(
                alpha,
                Color.red(originalColor),
                Color.green(originalColor),
                Color.blue(originalColor)
            )
            val shader = LinearGradient(
                lightX, lightY, farX, farY,
                startColor, endColor,
                Shader.TileMode.CLAMP
            )
            paints[i].shader = shader
        }
    }

    private fun startRotationAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 60000L   // 60秒一圈，更舒缓
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

        // 绘制背景光晕：在光源处绘制径向渐变，柔和的光晕
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                lightX, lightY, maxLength * 0.3f,
                Color.argb(100, 255, 200, 100),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawCircle(lightX, lightY, maxLength * 0.4f, glowPaint)

        canvas.save()
        canvas.rotate(currentRotation, lightX, lightY)

        // 绘制所有光束（带渐变和混合模式）
        for (i in 0 until beamCount) {
            canvas.drawPath(beamPaths[i], paints[i])
        }
        canvas.restore()

        // 可选：再绘制一层微弱的光晕中心，增强融合
        val centerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                lightX, lightY, maxLength * 0.15f,
                Color.argb(180, 255, 255, 200),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        canvas.drawCircle(lightX, lightY, maxLength * 0.2f, centerGlow)
    }

    fun startAnimation() {
        if (animator?.isRunning != true) {
            startRotationAnimation()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}