package com.dkc.fileserverclient.sync

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 本地哈希缓存：mediaId -> {hash, size, lastModified}。
 * 存到 filesDir/sync/hash_cache.json。
 */
class LocalHashCache(private val context: Context) {

    companion object {
        private const val TAG = "LocalHashCache"
        private const val DIR = "sync"
        private const val FILE = "hash_cache.json"
    }

    private val data = mutableMapOf<Long, Entry>()

    data class Entry(val hash: String, val size: Long, val lastModified: Long)

    fun load() {
        try {
            val f = File(context.filesDir, "$DIR/$FILE")
            if (!f.exists()) return
            val json = JSONObject(f.readText())
            for (key in json.keys()) {
                val obj = json.getJSONObject(key)
                data[key.toLong()] = Entry(
                    hash = obj.getString("hash"),
                    size = obj.getLong("size"),
                    lastModified = obj.getLong("lastModified")
                )
            }
            Log.d(TAG, "已加载 ${data.size} 条哈希缓存")
        } catch (e: Exception) {
            Log.w(TAG, "加载哈希缓存失败", e)
        }
    }

    fun save() {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val json = JSONObject()
            for ((id, entry) in data) {
                json.put(id.toString(), JSONObject().apply {
                    put("hash", entry.hash)
                    put("size", entry.size)
                    put("lastModified", entry.lastModified)
                })
            }
            File(dir, FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "保存哈希缓存失败", e)
        }
    }

    /** 命中条件：id 相同 + size 相同 + lastModified 相同 */
    fun get(mediaId: Long, size: Long, lastModified: Long): String? {
        val entry = data[mediaId] ?: return null
        if (entry.size != size || entry.lastModified != lastModified) return null
        return entry.hash
    }

    fun put(mediaId: Long, hash: String, size: Long, lastModified: Long) {
        data[mediaId] = Entry(hash, size, lastModified)
    }

    fun clear() {
        data.clear()
        save()
    }
}