package com.dkc.fileserverclient

import java.net.URLEncoder
import java.util.UUID

/**
 * 音频相关工具函数
 */
object AudioUtils {

    /**
     * 判断是否为音频文件
     */
    fun isAudioFile(item: FileSystemItem): Boolean {
        val audioExtensions = listOf(
            ".mp3", ".wav", ".ogg", ".m4a", ".flac", ".aac", ".wma", ".amr",
            ".MP3", ".WAV", ".OGG", ".M4A", ".FLAC", ".AAC", ".WMA", ".AMR"
        )
        return audioExtensions.any { item.name.endsWith(it, ignoreCase = true) }
    }

    /**
     * 从音频文件列表中过滤出音频文件
     */
    fun filterAudioFiles(items: List<FileSystemItem>): List<FileSystemItem> {
        return items.filter { isAudioFile(it) }
    }

    /**
     * 获取音频文件格式
     */
    fun getAudioFormat(item: FileSystemItem): String {
        return when (item.extension.lowercase()) {
            ".mp3" -> "MP3"
            ".wav" -> "WAV"
            ".ogg" -> "OGG"
            ".m4a" -> "M4A"
            ".flac" -> "FLAC"
            ".aac" -> "AAC"
            ".wma" -> "WMA"
            ".amr" -> "AMR"
            else -> item.extension.uppercase()
        }
    }

    /**
     * 将 FileSystemItem 列表转换为 AudioTrack 列表
     */
    fun convertToAudioTracks(
        items: List<FileSystemItem>,
        serverUrl: String
    ): List<AudioTrack> {
        return items.filter { isAudioFile(it) }
            .map { AudioTrack.fromFileSystemItem(it, serverUrl) }
    }
}