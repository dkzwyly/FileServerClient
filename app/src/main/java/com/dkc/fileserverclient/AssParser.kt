package com.dkc.fileserverclient

import android.graphics.Color

data class AssStyle(
    val name: String = "Default",
    val primaryColor: Int = Color.WHITE,
    val backColor: Int = Color.TRANSPARENT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

data class AssDialogue(
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val styleName: String = "Default"
)

fun parseAssColor(colorStr: String): Int {
    val clean = colorStr.replace("&H", "", ignoreCase = true)
        .replace("&", "")
        .uppercase()
    return when (clean.length) {
        6 -> {
            val b = clean.substring(0, 2).toIntOrNull(16) ?: 0
            val g = clean.substring(2, 4).toIntOrNull(16) ?: 0
            val r = clean.substring(4, 6).toIntOrNull(16) ?: 0
            Color.rgb(r, g, b)
        }
        8 -> {
            val a = clean.substring(0, 2).toIntOrNull(16) ?: 0xFF
            val b = clean.substring(2, 4).toIntOrNull(16) ?: 0
            val g = clean.substring(4, 6).toIntOrNull(16) ?: 0
            val r = clean.substring(6, 8).toIntOrNull(16) ?: 0
            Color.argb(a, r, g, b)
        }
        else -> Color.WHITE
    }
}

object AssParser {
    fun parse(content: String): Pair<Map<String, AssStyle>, List<AssDialogue>> {
        val styles = mutableMapOf<String, AssStyle>()
        val dialogues = mutableListOf<AssDialogue>()
        var currentSection = ""

        content.lines().forEach { line ->
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    currentSection = line.substring(1, line.length - 1)
                }
                currentSection == "V4+ Styles" && line.startsWith("Style:") -> {
                    val parts = line.substringAfter("Style:").split(",").map { it.trim() }
                    if (parts.size >= 10) {
                        val name = parts[0]
                        val primary = parseAssColor(parts[3])
                        val back = parseAssColor(parts[6])
                        val bold = parts[7].toIntOrNull()?.let { it == -1 || it == 1 } ?: false
                        val italic = parts[8].toIntOrNull()?.let { it == -1 || it == 1 } ?: false
                        val underline = parts[9].toIntOrNull()?.let { it == -1 || it == 1 } ?: false
                        styles[name] = AssStyle(name, primary, back, bold, italic, underline)
                    }
                }
                currentSection == "Events" && line.startsWith("Dialogue:") -> {
                    val parts = line.substringAfter("Dialogue:").split(",").map { it.trim() }
                    if (parts.size >= 10) {
                        val start = parseAssTimestamp(parts[1])
                        val end = parseAssTimestamp(parts[2])
                        val styleName = parts[3]
                        val text = parts.drop(9).joinToString(",")
                        if (start >= 0 && end >= 0 && text.isNotBlank()) {
                            dialogues.add(AssDialogue(start, end, text, styleName))
                        }
                    }
                }
            }
        }
        if (styles.isEmpty()) {
            styles["Default"] = AssStyle()
        }
        return Pair(styles, dialogues)
    }

    private fun parseAssTimestamp(s: String): Long {
        val clean = s.replace(',', '.')
        val parts = clean.split(":")
        if (parts.size != 3) return -1
        val secParts = parts[2].split(".")
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        val sec = secParts[0].toIntOrNull() ?: return -1
        val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toInt() else 0
        return (h * 3600 + m * 60 + sec) * 1000L + ms
    }
}