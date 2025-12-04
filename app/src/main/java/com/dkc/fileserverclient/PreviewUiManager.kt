package com.dkc.fileserverclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PreviewUiManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    // UI状态监听器
    interface UiStateListener {
        fun onContainerShown(container: View)
        fun onError(message: String)
        fun onDownloadRequested(fileUrl: String)
    }

    private var uiStateListener: UiStateListener? = null

    fun setUiStateListener(listener: UiStateListener) {
        this.uiStateListener = listener
    }

    // 显示指定的容器
    fun showContainer(
        container: View,
        imageContainer: FrameLayout,
        videoContainer: FrameLayout,
        textContainer: FrameLayout,
        generalContainer: FrameLayout,
        errorTextView: TextView
    ) {
        // 隐藏所有容器
        imageContainer.visibility = View.GONE
        videoContainer.visibility = View.GONE
        textContainer.visibility = View.GONE
        generalContainer.visibility = View.GONE
        errorTextView.visibility = View.GONE

        // 显示指定的容器
        container.visibility = View.VISIBLE

        uiStateListener?.onContainerShown(container)
    }

    // 显示错误
    fun showError(message: String, errorTextView: TextView, containers: List<View>) {
        showContainer(errorTextView, containers[0] as FrameLayout,
            containers[1] as FrameLayout, containers[2] as FrameLayout,
            containers[3] as FrameLayout, errorTextView)
        errorTextView.text = message

        uiStateListener?.onError(message)
    }

    // 设置WebView
    fun setupWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完成，可以在这里通知UI隐藏加载进度
            }
        }
    }

    // 下载文件
    fun downloadFile(fileUrl: String, fileName: String) {
        uiStateListener?.onDownloadRequested(fileUrl)

        coroutineScope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "开始下载: $fileName", Toast.LENGTH_SHORT).show()

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl))
                context.startActivity(intent)
            }
        }
    }

    // 更新文件信息显示
    fun updateFileInfo(
        fileName: String,
        fileType: String,
        fileNameTextView: TextView,
        fileTypeTextView: TextView
    ) {
        fileNameTextView.text = fileName
        fileTypeTextView.text = when (fileType) {
            "image" -> "图片"
            "video" -> "视频"
            "text" -> "文本"
            "audio" -> "音频"
            else -> "文件"
        }
    }
}