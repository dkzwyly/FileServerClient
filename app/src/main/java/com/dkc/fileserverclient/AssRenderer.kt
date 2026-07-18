package com.dkc.fileserverclient

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * 渲染 ASS 文本，可选择性强制覆盖文字颜色
 * @param raw 原始 ASS 文本
 * @param baseStyle 基础样式
 * @param styleMap 所有样式表
 * @param forceTextColor 若不为 null，则强制使用此颜色，忽略 ASS 自带颜色
 */
fun renderAssText(
    raw: String,
    baseStyle: AssStyle,
    styleMap: Map<String, AssStyle>,
    forceTextColor: Int? = null
): SpannedString {
    val builder = SpannableStringBuilder()
    var currentStyle = baseStyle.copy()
    var lastTextStart = 0
    var i = 0
    val len = raw.length

    fun applyStyle(start: Int, end: Int) {
        if (end <= start) return

        // 前景色：优先使用强制颜色
        val textColor = forceTextColor ?: currentStyle.primaryColor
        builder.setSpan(ForegroundColorSpan(textColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // 背景色：只在无强制颜色时使用 ASS 背景
        if (forceTextColor == null && currentStyle.backColor != Color.TRANSPARENT) {
            builder.setSpan(
                BackgroundColorSpan(currentStyle.backColor),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 粗/斜体
        when {
            currentStyle.bold && currentStyle.italic -> builder.setSpan(
                StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            currentStyle.bold -> builder.setSpan(
                StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            currentStyle.italic -> builder.setSpan(
                StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 下划线
        if (currentStyle.underline) {
            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    while (i < len) {
        val ch = raw[i]
        if (ch == '{' && i + 1 < len) {
            val endBrace = raw.indexOf('}', i)
            if (endBrace != -1) {
                val tag = raw.substring(i + 1, endBrace)
                applyStyle(lastTextStart, builder.length)
                val newStyle = parseTag(tag, currentStyle, styleMap, baseStyle, forceTextColor)
                if (newStyle != null) currentStyle = newStyle
                lastTextStart = builder.length
                i = endBrace + 1
                continue
            }
        }
        builder.append(ch)
        i++
    }
    applyStyle(lastTextStart, builder.length)
    return SpannedString(builder)
}

private fun parseTag(
    tag: String,
    current: AssStyle,
    styleMap: Map<String, AssStyle>,
    defaultStyle: AssStyle,
    forceTextColor: Int? = null
): AssStyle? {
    var style = current
    when {
        // 如果强制颜色，则忽略 \c 标签
        tag.startsWith("\\c") && forceTextColor == null -> {
            val colorPart = tag.substring(2)
            if (colorPart.startsWith("&H")) {
                style = style.copy(primaryColor = parseAssColor(colorPart))
            }
        }
        tag == "\\b1" -> style = style.copy(bold = true)
        tag == "\\b0" -> style = style.copy(bold = false)
        tag == "\\i1" -> style = style.copy(italic = true)
        tag == "\\i0" -> style = style.copy(italic = false)
        tag == "\\u1" -> style = style.copy(underline = true)
        tag == "\\u0" -> style = style.copy(underline = false)
        tag == "\\r" -> return defaultStyle.copy()
        tag.startsWith("\\r") -> {
            val styleName = tag.substring(2)
            styleMap[styleName]?.let { return it.copy() }
        }
    }
    return style
}