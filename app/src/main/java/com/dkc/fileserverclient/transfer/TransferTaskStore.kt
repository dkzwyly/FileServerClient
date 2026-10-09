package com.dkc.fileserverclient.transfer

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TransferTaskStore {

    private const val TAG = "TransferTaskStore"
    private const val DIR = "transfer"
    private const val FILE_NAME = "tasks.json"

    private val gson = Gson()

    private fun file(context: Context): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    suspend fun load(context: Context): List<TransferTask> = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (!f.exists()) return@withContext emptyList()
            val type = object : TypeToken<List<TransferTask>>() {}.type
            gson.fromJson<List<TransferTask>>(f.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "加载任务失败", e)
            emptyList()
        }
    }

    suspend fun save(context: Context, tasks: List<TransferTask>) = withContext(Dispatchers.IO) {
        try {
            file(context).writeText(gson.toJson(tasks))
        } catch (e: Exception) {
            Log.e(TAG, "保存任务失败", e)
        }
    }
}