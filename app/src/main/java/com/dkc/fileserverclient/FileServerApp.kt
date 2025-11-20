// FileServerApp.kt
package com.dkc.fileserverclient

import android.app.Application
import android.content.Context

class FileServerApp : Application() {

    val repository: FileRepository by lazy {
        FileRepository(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // 在这里可以初始化全局组件
    }
}