package com.dkc.fileserverclient

import android.view.View

class StaticBackgroundAnimation : PlayingAnimation {
    override fun start(target: View) {
        target.foreground = null
    }

    override fun stop(target: View) {
        target.foreground = null
    }

    override fun isRunning(target: View): Boolean = false
}