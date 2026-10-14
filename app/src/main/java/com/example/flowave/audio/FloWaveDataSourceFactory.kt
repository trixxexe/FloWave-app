package com.example.flowave.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.remote.ResolvedStreamSource
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@OptIn(UnstableApi::class)
fun DataSpec.withKey(key: String): DataSpec = buildUpon().setKey(key).build()

@UnstableApi
class FloWaveDataSourceFactory(
    private val context: Context,
    private val streamRepository: InnerTubeRepository = InnerTubeRepository.getInstance(context)
) : DataSource.Factory {

    companion object {
        const val ANDROID_VR_USER_AGENT = FloWaveCacheManager.ANDROID_VR_USER_AGENT
    }

    private val logger = FloWaveLogger.getInstance(context)
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val defaultHttpDataSourceFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(ANDROID_VR_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10000)
        .setReadTimeoutMs(15000)
        .setDefaultRequestProperties(
            mapOf(
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
        )

    val defaultDataSourceFactory: DefaultDataSource.Factory = DefaultDataSource.Factory(context, defaultHttpDataSourceFactory)

    val cacheDataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(FloWaveCacheManager.getCache(context))
        .setUpstreamDataSourceFactory(defaultDataSourceFactory)
        .setCacheKeyFactory { dataSpec -> dataSpec.key ?: dataSpec.uri.toString() }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // Tracks in-flight resolution jobs/futures per DataSource instance for clean close() cancellation
    private val activeResolutionJobs = ConcurrentHashMap<DataSource, Job>()
    private val activeResolutionFutures = ConcurrentHashMap<DataSource, CompletableFuture<ResolvedStreamSource>>()

    @Throws(IOException::class)
    fun resolveStreamDataSpec(dataSpec: DataSpec, sourceInstance: DataSource? = null): DataSpec {
        val uri = dataSpec.uri
        val scheme = uri.scheme?.lowercase()
        if (PlaybackIdentity.isLocalUri(uri)) {
            return dataSpec
        }

        val videoId = dataSpec.key ?: PlaybackIdentity.extractVideoId(uri)
        if (scheme == "flowave" && videoId.isNullOrBlank()) {
            throw IOException("Missing online track identifier")
        }

        if (videoId.isNullOrBlank()) {
            if ((scheme == "http" || scheme == "https") && !PlaybackIdentity.isOnlineUri(uri)) {
                return dataSpec
            }
            throw IOException("Missing online track identifier")
        }

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
        if (sourceInstance != null) {
            activeResolutionJobs[sourceInstance] = job
            activeResolutionFutures[sourceInstance] = future
        }

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
            throw IOException("Online stream resolution cancelled", interrupted)
        } catch (cancelled: CancellationException) {
            job.cancel()
            logger.debug("online", "resolution_cancelled", context = mapOf(
                "videoId" to videoId,
                "reason" to "coroutine_cancelled"
            ))
            throw IOException("Online stream resolution cancelled", cancelled)
        } catch (timeout: TimeoutException) {
            job.cancel()
            future.cancel(true)
            logger.error("online", "resolution_timeout", context = mapOf("videoId" to videoId))
            throw IOException("Online stream resolution timed out", timeout)
        } catch (execution: ExecutionException) {
            val cause = execution.cause ?: execution
            if (cause is kotlinx.coroutines.CancellationException || cause is InterruptedException) {
                logger.debug("online", "resolution_cancelled", context = mapOf(
                    "videoId" to videoId,
                    "reason" to "coroutine_cancelled"
                ))
                throw IOException("Online stream resolution cancelled", cause)
            }
            logger.error("online", "data_source_resolution_failed", context = mapOf("videoId" to videoId), throwable = cause)
            if (cause is IOException) throw cause
            throw IOException(cause.message ?: "Online stream resolution failed", cause)
        } finally {
            if (sourceInstance != null) {
                activeResolutionJobs.remove(sourceInstance)
                activeResolutionFutures.remove(sourceInstance)
            }
        }

        if (resolution.url.isBlank() || !resolution.url.startsWith("http")) {
            throw IOException("Online stream resolver returned an invalid URL")
        }

        val headers = mutableMapOf(
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/"
        )
        val ua = resolution.userAgent ?: ANDROID_VR_USER_AGENT
        headers["User-Agent"] = ua
        headers.putAll(resolution.requestHeaders)

        val resolvedUri = Uri.parse(resolution.url)
        return dataSpec.buildUpon()
            .setUri(resolvedUri)
            .setKey(videoId)
            .setHttpRequestHeaders(headers)
            .build()
            .withUri(resolvedUri)
            .withKey(videoId)
    }

    val resolvingDataSourceFactory: ResolvingDataSource.Factory = ResolvingDataSource.Factory(
        cacheDataSourceFactory,
        object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                return resolveStreamDataSpec(dataSpec)
            }
        }
    )

    override fun createDataSource(): DataSource {
        return FloWaveTrackDataSource(
            cacheDataSource = cacheDataSourceFactory.createDataSource(),
            defaultDataSource = defaultDataSourceFactory.createDataSource()
        )
    }

    private inner class FloWaveTrackDataSource(
        private val cacheDataSource: DataSource,
        private val defaultDataSource: DataSource
    ) : DataSource {
        private var activeDataSource: DataSource? = null
        private val resolvingDataSource: ResolvingDataSource = ResolvingDataSource(
            cacheDataSource,
            object : ResolvingDataSource.Resolver {
                override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                    return resolveStreamDataSpec(dataSpec, this@FloWaveTrackDataSource)
                }
            }
        )

        override fun addTransferListener(transferListener: TransferListener) {
            cacheDataSource.addTransferListener(transferListener)
            defaultDataSource.addTransferListener(transferListener)
            resolvingDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val isLocal = PlaybackIdentity.isLocalUri(dataSpec.uri)
            if (isLocal) {
                activeDataSource = defaultDataSource
                return defaultDataSource.open(dataSpec)
            }

            activeDataSource = resolvingDataSource
            val videoId = dataSpec.key ?: PlaybackIdentity.extractVideoId(dataSpec.uri)
            val openStartedAt = System.nanoTime()

            val openedLength = try {
                resolvingDataSource.open(dataSpec)
            } catch (error: Exception) {
                val cancelled = error is InterruptedException ||
                        error is kotlinx.coroutines.CancellationException ||
                        error.message?.contains("cancelled", ignoreCase = true) == true
                val httpStatus = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val failureClass = httpStatus?.let { OnlinePlaybackPolicy.classifyHttpStatus(it) }
                    ?: "media_open_failure"

                if (!cancelled && videoId != null) {
                    streamRepository.markUnplayableStream(videoId, failureClass)
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

            val headers = resolvingDataSource.responseHeaders
            val contentType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull()

            if (!OnlinePlaybackPolicy.isPlayableResponseContentType(contentType)) {
                resolvingDataSource.close()
                activeDataSource = null
                if (videoId != null) {
                    streamRepository.markUnplayableStream(videoId, "media_open_failure")
                    streamRepository.invalidateStreamUrl(videoId)
                    FloWaveCacheManager.invalidate(videoId)
                }
                logger.error("online", "stream_open_rejected", context = mapOf(
                    "videoId" to videoId,
                    "reason" to "non_media_content_type",
                    "contentType" to contentType.orEmpty().substringBefore(';').trim().lowercase()
                ))
                throw IOException("Resolved stream returned non-media content")
            }

            logger.info("online", "stream_opened_waiting_for_media3_ready", context = mapOf(
                "videoId" to videoId,
                "contentType" to contentType.orEmpty().substringBefore(';').trim().lowercase(),
                "openedLength" to openedLength,
                "openDurationMs" to ((System.nanoTime() - openStartedAt) / 1_000_000L)
            ))

            return openedLength
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = activeDataSource?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            activeDataSource?.responseHeaders ?: emptyMap()

        override fun close() {
            activeResolutionJobs.remove(this)?.cancel()
            activeResolutionFutures.remove(this)?.cancel(true)
            activeDataSource?.close()
            activeDataSource = null
        }
    }
}
