package com.dkc.fileserverclient

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

class GlowAnimation : PlayingAnimation {
    private val animatorMap = mutableMapOf<View, Animator>()

    override fun start(target: View) {
        if (isRunning(target)) return
        if (target !is ImageView) return

        // 缩放动画（模拟光晕扩散）
        val scaleX = ObjectAnimator.ofFloat(target, "scaleX", 1.0f, 1.12f, 1.0f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(target, "scaleY", 1.0f, 1.12f, 1.0f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        // 透明度动画（增强光晕感）
        val alpha = ObjectAnimator.ofFloat(target, "alpha", 1.0f, 0.7f, 1.0f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }

        val animatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
        }
        animatorSet.start()
        animatorMap[target] = animatorSet
    }

    override fun stop(target: View) {
        animatorMap[target]?.cancel()
        animatorMap.remove(target)
        target.scaleX = 1.0f
        target.scaleY = 1.0f
        target.alpha = 1.0f
    }

    override fun isRunning(target: View): Boolean {
        return animatorMap[target]?.isRunning == true
    }
}