package com.dkc.fileserverclient

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Animatable
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.CoroutineScope
import kotlin.math.sqrt

class ImagePreviewManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val imageView: ImageView,
    private val loadingProgress: ProgressBar,
    private val httpClient: okhttp3.OkHttpClient
) {

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 4.0f
    }

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val startPoint = PointF()
    private val midPoint = PointF()
    private var mode = NONE
    private var minScale = MIN_SCALE

    private var tapCount = 0
    private val tapTimeoutMillis = 300L
    private var tapHandler: android.os.Handler? = null

    private var isGif = false
    private var imageWidth = 0f
    private var imageHeight = 0f
    private var viewWidth = 0
    private var viewHeight = 0

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

    fun setTapHandler(handler: android.os.Handler) {
        this.tapHandler = handler
    }

    // 加载图片
    fun loadImage(imageUrl: String, fileName: String) {
        isGif = fileName.endsWith(".gif", ignoreCase = true)

        listener?.onImageLoadStart()
        loadingProgress.visibility = View.VISIBLE

        Log.d("ImagePreviewManager", "开始加载图片: $fileName, isGif: $isGif")

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
                    Log.d("ImagePreviewManager", "图片加载成功，isGif: $isGif")
                    listener?.onImageLoadSuccess(isGif)
                    loadingProgress.visibility = View.GONE

                    if (isGif) {
                        // GIF使用默认的缩放类型
                        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
                        // 清除之前的变换矩阵
                        imageView.imageMatrix = Matrix()

                        // 确保GIF动画开始
                        val drawable = imageView.drawable
                        if (drawable is Animatable && !drawable.isRunning) {
                            Log.d("ImagePreviewManager", "开始播放GIF动画")
                            (drawable as Animatable).start()
                        }
                    } else {
                        // 非GIF图片，设置触摸监听和缩放矩阵
                        setupImageForZoom()
                    }
                },
                onError = { _, result ->
                    loadingProgress.visibility = View.GONE
                    Log.e("ImagePreviewManager", "图片加载失败", result.throwable)
                    listener?.onImageLoadError("图片加载失败: ${result.throwable.message}")
                }
            )

        if (!isGif) {
            requestBuilder.transformations(RoundedCornersTransformation(8f))
        } else {
            requestBuilder
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .allowHardware(false)
        }

        val request = requestBuilder.build()
        imageLoader.enqueue(request)
    }

    private fun setupImageForZoom() {
        // 等待ImageView布局完成
        tapHandler?.postDelayed({
            viewWidth = imageView.width
            viewHeight = imageView.height
            val drawable = imageView.drawable

            if (drawable != null) {
                imageWidth = drawable.intrinsicWidth.toFloat()
                imageHeight = drawable.intrinsicHeight.toFloat()

                Log.d("ImagePreviewManager", "图片尺寸: ${imageWidth}x${imageHeight}, 视图尺寸: ${viewWidth}x${viewHeight}")

                if (imageWidth > 0 && imageHeight > 0) {
                    // 计算适合屏幕的缩放比例
                    val scaleX = viewWidth / imageWidth
                    val scaleY = viewHeight / imageHeight
                    minScale = minOf(scaleX, scaleY).coerceAtMost(1.0f)

                    Log.d("ImagePreviewManager", "最小缩放比例: $minScale")

                    // 设置初始矩阵以显示全图
                    matrix.reset()
                    matrix.setScale(minScale, minScale)
                    matrix.postTranslate(
                        (viewWidth - imageWidth * minScale) / 2,
                        (viewHeight - imageHeight * minScale) / 2
                    )

                    imageView.scaleType = ImageView.ScaleType.MATRIX
                    imageView.imageMatrix = matrix

                    // 设置触摸监听器
                    setupTouchListener()
                }
            }
        }, 100)
    }

    private fun setupTouchListener() {
        Log.d("ImagePreviewManager", "设置触摸监听器")

        imageView.setOnTouchListener { _, event ->
            if (isGif) {
                // GIF不处理缩放，交给默认的点击处理
                return@setOnTouchListener false
            }

            handleImageTouch(event)
        }
    }

    private fun handleImageTouch(event: MotionEvent): Boolean {
        Log.d("ImagePreviewManager", "触摸事件: ${event.action}, 模式: $mode")

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                // 单点触摸开始
                savedMatrix.set(matrix)
                startPoint.set(event.x, event.y)
                mode = DRAG
                Log.d("ImagePreviewManager", "ACTION_DOWN: 开始拖动")
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多点触摸开始
                val oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(midPoint, event)
                    mode = ZOOM
                    Log.d("ImagePreviewManager", "ACTION_POINTER_DOWN: 开始缩放")
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    // 拖动图片
                    matrix.set(savedMatrix)
                    matrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                    imageView.imageMatrix = matrix
                    Log.d("ImagePreviewManager", "ACTION_MOVE: 拖动")
                } else if (mode == ZOOM) {
                    // 缩放图片
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        matrix.set(savedMatrix)
                        val scale = newDist / spacing(event, savedMatrix)

                        val values = FloatArray(9)
                        matrix.getValues(values)
                        val currentScale = values[Matrix.MSCALE_X]

                        // 限制缩放范围
                        val newScale = currentScale * scale
                        if (newScale in minScale..MAX_SCALE) {
                            matrix.postScale(scale, scale, midPoint.x, midPoint.y)
                            imageView.imageMatrix = matrix
                            Log.d("ImagePreviewManager", "ACTION_MOVE: 缩放, 当前比例: $currentScale, 新比例: $newScale")
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 触摸结束
                Log.d("ImagePreviewManager", "触摸结束")
                mode = NONE

                // 处理双击事件
                if (event.action == MotionEvent.ACTION_UP) {
                    handleTapEvent()
                }
            }
        }

        return true
    }

    private fun handleTapEvent() {
        tapCount++
        Log.d("ImagePreviewManager", "点击次数: $tapCount")

        if (tapCount == 1) {
            tapHandler?.postDelayed({
                if (tapCount == 1) {
                    // 单击
                    tapCount = 0
                    Log.d("ImagePreviewManager", "单击事件")
                }
            }, tapTimeoutMillis)
        } else if (tapCount == 2) {
            // 双击 - 切换缩放状态
            tapHandler?.removeCallbacksAndMessages(null)
            handleDoubleTap()
            tapCount = 0
        }
    }

    private fun handleDoubleTap() {
        Log.d("ImagePreviewManager", "双击事件")

        val values = FloatArray(9)
        matrix.getValues(values)
        val currentScale = values[Matrix.MSCALE_X]

        if (currentScale > minScale) {
            // 如果当前已缩放，则重置到最小缩放
            matrix.reset()
            matrix.setScale(minScale, minScale)
            matrix.postTranslate(
                (viewWidth - imageWidth * minScale) / 2,
                (viewHeight - imageHeight * minScale) / 2
            )
            Log.d("ImagePreviewManager", "重置到最小缩放: $minScale")
        } else {
            // 如果当前是最小缩放，则缩放到最大缩放
            val scale = MAX_SCALE / minScale
            matrix.postScale(scale, scale, viewWidth / 2f, viewHeight / 2f)
            Log.d("ImagePreviewManager", "缩放到最大缩放: $MAX_SCALE")
        }

        imageView.imageMatrix = matrix
        listener?.onDoubleTap()
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun spacing(event: MotionEvent, matrix: Matrix): Float {
        val point1 = floatArrayOf(event.getX(0), event.getY(0))
        val point2 = floatArrayOf(event.getX(1), event.getY(1))
        matrix.mapPoints(point1)
        matrix.mapPoints(point2)
        val x = point1[0] - point2[0]
        val y = point1[1] - point2[1]
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    fun startGifAnimation() {
        if (isGif) {
            val drawable = imageView.drawable
            if (drawable is Animatable && !drawable.isRunning) {
                Log.d("ImagePreviewManager", "开始播放GIF动画")
                (drawable as Animatable).start()
            }
        }
    }

    fun stopGifAnimation() {
        if (isGif) {
            val drawable = imageView.drawable
            if (drawable is Animatable && drawable.isRunning) {
                Log.d("ImagePreviewManager", "停止GIF动画")
                drawable.stop()
            }
        }
    }

    fun clear() {
        tapHandler?.removeCallbacksAndMessages(null)
        tapCount = 0
        mode = NONE
        matrix.reset()
        imageView.imageMatrix = matrix
        stopGifAnimation()
    }
}