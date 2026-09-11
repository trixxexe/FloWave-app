package com.example.flowave.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
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
                    val resolution = try {
                        runBlocking(Dispatchers.IO) {
                            withTimeout(45_000L) {
                                streamRepository.getStreamResolution(videoId)
                            }
                        }
                    } catch (cancelled: InterruptedException) {
                        Thread.currentThread().interrupt()
                        logger.debug("online", "resolution_cancelled", context = mapOf(
                            "videoId" to videoId,
                            "reason" to "media3_data_source_interrupted"
                        ))
                        throw java.io.IOException("Online stream resolution cancelled", cancelled)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        logger.debug("online", "resolution_cancelled", context = mapOf(
                            "videoId" to videoId,
                            "reason" to "coroutine_cancelled"
                        ))
                        throw java.io.IOException("Online stream resolution cancelled", cancelled)
                    } catch (error: Exception) {
                        logger.error("online", "data_source_resolution_failed", context = mapOf("videoId" to videoId), throwable = error)
                        throw error
                    }
                    if (resolution.url.isBlank() || !resolution.url.startsWith("http")) {
                        throw java.io.IOException("Online stream resolver returned an invalid URL")
                    }
                    activeDataSource = cacheDataSource
                    val resolvedSpec = dataSpec.buildUpon()
                        .setUri(Uri.parse(resolution.url))
                        .setKey(videoId)
                        .build()
                    val openStartedAt = System.nanoTime()
                    val openedLength = try {
                        activeDataSource?.open(resolvedSpec) ?: -1L
                    } catch (error: Exception) {
                        val cancelled = error is InterruptedException || error is kotlinx.coroutines.CancellationException
                        val httpStatus = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        val failureClass = httpStatus?.let { OnlinePlaybackPolicy.classifyHttpStatus(it) }
                            ?: "media_open_failure"
                        if (resolution.candidateKey != null && !cancelled) {
                            streamRepository.markUnplayableStream(videoId, failureClass)
                        }
                        if (cancelled) {
                            logger.debug("online", "stream_open_cancelled", context = mapOf(
                                "videoId" to videoId,
                                "reason" to error::class.simpleName.orEmpty(),
                                "httpStatus" to httpStatus,
                                "failureClass" to failureClass
                            ))
                        } else {
                            logger.error("online", "stream_open_failed", context = mapOf(
                                "videoId" to videoId,
                                "httpStatus" to httpStatus,
                                "failureClass" to failureClass,
                                "errorType" to error::class.simpleName.orEmpty()
                            ), throwable = error)
                        }
                        throw error
                    }
                    val headers = activeDataSource?.responseHeaders.orEmpty()
                    val contentType = headers.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    val contentLength = headers.entries
                        .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    val range = headers.entries
                        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    val acceptRanges = headers.entries
                        .firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    logger.info("online", "stream_open_response", context = mapOf(
                        "videoId" to videoId,
                        "contentType" to contentType.orEmpty().substringBefore(';').trim().lowercase(),
                        "contentEncoding" to activeDataSource?.responseHeaders.orEmpty().entries
                            .firstOrNull { it.key.equals("Content-Encoding", ignoreCase = true) }
                            ?.value?.firstOrNull().orEmpty().take(32),
                        "contentLength" to (contentLength ?: openedLength.toString()),
                        "contentRange" to range.orEmpty().take(80),
                        "acceptRanges" to acceptRanges.orEmpty().take(32),
                        "openedLength" to openedLength,
                        "urlHost" to resolvedSpec.uri.host.orEmpty(),
                        "urlPath" to resolvedSpec.uri.path.orEmpty().take(80),
                        "finalHost" to activeDataSource?.uri?.host.orEmpty()
                    ))
                    if (!com.example.flowave.data.remote.ResolverStreamSelector.isPlayableResponseContentType(contentType)) {
                        activeDataSource?.close()
                        activeDataSource = null
                        streamRepository.markUnplayableStream(videoId, "media_open_failure")
                        streamRepository.invalidateStreamUrl(videoId)
                        FloWaveCacheManager.invalidate(videoId)
                        logger.error("online", "stream_open_rejected", context = mapOf(
                            "videoId" to videoId,
                            "reason" to "non_media_content_type",
                            "contentType" to contentType.orEmpty().substringBefore(';').trim().lowercase()
                        ))
                        throw java.io.IOException("Resolved stream returned non-media content")
                    }
                    logger.info("online", "stream_opened_waiting_for_media3_ready", context = mapOf(
                        "videoId" to videoId,
                        "resolver" to resolution.resolver,
                        "candidateKey" to resolution.candidateKey,
                        "contentType" to contentType.orEmpty().substringBefore(';').trim().lowercase(),
                        "openedLength" to openedLength,
                        "openDurationMs" to ((System.nanoTime() - openStartedAt) / 1_000_000L)
                    ))
                    return openedLength
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
