package com.dkc.fileserverclient

import android.view.View

interface PlayingAnimation {
    /**
     * 开始播放动画（通常应用于封面图标或整个item）
     * @param target 要应用动画的View，通常是 ImageView
     */
    fun start(target: View)

    /**
     * 停止动画，并恢复View的原始状态（缩放、透明度等）
     */
    fun stop(target: View)

    /**
     * 判断当前目标View是否正在执行动画
     */
    fun isRunning(target: View): Boolean
}