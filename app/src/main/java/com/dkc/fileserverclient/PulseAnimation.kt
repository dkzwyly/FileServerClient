package com.dkc.fileserverclient

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class PulseAnimation : PlayingAnimation {
    private val animatorMap = mutableMapOf<View, Animator>()

    override fun start(target: View) {
        if (isRunning(target)) return

        val scaleX = ObjectAnimator.ofFloat(target, "scaleX", 1.0f, 1.25f, 1.0f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(target, "scaleY", 1.0f, 1.25f, 1.0f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alpha = ObjectAnimator.ofFloat(target, "alpha", 1.0f, 0.6f, 1.0f).apply {
            duration = 800
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