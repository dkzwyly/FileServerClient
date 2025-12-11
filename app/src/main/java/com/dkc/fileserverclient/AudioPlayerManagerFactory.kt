package com.dkc.fileserverclient

import android.content.Context
import okhttp3.OkHttpClient

/**
 * AudioPlayerManager 工厂类
 */
object AudioPlayerManagerFactory {

    private var instance: AudioPlayerManager? = null

    /**
     * 获取 AudioPlayerManager 实例（单例模式）
     */
    fun getInstance(httpClient: OkHttpClient? = null): AudioPlayerManager {
        if (instance == null) {
            val client = httpClient ?: UnsafeHttpClient.createUnsafeOkHttpClient()
            instance = ExoAudioPlayerManager(client)
        }
        return instance!!
    }

    /**
     * 初始化 AudioPlayerManager
     */
    fun initialize(context: Context, httpClient: OkHttpClient? = null) {
        val manager = getInstance(httpClient)
        if (manager is ExoAudioPlayerManager) {
            manager.initialize(context, android.os.Handler(context.mainLooper))
        }
    }

    /**
     * 释放 AudioPlayerManager
     */
    fun release() {
        instance?.release()
        instance = null
    }
}