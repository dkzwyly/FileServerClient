package com.dkc.fileserverclient

import android.content.Context
import android.os.Handler

/**
 * 音频播放管理器接口
 * 用于解耦音频播放逻辑，为后台服务做准备
 */
interface AudioPlayerManager {

    // ==================== 初始化与基础操作 ====================

    /**
     * 初始化播放器
     */
    fun initialize(context: Context, handler: Handler)

    /**
     * 释放资源
     */
    fun release()

    // ==================== 播放控制 ====================

    /**
     * 播放指定音频
     */
    fun play(track: AudioTrack)

    /**
     * 播放指定URL的音频
     */
    fun play(url: String, metadata: AudioTrack? = null)

    /**
     * 暂停播放
     */
    fun pause()

    /**
     * 恢复播放
     */
    fun resume()

    /**
     * 停止播放
     */
    fun stop()

    /**
     * 切换播放/暂停状态
     */
    fun togglePlayback()

    /**
     * 跳转到指定位置
     * @param position 位置（毫秒）
     */
    fun seekTo(position: Long)

    /**
     * 快进
     * @param milliseconds 快进毫秒数
     */
    fun fastForward(milliseconds: Long = 10000)

    /**
     * 快退
     * @param milliseconds 快退毫秒数
     */
    fun rewind(milliseconds: Long = 10000)

    // ==================== 播放列表管理 ====================

    /**
     * 设置播放列表
     */
    fun setPlaylist(tracks: List<AudioTrack>, startIndex: Int = 0)

    /**
     * 播放下一首
     */
    fun playNext()

    /**
     * 播放上一首
     */
    fun playPrevious()

    /**
     * 跳转到播放列表中的指定位置
     */
    fun playAtIndex(index: Int)

    /**
     * 获取当前播放列表
     */
    fun getPlaylist(): List<AudioTrack>

    // ==================== 播放设置 ====================

    /**
     * 设置播放速度
     * @param speed 播放速度（0.5x, 1.0x, 1.5x, 2.0x等）
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * 设置重复模式
     */
    fun setRepeatMode(mode: RepeatMode)

    /**
     * 切换随机播放
     * @param enabled 是否启用随机播放
     */
    fun setShuffleEnabled(enabled: Boolean)

    // ==================== 状态获取 ====================

    /**
     * 获取当前播放状态
     */
    fun getPlaybackStatus(): AudioPlaybackStatus

    /**
     * 获取当前播放位置（毫秒）
     */
    fun getCurrentPosition(): Long

    /**
     * 获取音频总时长（毫秒）
     */
    fun getDuration(): Long

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean

    /**
     * 获取当前播放的音频
     */
    fun getCurrentTrack(): AudioTrack?

    /**
     * 获取当前播放索引
     */
    fun getCurrentIndex(): Int

    // ==================== 事件监听 ====================

    /**
     * 添加播放状态监听器
     */
    fun addPlaybackListener(listener: AudioPlaybackListener)

    /**
     * 移除播放状态监听器
     */
    fun removePlaybackListener(listener: AudioPlaybackListener)

    /**
     * 添加播放进度监听器
     */
    fun addProgressListener(listener: AudioProgressListener)

    /**
     * 移除播放进度监听器
     */
    fun removeProgressListener(listener: AudioProgressListener)
}

/**
 * 音频播放事件监听器
 */
interface AudioPlaybackListener {
    fun onPlaybackStateChanged(status: AudioPlaybackStatus)
    fun onTrackChanged(track: AudioTrack, index: Int)
    fun onPlaybackError(error: String)
    fun onPlaybackEnded()
    fun onAudioBuffering(isBuffering: Boolean)
}

/**
 * 音频播放进度监听器
 */
interface AudioProgressListener {
    fun onProgressUpdated(position: Long, duration: Long)
    fun onBufferingProgress(percent: Int)
}