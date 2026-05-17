package com.dkc.fileserverclient

import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout

class BackgroundEffectManager(private val activity: Activity) {

    companion object {
        private const val TAG = "EffectManager"
        const val GRADIENT_COLD_BLUE = 1
        const val GRADIENT_SUNSET_ORANGE = 4
    }

    private var snowView: SnowView? = null
    private var sunbeamView: SunbeamView? = null
    private var sunbeamContainer: FrameLayout? = null   // 底层容器（阳光）
    private var snowContainer: FrameLayout? = null      // 上层容器（雪花）
    private var currentGradientIndex = -1

    fun attachTo(rootView: ViewGroup) {
        // 创建底层容器（阳光），索引0确保在所有内容之下
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
            Log.d(TAG, "阳光容器已添加到根视图底层")
        }

        // 创建上层容器（雪花），默认添加到最上层
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
            Log.d(TAG, "雪花容器已添加到根视图上层")
        }
    }

    fun applyEffectForGradient(gradientIndex: Int) {
        if (currentGradientIndex == gradientIndex) return
        currentGradientIndex = gradientIndex
        Log.d(TAG, "切换特效，渐变索引: $gradientIndex")

        when (gradientIndex) {
            GRADIENT_COLD_BLUE -> {
                enableSnow(true)
                enableSunbeam(false)
            }
            GRADIENT_SUNSET_ORANGE -> {
                enableSnow(false)
                enableSunbeam(true)
            }
            else -> {
                enableSnow(false)
                enableSunbeam(false)
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
            Log.d(TAG, "创建 SnowView")
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
            Log.d(TAG, "创建 SunbeamView")
        }
        return sunbeamView!!
    }

    private fun enableSnow(enable: Boolean) {
        val container = snowContainer ?: return
        if (enable) {
            val view = ensureSnowView()
            if (view.parent == null) {
                container.addView(view)
                Log.d(TAG, "SnowView 添加到雪花容器")
            } else {
                view.bringToFront()
            }
            view.setSnowEnabled(true)
        } else {
            snowView?.let {
                it.setSnowEnabled(false)
                container.removeView(it)
                snowView = null
                Log.d(TAG, "SnowView 已移除")
            }
        }
    }

    private fun enableSunbeam(enable: Boolean) {
        val container = sunbeamContainer ?: return
        if (enable) {
            val view = ensureSunbeamView()
            if (view.parent == null) {
                container.addView(view)
                Log.d(TAG, "SunbeamView 添加到阳光容器")
            } else {
                view.bringToFront()
            }
            view.startAnimation()
        } else {
            sunbeamView?.let {
                it.stopAnimation()
                container.removeView(it)
                sunbeamView = null
                Log.d(TAG, "SunbeamView 已移除")
            }
        }
    }

    fun onResume() {
        when (currentGradientIndex) {
            GRADIENT_COLD_BLUE -> snowView?.setSnowEnabled(true)
            GRADIENT_SUNSET_ORANGE -> sunbeamView?.startAnimation()
        }
    }

    fun onPause() {
        snowView?.setSnowEnabled(false)
        sunbeamView?.stopAnimation()
    }

    fun onDestroy() {
        snowView?.setSnowEnabled(false)
        sunbeamView?.stopAnimation()
        snowContainer?.removeAllViews()
        sunbeamContainer?.removeAllViews()
        (snowContainer?.parent as? ViewGroup)?.removeView(snowContainer)
        (sunbeamContainer?.parent as? ViewGroup)?.removeView(sunbeamContainer)
        snowContainer = null
        sunbeamContainer = null
        snowView = null
        sunbeamView = null
    }
}