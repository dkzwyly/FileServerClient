package com.dkc.fileserverclient

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class ShineAnimationSimple : PlayingAnimation {
    private val animatorMap = mutableMapOf<View, Animator>()

    override fun start(target: View) {
        if (isRunning(target)) return

        val shineDrawable = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var gradient: LinearGradient? = null

            override fun draw(canvas: Canvas) {
                gradient?.let {
                    paint.shader = it
                    canvas.drawRect(bounds, paint)
                }
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        target.foreground = shineDrawable

        val width = target.width
        if (width <= 0) {
            target.post { start(target) }
            return
        }

        // 扫光区域宽度占 View 宽度的 60%
        val shineWidth = width * 0.6f
        // 移动范围：从 -shineWidth 到 width（完全移出右侧）
        val startOffset = -shineWidth
        val endOffset = width.toFloat()

        val anim = ObjectAnimator.ofFloat(0f, 1f).apply {
            duration = 2500   // 变慢，更柔和
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val currentCenter = startOffset + (endOffset - startOffset) * progress
                val startX = currentCenter - shineWidth / 2
                val endX = currentCenter + shineWidth / 2

                val gradient = LinearGradient(
                    startX.coerceAtLeast(0f), 0f,
                    endX.coerceAtMost(width.toFloat()), 0f,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.argb(80, 255, 255, 255),  // 半透白光，强度降低更自然
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                shineDrawable.gradient = gradient
                target.invalidate()
            }
        }
        anim.start()
        animatorMap[target] = anim
    }

    override fun stop(target: View) {
        animatorMap[target]?.cancel()
        animatorMap.remove(target)
        target.foreground = null
    }

    override fun isRunning(target: View): Boolean {
        return animatorMap[target]?.isRunning == true
    }
}