package com.example.flowave.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

@UnstableApi
class FloWaveDataSourceFactory(
    private val context: Context
) : DataSource.Factory {
    private val cacheDataSourceFactory = FloWaveCacheManager.createCacheDataSourceFactory(context)
    private val defaultDataSourceFactory = DefaultDataSource.Factory(context)

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
                activeDataSource = if (scheme == "http" || scheme == "https") {
                    cacheDataSource
                } else {
                    defaultDataSource
                }
                return activeDataSource!!.open(dataSpec)
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
