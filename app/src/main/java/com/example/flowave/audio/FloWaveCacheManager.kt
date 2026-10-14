package com.example.flowave.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object FloWaveCacheManager {
    private var cacheInstance: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        if (cacheInstance == null) {
            val cacheDir = File(context.cacheDir, "flowave_audio_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val evictor = LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024L) // 512 MB LRU Eviction
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            cacheInstance = SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return cacheInstance ?: throw IllegalStateException("Cache failed to initialize")
    }

    const val ANDROID_VR_USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 2; Build/SQ3A.220605.009.A1) gzip"

    fun createDefaultHttpDataSourceFactory(
        userAgent: String? = null,
        requestHeaders: Map<String, String> = emptyMap()
    ): androidx.media3.datasource.DefaultHttpDataSource.Factory {
        val defaultHeaders = mutableMapOf(
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/"
        )
        defaultHeaders.putAll(requestHeaders)

        return androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: ANDROID_VR_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
            .setDefaultRequestProperties(defaultHeaders)
    }

    /**
     * Creates a CacheDataSource.Factory using DefaultHttpDataSource with ANDROID_VR
     * client headers as the default upstream source, wrapped with DefaultDataSource
     * to support local file/content schemes.
     */
    @Synchronized
    fun createCacheDataSourceFactory(
        context: Context,
        upstreamFactory: androidx.media3.datasource.DataSource.Factory? = null
    ): CacheDataSource.Factory {
        val upstream = upstreamFactory ?: run {
            val httpDataSourceFactory = createDefaultHttpDataSourceFactory()
            DefaultDataSource.Factory(context, httpDataSourceFactory)
        }

        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { dataSpec -> dataSpec.key ?: dataSpec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Synchronized
    fun releaseCache() {
        try {
            cacheInstance?.release()
            cacheInstance = null
        } catch (e: Exception) {
            android.util.Log.e("FloWaveCacheManager", "Failed to release SimpleCache", e)
        }
    }

    @Synchronized
    fun invalidate(key: String) {
        try {
            cacheInstance?.removeResource(key)
        } catch (e: Exception) {
            android.util.Log.w("FloWaveCacheManager", "Failed to invalidate cache key $key", e)
            return
        }
    }
}
