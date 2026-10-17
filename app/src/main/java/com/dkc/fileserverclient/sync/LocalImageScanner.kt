package com.dkc.fileserverclient.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalImage(
    val mediaId: Long,
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val bucketName: String
) {
    var hash: String? = null
    var alreadyOnServer: Boolean = false
    var quickUploaded: Boolean = false
    var uploadQueued: Boolean = false
    var errorMessage: String? = null
}

object LocalImageScanner {

    /** 默认只看相机目录 */
    private const val DEFAULT_BUCKET = "Camera"

    /**
     * @param onlyCamera true 只扫 Camera 目录，false 扫全部
     */
    suspend fun scan(context: Context, onlyCamera: Boolean = true): List<LocalImage> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<LocalImage>()

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            val selection = if (onlyCamera) {
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
            } else null
            val selectionArgs = if (onlyCamera) arrayOf(DEFAULT_BUCKET) else null

            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
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
                        LocalImage(
                            mediaId = id,
                            uri = uri,
                            displayName = cursor.getString(nameCol) ?: "",
                            size = cursor.getLong(sizeCol),
                            dateModified = cursor.getLong(dateCol),
                            bucketName = cursor.getString(bucketCol) ?: ""
                        )
                    )
                }
            }

            result
        }
}