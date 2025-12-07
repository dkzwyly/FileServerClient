package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope

class ImagePreviewManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val imageView: ImageView,
    private val loadingProgress: ProgressBar,
    private val httpClient: okhttp3.OkHttpClient
) {
    private var isGif = false

    interface ImageStateListener {
        fun onImageLoadStart()
        fun onImageLoadSuccess(isGif: Boolean)
        fun onImageLoadError(message: String)
        fun onDoubleTap()
    }

    private var listener: ImageStateListener? = null

    fun setListener(listener: ImageStateListener) {
        this.listener = listener
    }

    // 加载图片
    fun loadImage(imageUrl: String, fileName: String) {
        isGif = fileName.endsWith(".gif", ignoreCase = true)

        // 预览时都使用 FIT_CENTER 完整显示图片
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        listener?.onImageLoadStart()
        loadingProgress.visibility = View.VISIBLE

        Log.d("ImagePreviewManager", "开始加载图片: $fileName, isGif: $isGif, scaleType: ${imageView.scaleType}")

        val imageLoader = ImageLoader.Builder(context)
            .okHttpClient(httpClient)
            .components {
                add(GifDecoder.Factory())
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .build()

        val requestBuilder = ImageRequest.Builder(context)
            .data(imageUrl)
            .target(imageView)
            .listener(
                onStart = {
                    listener?.onImageLoadStart()
                },
                onSuccess = { _, _ ->
                    Log.d("ImagePreviewManager", "图片加载成功，isGif: $isGif, scaleType: ${imageView.scaleType}")
                    listener?.onImageLoadSuccess(isGif)
                    loadingProgress.visibility = View.GONE

                    if (isGif) {
                        // 确保GIF动画开始
                        val drawable = imageView.drawable
                        if (drawable is android.graphics.drawable.Animatable && !drawable.isRunning) {
                            Log.d("ImagePreviewManager", "开始播放GIF动画")
                            (drawable as android.graphics.drawable.Animatable).start()
                        }
                    }
                },
                onError = { _, result ->
                    loadingProgress.visibility = View.GONE
                    Log.e("ImagePreviewManager", "图片加载失败", result.throwable)
                    listener?.onImageLoadError("图片加载失败: ${result.throwable.message}")
                }
            )

        if (isGif) {
            requestBuilder
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .allowHardware(false)
        }

        val request = requestBuilder.build()
        imageLoader.enqueue(request)
    }

    fun startGifAnimation() {
        if (isGif) {
            val drawable = imageView.drawable
            if (drawable is android.graphics.drawable.Animatable && !drawable.isRunning) {
                Log.d("ImagePreviewManager", "开始播放GIF动画")
                (drawable as android.graphics.drawable.Animatable).start()
            }
        }
    }

    fun stopGifAnimation() {
        if (isGif) {
            val drawable = imageView.drawable
            if (drawable is android.graphics.drawable.Animatable && drawable.isRunning) {
                Log.d("ImagePreviewManager", "停止GIF动画")
                drawable.stop()
            }
        }
    }

    fun clear() {
        stopGifAnimation()
        imageView.setImageDrawable(null)
    }
}