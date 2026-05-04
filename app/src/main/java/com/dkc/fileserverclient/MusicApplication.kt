package com.dkc.fileserverclient

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient

/**
 * 统一持有安全的 OkHttpClient，避免重复创建
 */
object UnsafeHttpClientHolder {
    val client: OkHttpClient by lazy {
        UnsafeHttpClient.createUnsafeOkHttpClient()  // 你现有的信任所有证书的客户端
    }
}

class MusicApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // 初始化封面本地存储（使用统一的 OkHttpClient）
        CoverImageStorage.init(this, UnsafeHttpClientHolder.client)

        // 无需手动调用 Coil.setImageLoader，实现 ImageLoaderFactory 后 Coil 会自动获取
    }

    /**
     * Coil 将通过此方法创建全局默认的 ImageLoader
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(UnsafeHttpClientHolder.client)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizePercent(0.02)  // 最多占磁盘 2%
                    .build()
            }
            .build()
    }
}