// 在 utils 包中创建 FileNameUtils.kt
package com.dkc.fileserverclient.utils

object FileNameUtils {

    /**
     * 安全地移除文件扩展名
     */
    fun removeExtension(fileName: String): String {
        return try {
            // 处理特殊文件名
            if (fileName.isEmpty()) return fileName

            // 找到最后一个点号的位置
            val lastDotIndex = fileName.lastIndexOf(".")

            // 特殊情况处理：以点开头的隐藏文件，或者点号在第一个字符
            if (lastDotIndex == 0) {
                // 类似 ".gitignore" 的文件名，保留整个文件名
                return fileName
            }

            if (lastDotIndex > 0) {
                // 移除点号及其后面的内容
                return fileName.substring(0, lastDotIndex)
            }

            // 如果没有点号，返回原文件名
            fileName
        } catch (e: Exception) {
            // 异常情况下返回原文件名
            fileName
        }
    }

    /**
     * 获取文件扩展名（不带点号）
     */
    fun getExtension(fileName: String): String {
        return try {
            val lastDotIndex = fileName.lastIndexOf(".")
            if (lastDotIndex > 0 && lastDotIndex < fileName.length - 1) {
                fileName.substring(lastDotIndex + 1)
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}