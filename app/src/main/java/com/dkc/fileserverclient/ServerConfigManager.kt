// ServerConfigManager.kt
package com.dkc.fileserverclient
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ServerConfigManager(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val KEY_CURRENT_CONFIG = "current_server_config"
    private val KEY_HISTORY = "server_config_history"
    private val MAX_HISTORY_SIZE = 10

    fun saveCurrentConfig(config: ServerConfig) {
        val json = gson.toJson(config)
        sharedPreferences.edit().putString(KEY_CURRENT_CONFIG, json).apply()
        addToHistory(config)
    }

    fun getCurrentConfig(): ServerConfig? {
        val json = sharedPreferences.getString(KEY_CURRENT_CONFIG, null)
        return if (json != null) {
            gson.fromJson(json, ServerConfig::class.java)
        } else {
            null
        }
    }

    fun getHistory(): List<ServerConfig> {
        val json = sharedPreferences.getString(KEY_HISTORY, "[]")
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun addToHistory(config: ServerConfig) {
        val history = getHistory().toMutableList()

        // 移除重复的
        history.removeAll { it.baseUrl == config.baseUrl }

        // 添加到开头
        history.add(0, config)

        // 限制历史记录大小
        if (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(history.size - 1)
        }

        val json = gson.toJson(history)
        sharedPreferences.edit().putString(KEY_HISTORY, json).apply()
    }

    fun clearHistory() {
        sharedPreferences.edit().remove(KEY_HISTORY).apply()
    }

    fun removeFromHistory(config: ServerConfig) {
        val history = getHistory().toMutableList()
        history.removeAll { it.baseUrl == config.baseUrl }
        val json = gson.toJson(history)
        sharedPreferences.edit().putString(KEY_HISTORY, json).apply()
    }
}