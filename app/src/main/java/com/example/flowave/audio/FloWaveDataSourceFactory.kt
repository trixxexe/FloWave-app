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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.remote.ResolvedStreamSource
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@UnstableApi
class FloWaveDataSourceFactory(
    private val context: Context,
    private val streamRepository: InnerTubeRepository = InnerTubeRepository.getInstance(context)
) : DataSource.Factory {
    private val cacheDataSourceFactory = FloWaveCacheManager.createCacheDataSourceFactory(context)
    private val defaultDataSourceFactory = DefaultDataSource.Factory(context)
    private val logger = FloWaveLogger.getInstance(context)
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Creates a dedicated OkHttpDataSource for fetching the resolved YouTube
     * stream URL. This bypasses the cache layer for the initial request,
     * avoiding CacheDataSource header-forwarding issues while still being
     * able to read from cache on subsequent requests.
     *
     * YouTube CDN requires:
     * 1. User-Agent matching the client that resolved the URL
     * 2. Valid Referer/Origin headers
     * 3. Proper redirect following
     */
    private fun createStreamDataSource(): OkHttpDataSource {
        val streamClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        return OkHttpDataSource.Factory(streamClient)
            .setUserAgent(com.example.flowave.utils.FloWaveConstants.USER_AGENT_ANDROID)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
            )
            .createDataSource()
    }

    override fun createDataSource(): DataSource {
        val cacheDataSource = cacheDataSourceFactory.createDataSource()
        val defaultDataSource = defaultDataSourceFactory.createDataSource()
        
        return object : DataSource {
            private var activeDataSource: DataSource? = null
            @Volatile private var activeJob: Job? = null
            @Volatile private var activeFuture: CompletableFuture<ResolvedStreamSource>? = null

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

                    val future = CompletableFuture<ResolvedStreamSource>()
                    val job = resolutionScope.launch {
                        try {
                            val resolved = withTimeout(45_000L) {
                                streamRepository.getStreamResolution(videoId)
                            }
                            future.complete(resolved)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            future.completeExceptionally(cancelled)
                        } catch (error: Throwable) {
                            future.completeExceptionally(error)
                        }
                    }
                    activeJob = job
                    activeFuture = future

                    val resolution = try {
                        future.get(45_000L, TimeUnit.MILLISECONDS)
                    } catch (interrupted: InterruptedException) {
                        job.cancel()
                        future.cancel(true)
                        Thread.currentThread().interrupt()
                        logger.debug("online", "resolution_cancelled", context = mapOf(
                            "videoId" to videoId,
                            "reason" to "media3_data_source_interrupted"
                        ))
                        throw java.io.IOException("Online stream resolution cancelled", interrupted)
                    } catch (cancelled: CancellationException) {
                        job.cancel()
                        logger.debug("online", "resolution_cancelled", context = mapOf(
                            "videoId" to videoId,
                            "reason" to "coroutine_cancelled"
                        ))
                        throw java.io.IOException("Online stream resolution cancelled", cancelled)
                    } catch (timeout: TimeoutException) {
                        job.cancel()
                        future.cancel(true)
                        logger.error("online", "resolution_timeout", context = mapOf("videoId" to videoId))
                        throw java.io.IOException("Online stream resolution timed out", timeout)
                    } catch (execution: ExecutionException) {
                        val cause = execution.cause ?: execution
                        if (cause is kotlinx.coroutines.CancellationException || cause is InterruptedException) {
                            logger.debug("online", "resolution_cancelled", context = mapOf(
                                "videoId" to videoId,
                                "reason" to "coroutine_cancelled"
                            ))
                            throw java.io.IOException("Online stream resolution cancelled", cause)
                        }
                        logger.error("online", "data_source_resolution_failed", context = mapOf("videoId" to videoId), throwable = cause)
                        if (cause is java.io.IOException) throw cause
                        throw java.io.IOException(cause.message ?: "Online stream resolution failed", cause)
                    } finally {
                        activeJob = null
                        activeFuture = null
                    }

                    if (resolution.url.isBlank() || !resolution.url.startsWith("http")) {
                        throw java.io.IOException("Online stream resolver returned an invalid URL")
                    }

                    // Use a dedicated OkHttpDataSource for YouTube streams.
                    // This avoids CacheDataSource wrapping issues where
                    // getResponseHeaders() returns empty maps and YouTube CDN
                    // responses are not properly handled. The stream will still
                    // benefit from OkHttp's connection pooling and proper
                    // redirect following.
                    val streamDataSource = createStreamDataSource()
                    activeDataSource = streamDataSource

                    val resolvedSpec = dataSpec.buildUpon()
                        .setUri(Uri.parse(resolution.url))
                        .setKey(videoId)
                        .build()
                    val openStartedAt = System.nanoTime()
                    val openedLength = try {
                        streamDataSource.open(resolvedSpec)
                    } catch (error: Exception) {
                        val cancelled = error is InterruptedException || error is kotlinx.coroutines.CancellationException
                        val httpStatus = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        val failureClass = httpStatus?.let { OnlinePlaybackPolicy.classifyHttpStatus(it) }
                            ?: "media_open_failure"
                        if (!cancelled) {
                            if (resolution.candidateKey != null) {
                                streamRepository.markUnplayableStream(videoId, failureClass)
                            }
                            streamRepository.invalidateStreamUrl(videoId)
                            FloWaveCacheManager.invalidate(videoId)
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

                    // OkHttpDataSource properly exposes response headers
                    val headers = streamDataSource.responseHeaders
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
                        "contentEncoding" to headers.entries
                            .firstOrNull { it.key.equals("Content-Encoding", ignoreCase = true) }
                            ?.value?.firstOrNull().orEmpty().take(32),
                        "contentLength" to (contentLength ?: openedLength.toString()),
                        "contentRange" to range.orEmpty().take(80),
                        "acceptRanges" to acceptRanges.orEmpty().take(32),
                        "openedLength" to openedLength,
                        "urlHost" to resolvedSpec.uri.host.orEmpty(),
                        "urlPath" to resolvedSpec.uri.path.orEmpty().take(80),
                        "finalHost" to streamDataSource.uri?.host.orEmpty()
                    ))
                    if (!com.example.flowave.data.remote.ResolverStreamSelector.isPlayableResponseContentType(contentType)) {
                        streamDataSource.close()
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
                activeJob?.cancel()
                activeJob = null
                activeFuture?.cancel(true)
                activeFuture = null
                activeDataSource?.close()
                activeDataSource = null
            }
        }
    }
}
