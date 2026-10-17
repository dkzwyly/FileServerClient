package com.dkc.fileserverclient.sync

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 MediaStore 扫描本地图片和视频。
 */
object LocalMediaScanner {

    private const val DEFAULT_BUCKET = "Camera"

    /**
     * 扫描图片 + 视频。
     * @param onlyCamera true 只看 Camera 目录；false 扫所有相册
     */
    suspend fun scan(
        context: Context,
        onlyCamera: Boolean = true
    ): List<LocalMedia> = withContext(Dispatchers.IO) {
        val images = scanImages(context, onlyCamera)
        val videos = scanVideos(context, onlyCamera)
        (images + videos).sortedByDescending { it.dateModified }
    }

    // ============================================================
    // 图片
    // ============================================================
    private fun scanImages(context: Context, onlyCamera: Boolean): List<LocalMedia> {
        val result = mutableListOf<LocalMedia>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val selection = if (onlyCamera)
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        else null
        val selectionArgs = if (onlyCamera) arrayOf(DEFAULT_BUCKET) else null

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                result.add(
                    LocalMedia(
                        mediaId = id,
                        uri = uri,
                        displayName = cursor.getString(nameCol) ?: "",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        bucketName = cursor.getString(bucketCol) ?: "",
                        isVideo = false
                    )
                )
            }
        }
        return result
    }

    // ============================================================
    // 视频
    // ============================================================
    private fun scanVideos(context: Context, onlyCamera: Boolean): List<LocalMedia> {
        val result = mutableListOf<LocalMedia>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )

        val selection = if (onlyCamera)
            "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?"
        else null
        val selectionArgs = if (onlyCamera) arrayOf(DEFAULT_BUCKET) else null

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                )
                result.add(
                    LocalMedia(
                        mediaId = id,
                        uri = uri,
                        displayName = cursor.getString(nameCol) ?: "",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        bucketName = cursor.getString(bucketCol) ?: "",
                        isVideo = true,
                        duration = cursor.getLong(durCol)
                    )
                )
            }
        }
        return result
    }
}