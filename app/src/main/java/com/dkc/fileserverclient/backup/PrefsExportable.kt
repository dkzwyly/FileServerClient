package com.dkc.fileserverclient.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用 SharedPreferences 导出器。
 * 记录每个键的类型信息，恢复时能精确还原 Boolean/Int/Long/Float/String/Set<String>。
 */
class PrefsExportable(private val prefsName: String) : Exportable {

    override val exportKey: String = "prefs_$prefsName"

    override suspend fun export(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val values = JSONObject()
        val types = JSONObject()

        for ((k, v) in prefs.all) {
            when (v) {
                is Set<*> -> {
                    val arr = JSONArray()
                    v.forEach { arr.put(it) }
                    values.put(k, arr)
                    types.put(k, "Set")
                }
                else -> {
                    values.put(k, v)
                    types.put(k, v?.javaClass?.simpleName ?: "null")
                }
            }
        }

        val root = JSONObject().apply {
            put("values", values)
            put("types", types)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    override suspend fun restore(context: Context, data: ByteArray): Boolean {
        return try {
            val root = JSONObject(String(data, Charsets.UTF_8))
            val values = root.getJSONObject("values")
            val types = root.optJSONObject("types") ?: JSONObject()

            val editor = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            editor.clear()

            for (key in values.keys()) {
                val type = types.optString(key, "")
                val value = values.get(key)
                when (type) {
                    "Boolean" -> editor.putBoolean(key, value as Boolean)
                    "Integer" -> editor.putInt(key, (value as Number).toInt())
                    "Long"    -> editor.putLong(key, (value as Number).toLong())
                    "Float"   -> editor.putFloat(key, (value as Number).toFloat())
                    "String"  -> editor.putString(key, value as String)
                    "Set"     -> {
                        val arr = value as JSONArray
                        val set = mutableSetOf<String>()
                        for (i in 0 until arr.length()) set.add(arr.getString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> {
                        // 兜底：按 JSON 原生类型猜测
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int     -> editor.putInt(key, value)
                            is Long    -> editor.putLong(key, value)
                            is Double  -> {
                                if (value == value.toLong().toDouble()) {
                                    editor.putLong(key, value.toLong())
                                } else {
                                    editor.putFloat(key, value.toFloat())
                                }
                            }
                            is String  -> editor.putString(key, value)
                        }
                    }
                }
            }
            editor.commit()
        } catch (e: Exception) {
            false
        }
    }
}