package com.dkc.fileserverclient

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object PlaylistManager {

    private const val PREFS_NAME = "playlist_prefs"
    private const val KEY_PLAYLISTS = "playlists"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 获取所有歌单
    fun getAllPlaylists(): MutableList<Playlist> {
        val json = prefs.getString(KEY_PLAYLISTS, null)
        return if (json.isNullOrEmpty()) {
            mutableListOf()
        } else {
            val type = object : TypeToken<MutableList<Playlist>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        }
    }

    // 保存所有歌单
    private fun savePlaylists(playlists: List<Playlist>) {
        val json = gson.toJson(playlists)
        prefs.edit().putString(KEY_PLAYLISTS, json).apply()
    }

    // 创建新歌单
    fun createPlaylist(name: String): Playlist {
        val playlists = getAllPlaylists().toMutableList()
        val newPlaylist = Playlist(
            id = "playlist_${System.currentTimeMillis()}",
            name = name,
            tracks = emptyList()
        )
        playlists.add(newPlaylist)
        savePlaylists(playlists)
        return newPlaylist
    }

    // 删除歌单
    fun deletePlaylist(playlistId: String) {
        val playlists = getAllPlaylists().toMutableList()
        playlists.removeAll { it.id == playlistId }
        savePlaylists(playlists)
    }

    // 重命名歌单
    fun renamePlaylist(playlistId: String, newName: String): Boolean {
        val playlists = getAllPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            playlists[index] = playlists[index].copy(name = newName)
            savePlaylists(playlists)
            return true
        }
        return false
    }

    // 添加歌曲到歌单
    fun addTrackToPlaylist(playlistId: String, track: AudioTrack): Boolean {
        val playlists = getAllPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            val playlist = playlists[index]
            // 避免重复添加同一首歌曲（根据 id 判断）
            if (playlist.tracks.any { it.id == track.id }) {
                return false
            }
            val updatedTracks = playlist.tracks + track
            playlists[index] = playlist.copy(tracks = updatedTracks)
            savePlaylists(playlists)
            return true
        }
        return false
    }

    // 从歌单移除歌曲
    fun removeTrackFromPlaylist(playlistId: String, trackId: String): Boolean {
        val playlists = getAllPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            val playlist = playlists[index]
            val updatedTracks = playlist.tracks.filter { it.id != trackId }
            playlists[index] = playlist.copy(tracks = updatedTracks)
            savePlaylists(playlists)
            return true
        }
        return false
    }

    // 获取单个歌单
    fun getPlaylist(playlistId: String): Playlist? {
        return getAllPlaylists().firstOrNull { it.id == playlistId }
    }

    // 更新整个歌单（例如调整顺序）
    fun updatePlaylist(updatedPlaylist: Playlist) {
        val playlists = getAllPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == updatedPlaylist.id }
        if (index != -1) {
            playlists[index] = updatedPlaylist
            savePlaylists(playlists)
        }
    }
}