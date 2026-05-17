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

class FloatingPetalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Petal(
        var x: Float,                // X坐标
        var y: Float,                // Y坐标
        var size: Float,             // 大小
        var alpha: Int,              // 透明度
        var speedY: Float,           // 下落速度
        var speedX: Float,           // 水平飘移速度
        var rotation: Float,         // 当前旋转角度
        var rotSpeed: Float,         // 旋转速度
        var swingPhase: Float,       // 摆动相位
        var swingAmp: Float,         // 摆动幅度
        var scale: Float = 1f        // 淡出缩放
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val petals = mutableListOf<Petal>()
    private var animator: ValueAnimator? = null
    private var isFadingOut = false
    private var fadeOutCallback: (() -> Unit)? = null
    private var enabled = false

    private val petalCount = 18               // 花瓣数量，足够丰富但不卡顿
    private val minSize = 12f                 // 最小尺寸 (dp)
    private val maxSize = 22f                 // 最大尺寸 (dp)
    private val minSpeed = 0.6f               // 最小下落速度 (dp/帧)
    private val maxSpeed = 1.2f               // 最大下落速度
    private val baseSwayRange = 0.4f           // 基础水平飘移范围 (dp/帧)
    private val minAlpha = 180                 // 最小透明度
    private val maxAlpha = 255                 // 最大透明度
    private val rotSpeedRange = 2.5f           // 旋转速度范围 (±度/帧)

    private var viewWidth = 0
    private var viewHeight = 0
    private var density = 1f

    // 花瓣路径 (贝塞尔曲线绘制)
    private val petalPath = Path()

    init {
        density = resources.displayMetrics.density
        buildSakuraPetalPath()
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * 绘制一片真实的樱花/桃花花瓣形状 (使用三次贝塞尔曲线)
     * 形状：顶端尖，底部圆润，两侧弧形
     */
    private fun buildSakuraPetalPath() {
        petalPath.reset()
        // 控制点相对坐标 (以花瓣中心为原点，长度方向为Y轴，宽度方向为X轴)
        // 花瓣总长 = 2，总宽 ≈ 1.6
        val topX = 0f
        val topY = -1f          // 尖端
        val bottomX = 0f
        val bottomY = 0.8f      // 基部

        // 右侧控制点
        val rightTopCtrlX = 0.6f
        val rightTopCtrlY = -0.7f
        val rightMidCtrlX = 0.8f
        val rightMidCtrlY = 0f
        val rightBottomCtrlX = 0.3f
        val rightBottomCtrlY = 0.6f

        // 左侧控制点 (镜像)
        val leftTopCtrlX = -0.6f
        val leftTopCtrlY = -0.7f
        val leftMidCtrlX = -0.8f
        val leftMidCtrlY = 0f
        val leftBottomCtrlX = -0.3f
        val leftBottomCtrlY = 0.6f

        petalPath.moveTo(topX, topY)
        // 右侧曲线
        petalPath.cubicTo(rightTopCtrlX, rightTopCtrlY,
            rightMidCtrlX, rightMidCtrlY,
            bottomX, bottomY)
        // 左侧曲线返回尖端
        petalPath.cubicTo(leftBottomCtrlX, leftBottomCtrlY,
            leftMidCtrlX, leftMidCtrlY,
            topX, topY)

        petalPath.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        if (enabled && petals.isEmpty()) {
            initPetals()
        }
    }

    private fun initPetals() {
        petals.clear()
        for (i in 0 until petalCount) {
            val size = (minSize + Random.nextFloat() * (maxSize - minSize)) * density
            val speedY = (minSpeed + Random.nextFloat() * (maxSpeed - minSpeed)) * density
            val speedX = (Random.nextFloat() - 0.5f) * baseSwayRange * density
            val alpha = (minAlpha + Random.nextInt(maxAlpha - minAlpha)).coerceIn(0, 255)
            val rotation = Random.nextFloat() * 360f
            val rotSpeed = (Random.nextFloat() - 0.5f) * rotSpeedRange
            val swingPhase = Random.nextFloat() * 360f
            val swingAmp = 0.8f + Random.nextFloat() * 1.2f  // 摆动幅度系数

            petals.add(
                Petal(
                    x = Random.nextFloat() * viewWidth,
                    y = Random.nextFloat() * viewHeight,
                    size = size,
                    alpha = alpha,
                    speedY = speedY,
                    speedX = speedX,
                    rotation = rotation,
                    rotSpeed = rotSpeed,
                    swingPhase = swingPhase,
                    swingAmp = swingAmp
                )
            )
        }
    }

    private fun updatePetals() {
        val iterator = petals.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            // 动态水平飘移：基于正弦摆动
            val swing = sin(Math.toRadians((p.swingPhase + System.currentTimeMillis() % 3000 / 3000f * 360).toDouble())).toFloat()
            val deltaX = swing * p.swingAmp * 0.8f
            p.x += p.speedX + deltaX * 0.5f
            p.y += p.speedY
            p.rotation += p.rotSpeed

            if (!isFadingOut) {
                // 超出边界则重置到顶部
                if (p.y > viewHeight + p.size) {
                    p.y = -p.size * 2
                    p.x = Random.nextFloat() * viewWidth
                }
                // 水平边界环绕 (可选，让花瓣从另一边飘入)
                if (p.x < -p.size) {
                    p.x = viewWidth + p.size
                } else if (p.x > viewWidth + p.size) {
                    p.x = -p.size
                }
            }

            if (isFadingOut) {
                p.scale *= 0.94f
                if (p.scale <= 0.05f) {
                    iterator.remove()
                }
            }
        }

        if (isFadingOut && petals.isEmpty()) {
            stopAnimationInternal()
            fadeOutCallback?.invoke()
            fadeOutCallback = null
            isFadingOut = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (p in petals) {
            val alpha = (p.alpha * p.scale).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, 255, 182, 193) // 固定粉红色
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)
            canvas.scale(p.size * p.scale, p.size * p.scale)
            canvas.drawPath(petalPath, paint)
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
                    if (enabled || isFadingOut) {
                        updatePetals()
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

    fun setPetalEnabled(enable: Boolean) {
        if (enabled == enable && !enable) return
        enabled = enable
        if (enable) {
            isFadingOut = false
            fadeOutCallback = null
            if (viewWidth > 0 && viewHeight > 0) {
                initPetals()
            } else {
                petals.clear()
            }
            startAnimationInternal()
        } else {
            if (petals.isNotEmpty()) {
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