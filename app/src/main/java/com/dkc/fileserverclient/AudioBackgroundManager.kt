// [file name]: AudioBackgroundManager.kt
package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 音频后台播放管理器
 * 简化版本，只作为桥梁
 */
class AudioBackgroundManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioBackgroundManager"
    }

    private var audioService: AudioPlaybackService? = null
    private var isBound = false

    // 监听器列表
    private val playbackListeners = CopyOnWriteArrayList<AudioPlaybackListener>()
    private val progressListeners = CopyOnWriteArrayList<AudioProgressListener>()

    // 服务连接
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "服务连接成功")
            val binder = service as AudioPlaybackService.AudioServiceBinder
            audioService = binder.getService()
            isBound = true

            // 将所有已注册的监听器添加到服务
            playbackListeners.forEach { listener ->
                audioService?.addPlaybackListener(listener)
            }
            progressListeners.forEach { listener ->
                audioService?.addProgressListener(listener)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "服务断开连接")
            audioService = null
            isBound = false
        }
    }

    /**
     * 启动后台播放服务
     */
    fun startService(track: AudioTrack? = null, playlist: ArrayList<AudioTrack>? = null, startIndex: Int = 0) {
        Log.d(TAG, "启动后台播放服务")

        // 使用服务类提供的方法启动
        AudioPlaybackService.startService(context, track, playlist, startIndex)

        // 绑定服务
        val intent = Intent(context, AudioPlaybackService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 停止后台播放服务
     */
    fun stopService() {
        Log.d(TAG, "停止后台播放服务")

        // 发送停止命令
        AudioPlaybackService.sendAction(context, AudioPlaybackService.ACTION_STOP)

        // 解绑服务
        unbindService()
    }

    /**
     * 绑定到服务（如果服务已在运行）
     */
    fun bindService(): Boolean {
        if (isBound) return true

        val intent = Intent(context, AudioPlaybackService::class.java)
        return context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 解绑服务
     */
    fun unbindService() {
        if (isBound) {
            // 从服务中移除所有监听器
            playbackListeners.forEach { listener ->
                audioService?.removePlaybackListener(listener)
            }
            progressListeners.forEach { listener ->
                audioService?.removeProgressListener(listener)
            }

            context.unbindService(serviceConnection)
            isBound = false
            audioService = null
        }
    }

    /**
     * 获取当前播放状态
     */
    fun getPlaybackStatus(): AudioPlaybackStatus? {
        return audioService?.getPlaybackStatus()
    }

    /**
     * 获取当前播放曲目
     */
    fun getCurrentTrack(): AudioTrack? {
        return audioService?.getCurrentTrack()
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean {
        return audioService?.isPlaying() ?: false
    }

    /**
     * 发送控制命令
     */
    fun sendAction(action: String) {
        AudioPlaybackService.sendAction(context, action)
    }

    /**
     * 添加播放监听器
     */
    fun addPlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.add(listener)
        audioService?.addPlaybackListener(listener)
    }

    /**
     * 移除播放监听器
     */
    fun removePlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.remove(listener)
        audioService?.removePlaybackListener(listener)
    }

    /**
     * 添加进度监听器
     */
    fun addProgressListener(listener: AudioProgressListener) {
        progressListeners.add(listener)
        audioService?.addProgressListener(listener)
    }

    /**
     * 移除进度监听器
     */
    fun removeProgressListener(listener: AudioProgressListener) {
        progressListeners.remove(listener)
        audioService?.removeProgressListener(listener)
    }

    /**
     * 检查服务是否正在运行
     */
    fun isServiceRunning(): Boolean {
        return isBound && audioService != null
    }

    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Long) {
        if (isBound && audioService != null) {
            audioService?.seekTo(position)
        } else {
            Log.d(TAG, "服务未绑定，无法跳转")
            // 尝试绑定后跳转
            if (bindService()) {
                // 延迟执行跳转，等待服务连接
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    audioService?.seekTo(position)
                }, 300)
            }
        }
    }

    /**
     * 检查并确保服务已准备好
     */
    fun ensureServiceReady(callback: (Boolean) -> Unit) {
        if (isBound && audioService != null) {
            callback(true)
        } else {
            // 尝试绑定服务
            val bound = bindService()
            if (bound) {
                // 等待服务连接
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    callback(isBound && audioService != null)
                }, 300)
            } else {
                callback(false)
            }
        }
    }

    /**
     * 安全地播放下一首
     */
    fun safePlayNext() {
        ensureServiceReady { isReady ->
            if (isReady) {
                sendAction(AudioPlaybackService.ACTION_NEXT)
            } else {
                Log.e(TAG, "服务未准备好，无法播放下一首")
            }
        }
    }

    /**
     * 安全地播放上一首
     */
    fun safePlayPrevious() {
        ensureServiceReady { isReady ->
            if (isReady) {
                sendAction(AudioPlaybackService.ACTION_PREVIOUS)
            } else {
                Log.e(TAG, "服务未准备好，无法播放上一首")
            }
        }
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        // 清理监听器列表
        playbackListeners.clear()
        progressListeners.clear()

        // 解绑服务
        unbindService()
    }
}