// AudioBackgroundManager.kt
package com.dkc.fileserverclient

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class AudioBackgroundManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioBackgroundManager"
    }

    private var audioService: AudioPlaybackService? = null
    private var isBound = false
    private val playbackListeners = CopyOnWriteArrayList<AudioPlaybackListener>()
    private val progressListeners = CopyOnWriteArrayList<AudioProgressListener>()
    private val spectrumListeners = CopyOnWriteArrayList<AudioSpectrumListener>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "服务连接成功")
            val binder = service as AudioPlaybackService.AudioServiceBinder
            audioService = binder.getService()
            isBound = true
            playbackListeners.forEach { audioService?.addPlaybackListener(it) }
            progressListeners.forEach { audioService?.addProgressListener(it) }
            spectrumListeners.forEach { audioService?.addSpectrumListener(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "服务断开连接")
            audioService = null
            isBound = false
        }
    }

    fun startService(track: AudioTrack? = null, playlist: ArrayList<AudioTrack>? = null, startIndex: Int = 0) {
        Log.d(TAG, "启动后台播放服务")
        AudioPlaybackService.startService(context, track, playlist, startIndex)
        val intent = Intent(context, AudioPlaybackService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopServiceAndRelease() {
        AudioPlaybackService.sendAction(context, AudioPlaybackService.ACTION_CLOSE)
        cleanup()
    }

    fun stopService() {
        AudioPlaybackService.sendAction(context, AudioPlaybackService.ACTION_STOP)
    }

    fun bindService(): Boolean {
        if (isBound) return true
        val intent = Intent(context, AudioPlaybackService::class.java)
        return context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (isBound) {
            playbackListeners.forEach { audioService?.removePlaybackListener(it) }
            progressListeners.forEach { audioService?.removeProgressListener(it) }
            spectrumListeners.forEach { audioService?.removeSpectrumListener(it) }
            context.unbindService(serviceConnection)
            isBound = false
            audioService = null
        }
    }

    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0) {
        if (isBound && audioService != null) {
            audioService?.setPlaylist(tracks, startIndex)
        } else {
            startService(null, ArrayList(tracks), startIndex)
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        if (isBound && audioService != null) {
            audioService?.setRepeatMode(mode)
        } else {
            ensureServiceReady { if (it) audioService?.setRepeatMode(mode) }
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (isBound && audioService != null) {
            audioService?.setShuffleEnabled(enabled)
        } else {
            ensureServiceReady { if (it) audioService?.setShuffleEnabled(enabled) }
        }
    }

    fun getPlaybackStatus(): AudioPlaybackStatus? {
        if (isBound && audioService != null) {
            return audioService?.getPlaybackStatus()
        }
        if (!isBound && audioService == null) {
            bindService()
        }
        return null
    }

    fun getCurrentTrack(): AudioTrack? = audioService?.getCurrentTrack()
    fun isPlaying(): Boolean = audioService?.isPlaying() ?: false
    fun sendAction(action: String) = AudioPlaybackService.sendAction(context, action)

    fun addPlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.add(listener)
        audioService?.addPlaybackListener(listener)
    }

    fun removePlaybackListener(listener: AudioPlaybackListener) {
        playbackListeners.remove(listener)
        audioService?.removePlaybackListener(listener)
    }

    fun addProgressListener(listener: AudioProgressListener) {
        progressListeners.add(listener)
        audioService?.addProgressListener(listener)
    }

    fun removeProgressListener(listener: AudioProgressListener) {
        progressListeners.remove(listener)
        audioService?.removeProgressListener(listener)
    }

    fun addSpectrumListener(listener: AudioSpectrumListener) {
        spectrumListeners.add(listener)
        audioService?.addSpectrumListener(listener)
    }

    fun removeSpectrumListener(listener: AudioSpectrumListener) {
        spectrumListeners.remove(listener)
        audioService?.removeSpectrumListener(listener)
    }

    fun isServiceRunning(): Boolean = isBound && audioService != null

    fun seekTo(position: Long) {
        if (isBound && audioService != null) {
            audioService?.seekTo(position)
        } else {
            if (bindService()) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    audioService?.seekTo(position)
                }, 300)
            }
        }
    }

    fun ensureServiceReady(callback: (Boolean) -> Unit) {
        if (isBound && audioService != null) {
            callback(true)
        } else {
            val bound = bindService()
            if (bound) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    callback(isBound && audioService != null)
                }, 300)
            } else {
                callback(false)
            }
        }
    }

    fun safePlayNext() {
        ensureServiceReady { if (it) sendAction(AudioPlaybackService.ACTION_NEXT) }
    }

    fun safePlayPrevious() {
        ensureServiceReady { if (it) sendAction(AudioPlaybackService.ACTION_PREVIOUS) }
    }

    fun shutdownService() {
        AudioPlaybackService.sendAction(context, AudioPlaybackService.ACTION_CLOSE)
        cleanup()
    }

    fun keepServiceAlive() {
        unbindService()
    }

    fun rebindToService(): Boolean {
        if (isBound) return true
        return bindService()
    }

    fun isServiceBound(): Boolean = isBound
    fun getService(): AudioPlaybackService? = audioService
    fun isServiceReady(): Boolean = isBound && audioService != null

    fun cleanupLocal() {
        playbackListeners.clear()
        progressListeners.clear()
        spectrumListeners.clear()
        unbindService()
    }

    fun cleanup() {
        playbackListeners.clear()
        progressListeners.clear()
        spectrumListeners.clear()
        unbindService()
    }
}