package com.dkc.fileserverclient

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout

class BackgroundEffectManager(private val activity: Activity) {

    companion object {
        private const val TAG = "EffectManager"
        const val GRADIENT_PINK = 0
        const val GRADIENT_COLD_BLUE = 1
        const val GRADIENT_CYAN = 2
        const val GRADIENT_PURPLE = 3
        const val GRADIENT_SUNSET_ORANGE = 4
        const val GRADIENT_CLASSIC_WHITE = 5
    }

    private var snowView: SnowView? = null
    private var sunbeamView: SunbeamView? = null
    private var petalView: FloatingPetalView? = null
    private var bubbleView: BubbleView? = null
    private var starView: TwinkleStarView? = null

    private var sunbeamContainer: FrameLayout? = null   // 底层容器（阳光）
    private var snowContainer: FrameLayout? = null      // 上层容器（雪花、花瓣、气泡、星星）

    private var currentGradientIndex = -1

    fun attachTo(rootView: ViewGroup) {
        if (sunbeamContainer == null) {
            sunbeamContainer = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isClickable = false
                isFocusable = false
                setBackgroundColor(0x00000000)
            }
            rootView.addView(sunbeamContainer, 0)
            Log.d(TAG, "阳光容器已添加")
        }

        if (snowContainer == null) {
            snowContainer = FrameLayout(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isClickable = false
                isFocusable = false
                setBackgroundColor(0x00000000)
            }
            rootView.addView(snowContainer)
            Log.d(TAG, "特效容器已添加")
        }
    }

    fun applyEffectForGradient(gradientIndex: Int) {
        if (currentGradientIndex == gradientIndex) return
        currentGradientIndex = gradientIndex
        Log.d(TAG, "切换特效，渐变索引: $gradientIndex")

        // 先禁用所有特效
        enableSnow(false)
        enableSunbeam(false)
        enablePetal(false)
        enableBubble(false)
        enableStar(false)

        when (gradientIndex) {
            GRADIENT_COLD_BLUE -> enableSnow(true)
            GRADIENT_SUNSET_ORANGE -> enableSunbeam(true)
            GRADIENT_PINK -> enablePetal(true)
            GRADIENT_CYAN -> enableBubble(true)
            GRADIENT_PURPLE -> enableStar(true)
            GRADIENT_CLASSIC_WHITE -> {
                // 纯白背景，不启用任何特效
            }
        }
    }

    private fun ensureSnowView(): SnowView {
        if (snowView == null) {
            snowView = SnowView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return snowView!!
    }

    private fun ensureSunbeamView(): SunbeamView {
        if (sunbeamView == null) {
            sunbeamView = SunbeamView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return sunbeamView!!
    }

    private fun ensurePetalView(): FloatingPetalView {
        if (petalView == null) {
            petalView = FloatingPetalView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return petalView!!
    }

    private fun ensureBubbleView(): BubbleView {
        if (bubbleView == null) {
            bubbleView = BubbleView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return bubbleView!!
    }

    private fun ensureStarView(): TwinkleStarView {
        if (starView == null) {
            starView = TwinkleStarView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return starView!!
    }

    private fun enableSnow(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureSnowView()
            if (view.parent == null) container.addView(view)
            view.setSnowEnabled(true)
        } else {
            snowView?.let {
                it.setSnowEnabled(false)
                container.removeView(it)
                snowView = null
            }
        }
    }

    private fun enableSunbeam(enable: Boolean) {
        val container = sunbeamContainer ?: return
        if (enable) {
            val view = ensureSunbeamView()
            if (view.parent == null) container.addView(view)
            view.startAnimation()
        } else {
            sunbeamView?.let {
                it.stopAnimation()
                container.removeView(it)
                sunbeamView = null
            }
        }
    }

    private fun enablePetal(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensurePetalView()
            if (view.parent == null) container.addView(view)
            view.setPetalEnabled(true)
        } else {
            petalView?.let {
                it.setPetalEnabled(false)
                container.removeView(it)
                petalView = null
            }
        }
    }

    private fun enableBubble(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureBubbleView()
            if (view.parent == null) container.addView(view)
            view.setBubbleEnabled(true)
        } else {
            bubbleView?.let {
                it.setBubbleEnabled(false)
                container.removeView(it)
                bubbleView = null
            }
        }
    }

    private fun enableStar(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureStarView()
            if (view.parent == null) container.addView(view)
            view.setStarEnabled(true)
        } else {
            starView?.let {
                it.setStarEnabled(false)
                container.removeView(it)
                starView = null
            }
        }
    }

    fun onResume() {
        when (currentGradientIndex) {
            GRADIENT_COLD_BLUE -> snowView?.setSnowEnabled(true)
            GRADIENT_SUNSET_ORANGE -> sunbeamView?.startAnimation()
            GRADIENT_PINK -> petalView?.setPetalEnabled(true)
            GRADIENT_CYAN -> bubbleView?.setBubbleEnabled(true)
            GRADIENT_PURPLE -> starView?.setStarEnabled(true)
        }
    }

    fun onPause() {
        snowView?.setSnowEnabled(false)
        sunbeamView?.stopAnimation()
        petalView?.setPetalEnabled(false)
        bubbleView?.setBubbleEnabled(false)
        starView?.setStarEnabled(false)
    }

    fun onDestroy() {
        snowView?.setSnowEnabled(false)
        sunbeamView?.stopAnimation()
        petalView?.setPetalEnabled(false)
        bubbleView?.setBubbleEnabled(false)
        starView?.setStarEnabled(false)

        snowContainer?.removeAllViews()
        sunbeamContainer?.removeAllViews()
        (snowContainer?.parent as? ViewGroup)?.removeView(snowContainer)
        (sunbeamContainer?.parent as? ViewGroup)?.removeView(sunbeamContainer)
        snowContainer = null
        sunbeamContainer = null
        snowView = null
        sunbeamView = null
        petalView = null
        bubbleView = null
        starView = null
    }
}