package com.example.flowave.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@UnstableApi
class FloWaveDataSourceFactory(
    private val context: Context
) : DataSource.Factory {
    private val cacheDataSourceFactory = FloWaveCacheManager.createCacheDataSourceFactory(context)
    private val defaultDataSourceFactory = DefaultDataSource.Factory(context)
    private val streamRepository = InnerTubeRepository.getInstance(context)
    private val logger = FloWaveLogger.getInstance(context)

    override fun createDataSource(): DataSource {
        val cacheDataSource = cacheDataSourceFactory.createDataSource()
        val defaultDataSource = defaultDataSourceFactory.createDataSource()
        
        return object : DataSource {
            private var activeDataSource: DataSource? = null

            override fun addTransferListener(transferListener: TransferListener) {
                cacheDataSource.addTransferListener(transferListener)
                defaultDataSource.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme
                if (scheme == "flowave") {
                    val videoId = dataSpec.uri.lastPathSegment
                        ?.takeIf { it.isNotBlank() }
                        ?: throw java.io.IOException("Missing online track identifier")
                    val resolvedUrl = try {
                        runBlocking(Dispatchers.IO) {
                            withTimeout(45_000L) {
                                streamRepository.getStreamUrl(videoId)
                            }
                        }
                    } catch (error: Exception) {
                        logger.error("online", "data_source_resolution_failed", context = mapOf("videoId" to videoId), throwable = error)
                        throw error
                    }
                    if (resolvedUrl.isBlank() || !resolvedUrl.startsWith("http")) {
                        throw java.io.IOException("Online stream resolver returned an invalid URL")
                    }
                    activeDataSource = cacheDataSource
                    val resolvedSpec = dataSpec.buildUpon()
                        .setUri(Uri.parse(resolvedUrl))
                        .setKey(videoId)
                        .build()
                    return activeDataSource?.open(resolvedSpec) ?: -1L
                }
                activeDataSource = if (scheme == "http" || scheme == "https") cacheDataSource else defaultDataSource
                return activeDataSource?.open(dataSpec) ?: -1L
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return activeDataSource?.read(buffer, offset, length) ?: -1
            }

            override fun getUri(): Uri? {
                return activeDataSource?.uri
            }

            override fun getResponseHeaders(): Map<String, List<String>> {
                return activeDataSource?.responseHeaders ?: emptyMap()
            }

            override fun close() {
                activeDataSource?.close()
                activeDataSource = null
            }
        }
    }
}
