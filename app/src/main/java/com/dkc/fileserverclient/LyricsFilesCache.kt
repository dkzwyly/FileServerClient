package com.dkc.fileserverclient

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌词文件列表缓存
 * - 内存层：热数据，进程内 0ms
 * - 持久层：SharedPreferences，跨进程启动复用
 */
object LyricsFilesCache {

    private const val PREF_NAME = "lyrics_files_cache"
    private const val TTL_MS = 10 * 60 * 1000L   // 10 分钟

    private val gson = Gson()
    private val listType = object : TypeToken<List<FileServerService.LyricsFileInfo>>() {}.type

    private data class Entry(
        val files: List<FileServerService.LyricsFileInfo>,
        val timestamp: Long
    )

    private val memCache = ConcurrentHashMap<String, Entry>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun key(serverUrl: String, dir: String) = "${serverUrl.trimEnd('/')}|$dir"

    /** 读缓存。allowExpired=true 时忽略 TTL，用于回源失败时兜底 */
    fun get(
        serverUrl: String,
        dir: String,
        allowExpired: Boolean = false
    ): List<FileServerService.LyricsFileInfo>? {
        val k = key(serverUrl, dir)
        val now = System.currentTimeMillis()

        // 1) 内存命中
        memCache[k]?.let { e ->
            if (allowExpired || now - e.timestamp < TTL_MS) return e.files
        }

        // 2) 持久层命中
        val p = prefs ?: return null
        val json = p.getString(k, null) ?: return null
        return try {
            val files: List<FileServerService.LyricsFileInfo> =
                gson.fromJson(json, listType) ?: return null
            val ts = p.getLong("$k:ts", 0L)
            if (!allowExpired && now - ts >= TTL_MS) return null
            memCache[k] = Entry(files, ts)
            files
        } catch (e: Exception) {
            null
        }
    }

    fun put(
        context: Context,
        serverUrl: String,
        dir: String,
        files: List<FileServerService.LyricsFileInfo>
    ) {
        init(context)
        val k = key(serverUrl, dir)
        val now = System.currentTimeMillis()
        memCache[k] = Entry(files, now)
        prefs?.edit()
            ?.putString(k, gson.toJson(files, listType))
            ?.putLong("$k:ts", now)
            ?.apply()
    }

    fun invalidate(context: Context, serverUrl: String, dir: String) {
        init(context)
        val k = key(serverUrl, dir)
        memCache.remove(k)
        prefs?.edit()?.remove(k)?.remove("$k:ts")?.apply()
    }
}