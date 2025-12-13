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
    // AudioBackgroundManager.kt - 修改 stopService() 方法
    /**
     * 停止后台播放服务
     */
    fun stopService() {
        Log.d(TAG, "停止后台播放服务")

        // 重要：不要发送停止服务的命令，而是发送停止播放的命令
        // 这允许服务继续运行，支持通知栏控制
        AudioPlaybackService.sendAction(context, AudioPlaybackService.ACTION_STOP)

        Log.d(TAG, "已发送停止播放命令，但服务仍在运行")
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
     * 设置播放列表
     */
    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        Log.d(TAG, "设置播放列表: 大小=${tracks.size}, 起始索引=$startIndex")

        if (isBound && audioService != null) {
            audioService?.setPlaylist(tracks, startIndex)
        } else {
            // 如果服务未绑定，启动服务并传递播放列表
            val trackList = ArrayList(tracks)
            startService(null, trackList, startIndex)
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
        Log.d(TAG, "安全播放下一首")
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
        Log.d(TAG, "安全播放上一首")
        ensureServiceReady { isReady ->
            if (isReady) {
                sendAction(AudioPlaybackService.ACTION_PREVIOUS)
            } else {
                Log.e(TAG, "服务未准备好，无法播放上一首")
            }
        }
    }
// AudioBackgroundManager.kt - 添加关闭服务的方法
    /**
     * 完全关闭服务（在应用退出时调用）
     */
    fun shutdownService() {
        Log.d(TAG, "完全关闭音频播放服务")

        // 发送关闭服务的命令
        AudioPlaybackService.sendAction(context, AudioPlaybackService.ACTION_CLOSE)

        // 清理资源
        cleanup()

        Log.d(TAG, "音频播放服务已完全关闭")
    }
    // AudioBackgroundManager.kt - 添加新方法
    /**
     * 保持服务运行但不绑定（用于Activity销毁时）
     */
    fun keepServiceAlive() {
        Log.d(TAG, "保持音频服务运行")

        // 重要：解绑服务但让服务继续在后台运行
        unbindService()

        // 服务会继续在后台运行，可以通过通知栏控制
        Log.d(TAG, "服务解绑但继续在后台运行")
    }

    /**
     * 重新绑定到正在运行的服务
     */
    fun rebindToService(): Boolean {
        Log.d(TAG, "重新绑定到音频服务")

        if (isBound) {
            Log.d(TAG, "服务已绑定")
            return true
        }

        return bindService()
    }
// AudioBackgroundManager.kt - 在类中添加这些方法
    /**
     * 检查服务是否已绑定
     */
    fun isServiceBound(): Boolean {
        return isBound
    }

    /**
     * 获取服务实例（用于调试和测试）
     */
    fun getService(): AudioPlaybackService? {
        return audioService
    }

    /**
     * 检查服务是否准备好
     */
    fun isServiceReady(): Boolean {
        return isBound && audioService != null
    }

    /**
     * 清理资源但保持服务运行
     */
    fun cleanupLocal() {
        Log.d(TAG, "清理本地资源，保持服务运行")

        // 清理监听器列表
        playbackListeners.clear()
        progressListeners.clear()

        // 解绑服务但不停止服务
        unbindService()

        Log.d(TAG, "本地资源清理完成，服务仍在后台运行")
    }
// AudioBackgroundManager.kt - 修改 cleanup() 方法
    /**
     * 清理资源
     */
    fun cleanup() {
        Log.d(TAG, "清理音频后台管理器资源")

        // 清理监听器列表
        playbackListeners.clear()
        progressListeners.clear()

        // 解绑服务（但不停止服务）
        unbindService()

        Log.d(TAG, "音频后台管理器清理完成，服务仍在后台运行")
    }

}