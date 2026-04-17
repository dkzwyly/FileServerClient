package com.dkc.fileserverclient

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 音乐可视化视图（动态频谱）
 * 根据播放状态显示跳动的柱状图，模拟音乐节奏
 */
class MusicVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 柱状数量
    private val barCount = 32
    // 柱状高度数组 (0f..1f)
    private val barHeights = FloatArray(barCount) { 0.2f }
    // 目标高度（用于平滑过渡）
    private val targetHeights = FloatArray(barCount) { 0.2f }
    // 画笔
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    // 矩形区域
    private val rect = RectF()
    // 更新动画的 Handler
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    // 是否正在播放（用于决定动画是否活跃）
    private var isPlaying = false
    // 动画是否运行中
    private var isAnimating = false
    // 随机生成器（模拟频谱波动）
    private val random = Random(System.currentTimeMillis())
    // 时间因子，用于产生流动感
    private var phase = 0f

    // 颜色渐变
    private val gradientColors = intArrayOf(
        Color.parseColor("#FF6B6B"), // 红
        Color.parseColor("#FFB347"), // 橙
        Color.parseColor("#FFD93D"), // 黄
        Color.parseColor("#6BCB77"), // 绿
        Color.parseColor("#4D96FF")  // 蓝
    )
    private val gradient = LinearGradient(0f, 0f, 0f, 0f, gradientColors, null, Shader.TileMode.CLAMP)

    init {
        // 设置默认透明度，不遮挡歌词
        setBackgroundColor(Color.parseColor("#22000000"))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 更新渐变方向
        gradient.setLocalMatrix(Matrix().apply {
            setRectToRect(RectF(0f, 0f, w.toFloat(), h.toFloat()),
                RectF(0f, 0f, w.toFloat(), h.toFloat()),
                Matrix.ScaleToFit.FILL)
        })
        paint.shader = gradient
    }

    /**
     * 设置播放状态
     */
    fun setPlaying(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        if (playing) {
            startAnimation()
        } else {
            stopAnimation()
            // 暂停时高度逐渐归零
            for (i in targetHeights.indices) {
                targetHeights[i] = 0.05f
            }
            invalidate()
        }
    }

    private fun startAnimation() {
        if (isAnimating) return
        isAnimating = true
        updateRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) return
                updateHeights()
                invalidate()
                handler.postDelayed(this, 50) // 约20fps，性能友好
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopAnimation() {
        isAnimating = false
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun updateHeights() {
        if (!isPlaying) {
            // 未播放时平滑归零
            for (i in targetHeights.indices) {
                targetHeights[i] = (targetHeights[i] * 0.9f).coerceAtLeast(0.05f)
                barHeights[i] = barHeights[i] * 0.9f + targetHeights[i] * 0.1f
            }
            phase += 0.1f
            return
        }

        // 模拟频谱跳动：使用正弦波 + 随机噪声，产生音乐律动感
        phase += 0.2f
        for (i in 0 until barCount) {
            // 基础正弦波，频率与柱索引相关
            val sinVal = (sin(phase + i * 0.3f) + 1f) / 2f  // 范围 0..1
            // 随机扰动
            val noise = (random.nextFloat() * 0.6f + 0.2f)
            // 组合高度，并确保播放时有较大起伏
            val rawHeight = (sinVal * 0.7f + noise * 0.3f) * 0.9f + 0.1f
            // 根据当前时间微调，增强动态感
            val target = rawHeight.coerceIn(0.2f, 1f)
            targetHeights[i] = target
            // 平滑过渡
            barHeights[i] = barHeights[i] * 0.7f + targetHeights[i] * 0.3f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width == 0 || height == 0) return

        val barWidth = width.toFloat() / barCount
        val maxBarHeight = height.toFloat() * 0.7f  // 最高占视图高度的70%
        val bottom = height.toFloat()

        for (i in 0 until barCount) {
            val left = i * barWidth + barWidth * 0.1f
            val right = (i + 1) * barWidth - barWidth * 0.1f
            if (right <= left) continue

            val barHeight = barHeights[i] * maxBarHeight
            val top = bottom - barHeight
            rect.set(left, top, right, bottom)

            // 根据高度改变颜色深浅
            val intensity = (0.5f + barHeights[i] * 0.5f).coerceIn(0.5f, 1f)
            paint.alpha = (150 * intensity).toInt()
            canvas.drawRoundRect(rect, 6f, 6f, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}