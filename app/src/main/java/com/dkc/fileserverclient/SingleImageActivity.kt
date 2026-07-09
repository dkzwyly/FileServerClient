package com.dkc.fileserverclient

import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import coil.load
import coil.request.CachePolicy

class SingleImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_single_image)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        imageView = findViewById(R.id.imageView)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)

        val imageUrl = intent.getStringExtra("IMAGE_URL") ?: ""
        if (imageUrl.isEmpty()) {
            showError("图片地址为空")
            return
        }

        loadImage(imageUrl)
    }

    private fun loadImage(url: String) {
        progressBar.isVisible = true
        errorText.isVisible = false

        val imageLoader = coil.ImageLoader.Builder(this)
            .okHttpClient(UnsafeHttpClient.createUnsafeOkHttpClient())
            .build()

        val request = coil.request.ImageRequest.Builder(this)
            .data(url)
            .target(imageView)
            .placeholder(R.drawable.ic_image_placeholder)
            .error(R.drawable.ic_image_placeholder)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .listener(
                onStart = { progressBar.isVisible = true },
                onSuccess = { _, _ -> progressBar.isVisible = false },
                onError = { _, _ ->
                    progressBar.isVisible = false
                    showError("加载图片失败")
                }
            )
            .build()

        imageLoader.enqueue(request)
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.isVisible = true
        progressBar.isVisible = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}