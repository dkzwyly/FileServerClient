package com.dkc.fileserverclient

import android.content.Context
import android.content.SharedPreferences

object ReadingSettings {
    private const val PREFS_NAME = "reader_settings"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_BG_COLOR = "bg_color"

    const val FONT_SMALL = 14f
    const val FONT_MEDIUM = 18f
    const val FONT_LARGE = 22f

    val BG_WHITE = 0xFFFFFFFF.toInt()
    val BG_EYE_CARE = 0xFFE8DCC8.toInt()
    val BG_DARK = 0xFF2B2B2B.toInt()
    val BG_BLACK = 0xFF000000.toInt()

    fun getTextColorForBg(bgColor: Int): Int =
        if (bgColor == BG_DARK || bgColor == BG_BLACK) 0xFFFFFFFF.toInt()
        else 0xFF333333.toInt()

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFontSize(context: Context): Float =
        getPrefs(context).getFloat(KEY_FONT_SIZE, FONT_MEDIUM)

    fun getBackgroundColor(context: Context): Int =
        getPrefs(context).getInt(KEY_BG_COLOR, BG_WHITE)

    fun setFontSize(context: Context, size: Float) {
        getPrefs(context).edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    fun setBackgroundColor(context: Context, color: Int) {
        getPrefs(context).edit().putInt(KEY_BG_COLOR, color).apply()
    }
}