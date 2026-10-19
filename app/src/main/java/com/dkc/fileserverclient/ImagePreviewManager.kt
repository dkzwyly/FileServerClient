package com.dkc.fileserverclient

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
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

    fun loadImage(imageUrl: String, fileName: String) {
        isGif = fileName.endsWith(".gif", ignoreCase = true)

        listener?.onImageLoadStart()
        loadingProgress.visibility = View.VISIBLE

        Log.d("ImagePreviewManager", "开始加载: $fileName, isGif=$isGif")

        // ★ 复用 Coil 全局单例（MusicApplication 已注册 GifDecoder）
        val loader = context.imageLoader

        val requestBuilder = ImageRequest.Builder(context)
            .data(imageUrl)
            .target(imageView)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .listener(
                onStart = { _: ImageRequest ->
                    listener?.onImageLoadStart()
                },
                onSuccess = { _: ImageRequest, _: SuccessResult ->
                    Log.d("ImagePreviewManager", "加载成功 isGif=$isGif")
                    listener?.onImageLoadSuccess(isGif)
                    loadingProgress.visibility = View.GONE
                    if (isGif) {
                        val d = imageView.drawable
                        if (d is android.graphics.drawable.Animatable && !d.isRunning) {
                            d.start()
                        }
                    }
                },
                onError = { _: ImageRequest, result: ErrorResult ->
                    loadingProgress.visibility = View.GONE
                    Log.e("ImagePreviewManager", "加载失败", result.throwable)
                    listener?.onImageLoadError("图片加载失败: ${result.throwable.message}")
                }
            )

        if (isGif) {
            // GIF 必须关 crossfade + 关硬件 Bitmap，否则会卡首帧
            requestBuilder
                .crossfade(false)
                .allowHardware(false)
        } else {
            requestBuilder.crossfade(true)
        }

        loader.enqueue(requestBuilder.build())
    }

    fun startGifAnimation() {
        if (!isGif) return
        val d = imageView.drawable
        if (d is android.graphics.drawable.Animatable && !d.isRunning) d.start()
    }

    fun stopGifAnimation() {
        if (!isGif) return
        val d = imageView.drawable
        if (d is android.graphics.drawable.Animatable && d.isRunning) d.stop()
    }

    fun clear() {
        stopGifAnimation()
        imageView.setImageDrawable(null)
    }
}