package com.dkc.fileserverclient.effect

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dkc.fileserverclient.SunbeamView

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

    // 浅色模式特效
    private var snowView: SnowView? = null            // index 1
    private var sunbeamView: SunbeamView? = null      // index 4
    private var petalView: FloatingPetalView? = null  // index 0
    private var bubbleView: BubbleView? = null        // index 2
    private var starView: TwinkleStarView? = null     // index 3

    // 深色模式特效
    private var nightFireflyView: FireflyView? = null        // index 0 → 萤火虫
    private var nightMeteorView: MeteorShowerView? = null    // index 1 → 流星
    private var nightMistView: NightMistView? = null         // index 2 → 夜雾
    private var nightStarView: TwinkleStarView? = null       // index 3 → 星空
    private var moonlightView: MoonlightView? = null         // index 4 → 月光
    // index 5 → 纯黑，无特效

    private var sunbeamContainer: FrameLayout? = null
    private var snowContainer: FrameLayout? = null

    private var currentGradientIndex = -1
    private var currentNightMode = false

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
            Log.d(TAG, "底层特效容器已添加")
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
            Log.d(TAG, "上层特效容器已添加")
        }
    }

    fun applyEffectForGradient(gradientIndex: Int, isNightMode: Boolean = false) {
        if (currentGradientIndex == gradientIndex && currentNightMode == isNightMode) return
        currentGradientIndex = gradientIndex
        currentNightMode = isNightMode
        Log.d(TAG, "切换特效 index=$gradientIndex, night=$isNightMode")

        disableAllLightEffects()
        disableAllNightEffects()

        if (isNightMode) {
            when (gradientIndex) {
                GRADIENT_PINK -> enableNightFirefly(true)      // 深粉 → 萤火虫
                GRADIENT_COLD_BLUE -> enableNightMeteor(true)  // 冷蓝 → 流星
                GRADIENT_CYAN -> enableNightMist(true)         // 深青灰 → 夜雾
                GRADIENT_PURPLE -> enableNightStar(true)       // 深紫 → 星空
                GRADIENT_SUNSET_ORANGE -> enableMoonlight(true)// 深蓝 → 月光
                GRADIENT_CLASSIC_WHITE -> { /* 纯黑，无特效 */ }
            }
        } else {
            when (gradientIndex) {
                GRADIENT_PINK -> enablePetal(true)
                GRADIENT_COLD_BLUE -> enableSnow(true)
                GRADIENT_CYAN -> enableBubble(true)
                GRADIENT_PURPLE -> enableStar(true)
                GRADIENT_SUNSET_ORANGE -> enableSunbeam(true)
                GRADIENT_CLASSIC_WHITE -> { /* 纯白，无特效 */ }
            }
        }
    }

    // ---------------- 浅色模式特效 ----------------

    private fun disableAllLightEffects() {
        enableSnow(false)
        enableSunbeam(false)
        enablePetal(false)
        enableBubble(false)
        enableStar(false)
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

    // ---------------- 深色模式特效 ----------------

    private fun disableAllNightEffects() {
        enableNightFirefly(false)
        enableNightMeteor(false)
        enableNightMist(false)
        enableNightStar(false)
        enableMoonlight(false)
    }

    private fun ensureNightFireflyView(): FireflyView {
        if (nightFireflyView == null) {
            nightFireflyView = FireflyView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return nightFireflyView!!
    }

    private fun ensureNightMeteorView(): MeteorShowerView {
        if (nightMeteorView == null) {
            nightMeteorView = MeteorShowerView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return nightMeteorView!!
    }

    private fun ensureNightMistView(): NightMistView {
        if (nightMistView == null) {
            nightMistView = NightMistView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return nightMistView!!
    }

    private fun ensureNightStarView(): TwinkleStarView {
        if (nightStarView == null) {
            nightStarView = TwinkleStarView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return nightStarView!!
    }

    private fun ensureMoonlightView(): MoonlightView {
        if (moonlightView == null) {
            moonlightView = MoonlightView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
        return moonlightView!!
    }

    private fun enableNightFirefly(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureNightFireflyView()
            if (view.parent == null) container.addView(view)
            view.setFireflyEnabled(true)
        } else {
            nightFireflyView?.let {
                it.setFireflyEnabled(false)
                container.removeView(it)
                nightFireflyView = null
            }
        }
    }

    private fun enableNightMeteor(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureNightMeteorView()
            if (view.parent == null) container.addView(view)
            view.setMeteorEnabled(true)
        } else {
            nightMeteorView?.let {
                it.setMeteorEnabled(false)
                container.removeView(it)
                nightMeteorView = null
            }
        }
    }

    private fun enableNightMist(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureNightMistView()
            if (view.parent == null) container.addView(view)
            view.setMistEnabled(true)
        } else {
            nightMistView?.let {
                it.setMistEnabled(false)
                container.removeView(it)
                nightMistView = null
            }
        }
    }

    private fun enableNightStar(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureNightStarView()
            if (view.parent == null) container.addView(view)
            view.setStarEnabled(true)
        } else {
            nightStarView?.let {
                it.setStarEnabled(false)
                container.removeView(it)
                nightStarView = null
            }
        }
    }

    private fun enableMoonlight(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureMoonlightView()
            if (view.parent == null) container.addView(view)
            view.setMoonEnabled(true)
        } else {
            moonlightView?.let {
                it.setMoonEnabled(false)
                container.removeView(it)
                moonlightView = null
            }
        }
    }

    // ---------------- 生命周期 ----------------

    fun onResume() {
        if (currentNightMode) {
            when (currentGradientIndex) {
                GRADIENT_PINK -> nightFireflyView?.setFireflyEnabled(true)
                GRADIENT_COLD_BLUE -> nightMeteorView?.setMeteorEnabled(true)
                GRADIENT_CYAN -> nightMistView?.setMistEnabled(true)
                GRADIENT_PURPLE -> nightStarView?.setStarEnabled(true)
                GRADIENT_SUNSET_ORANGE -> moonlightView?.setMoonEnabled(true)
            }
        } else {
            when (currentGradientIndex) {
                GRADIENT_PINK -> petalView?.setPetalEnabled(true)
                GRADIENT_COLD_BLUE -> snowView?.setSnowEnabled(true)
                GRADIENT_CYAN -> bubbleView?.setBubbleEnabled(true)
                GRADIENT_PURPLE -> starView?.setStarEnabled(true)
                GRADIENT_SUNSET_ORANGE -> sunbeamView?.startAnimation()
            }
        }
    }

    fun onPause() {
        snowView?.setSnowEnabled(false)
        sunbeamView?.stopAnimation()
        petalView?.setPetalEnabled(false)
        bubbleView?.setBubbleEnabled(false)
        starView?.setStarEnabled(false)

        nightFireflyView?.setFireflyEnabled(false)
        nightMeteorView?.setMeteorEnabled(false)
        nightMistView?.setMistEnabled(false)
        nightStarView?.setStarEnabled(false)
        moonlightView?.setMoonEnabled(false)
    }

    fun onDestroy() {
        onPause()

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
        nightFireflyView = null
        nightMeteorView = null
        nightMistView = null
        nightStarView = null
        moonlightView = null
    }
}