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

    /**
     * Creates a CacheDataSource.Factory using OkHttpDataSource as the upstream
     * HTTP source. This matches how InnerTune, ViMusic and other working
     * YouTube Music clients configure their Media3 pipeline.
     *
     * OkHttpDataSource handles YouTube CDN responses (redirects, partial
     * content, chunked transfer, connection reuse) more reliably than
     * DefaultHttpDataSource which uses HttpURLConnection.
     */
    @Synchronized
    fun createCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val streamClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(streamClient)
            .setUserAgent(com.example.flowave.utils.FloWaveConstants.USER_AGENT_ANDROID)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
            )

        val upstreamFactory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
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
