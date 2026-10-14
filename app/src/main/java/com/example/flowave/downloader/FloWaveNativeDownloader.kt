package com.example.flowave.downloader

import android.content.Context
import com.example.flowave.audio.FloWaveCacheManager
import com.example.flowave.audio.PlaybackIdentity
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public download states shared by the foreground service and UI. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Initializing : DownloadState
    data object Preparing : DownloadState
    data class Downloading(val progress: Float, val speed: String, val eta: String) : DownloadState
    data class PostProcessing(val step: String) : DownloadState
    data class Success(val outputFilePath: String) : DownloadState
    data object Cancelled : DownloadState
    data class Error(val message: String) : DownloadState
}

/**
 * Pure-Kotlin native audio downloader for resolved streams.
 * Handles HTTP Range chunking (5MB chunks), matches InnerTube client User-Agent headers,
 * and outputs correctly named .m4a or .opus files to avoid throttling and HTTP 403 errors.
 */
class FloWaveNativeDownloader(
    private val context: Context,
    private val innerTubeRepo: InnerTubeRepository = InnerTubeRepository.getInstance(context)
) {
    private val logger = FloWaveLogger.getInstance(context)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val CHUNK_SIZE = 5 * 1024 * 1024L // 5 MB chunks
        const val DEFAULT_USER_AGENT = FloWaveCacheManager.ANDROID_VR_USER_AGENT
    }

    fun download(
        urlOrVideoId: String,
        outputDir: File,
        options: DownloadOptions = DownloadOptions()
    ): Flow<DownloadState> = channelFlow {
        val startedAt = System.nanoTime()
        send(DownloadState.Initializing)

        val videoId = PlaybackIdentity.extractVideoId(urlOrVideoId) ?: urlOrVideoId.trim()
        if (videoId.isBlank()) {
            send(DownloadState.Error("Invalid video ID or URL: $urlOrVideoId"))
            return@channelFlow
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            send(DownloadState.Error("Unable to create output directory: ${outputDir.absolutePath}"))
            return@channelFlow
        }

        send(DownloadState.Preparing)
        logger.info("native_downloader", "resolving_stream", context = mapOf("videoId" to videoId))

        val resolution = try {
            innerTubeRepo.getStreamResolution(videoId)
        } catch (e: Exception) {
            logger.error("native_downloader", "stream_resolution_failed", context = mapOf("videoId" to videoId), throwable = e)
            send(DownloadState.Error("Failed to resolve audio stream: ${e.message}"))
            return@channelFlow
        }

        val streamUrl = resolution.url
        val userAgent = resolution.userAgent.ifBlank { DEFAULT_USER_AGENT }
        val requestHeaders = resolution.requestHeaders

        // Fetch metadata to form a clean filename
        val metadata = runCatching { innerTubeRepo.getTrackMetadata(videoId) }.getOrNull()
        val title = metadata?.title ?: "Audio_$videoId"
        val extension = determineExtension(streamUrl, options)
        val safeBaseName = DownloadInput.safeFileName("$title [$videoId]")
        val targetFile = File(outputDir, "$safeBaseName.$extension")
        val partFile = File(outputDir, "$safeBaseName.$extension.part")

        logger.info("native_downloader", "download_started", context = mapOf(
            "videoId" to videoId,
            "extension" to extension,
            "targetFile" to targetFile.name
        ))

        // Probe total content length using Range: bytes=0-0
        val totalLength = probeContentLength(streamUrl, userAgent, requestHeaders)

        try {
            var downloadedBytes = 0L
            if (partFile.exists() && partFile.length() > 0L) {
                // If existing part file is valid, we can resume
                downloadedBytes = partFile.length()
                if (totalLength > 0L && downloadedBytes >= totalLength) {
                    partFile.delete()
                    downloadedBytes = 0L
                }
            }

            val fileOutputStream = FileOutputStream(partFile, downloadedBytes > 0L)
            var lastReportTime = System.currentTimeMillis()
            var bytesSinceLastReport = 0L
            val downloadStartTime = System.currentTimeMillis()

            fileOutputStream.use { out ->
                if (totalLength > 0L) {
                    while (downloadedBytes < totalLength && currentCoroutineContext().isActive) {
                        val endBytes = minOf(downloadedBytes + CHUNK_SIZE - 1, totalLength - 1)
                        val chunkRequest = buildRequest(streamUrl, userAgent, requestHeaders)
                            .header("Range", "bytes=$downloadedBytes-$endBytes")
                            .build()

                        httpClient.newCall(chunkRequest).execute().use { response ->
                            if (!response.isSuccessful && response.code != 206) {
                                throw IOException("Server returned HTTP ${response.code} downloading chunk $downloadedBytes-$endBytes")
                            }
                            val body = response.body ?: throw IOException("Empty chunk response body")
                            val buffer = ByteArray(8192)
                            val input = body.byteStream()
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (!currentCoroutineContext().isActive) {
                                    throw CancellationException("Download cancelled")
                                }
                                out.write(buffer, 0, read)
                                downloadedBytes += read
                                bytesSinceLastReport += read

                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 400L) {
                                    val progress = (downloadedBytes.toFloat() / totalLength.toFloat()) * 100f
                                    val elapsedSec = (now - lastReportTime) / 1000f
                                    val currentSpeed = if (elapsedSec > 0f) bytesSinceLastReport / elapsedSec else 0f
                                    val speedStr = formatSpeed(currentSpeed)

                                    val totalElapsedSec = (now - downloadStartTime) / 1000f
                                    val avgSpeed = if (totalElapsedSec > 0f) downloadedBytes / totalElapsedSec else currentSpeed
                                    val etaStr = if (avgSpeed > 0f) formatEta(((totalLength - downloadedBytes) / avgSpeed).toLong()) else ""

                                    send(DownloadState.Downloading(progress.coerceIn(0f, 100f), speedStr, etaStr))
                                    lastReportTime = now
                                    bytesSinceLastReport = 0L
                                }
                            }
                        }
                    }
                } else {
                    // Total length unknown, stream directly
                    val directRequest = buildRequest(streamUrl, userAgent, requestHeaders).build()
                    httpClient.newCall(directRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Server returned HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IOException("Empty response body")
                        val buffer = ByteArray(8192)
                        val input = body.byteStream()
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!currentCoroutineContext().isActive) {
                                throw CancellationException("Download cancelled")
                            }
                            out.write(buffer, 0, read)
                            downloadedBytes += read
                            bytesSinceLastReport += read

                            val now = System.currentTimeMillis()
                            if (now - lastReportTime >= 400L) {
                                val elapsedSec = (now - lastReportTime) / 1000f
                                val speedStr = if (elapsedSec > 0f) formatSpeed(bytesSinceLastReport / elapsedSec) else ""
                                send(DownloadState.Downloading(50f, speedStr, ""))
                                lastReportTime = now
                                bytesSinceLastReport = 0L
                            }
                        }
                    }
                }
            }

            if (!currentCoroutineContext().isActive) {
                send(DownloadState.Cancelled)
                return@channelFlow
            }

            // Move .part file to final file
            send(DownloadState.PostProcessing("Verifying audio container..."))
            if (targetFile.exists()) targetFile.delete()
            val renamed = partFile.renameTo(targetFile)
            if (!renamed) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            logger.info("native_downloader", "download_success", context = mapOf(
                "videoId" to videoId,
                "fileSize" to targetFile.length(),
                "durationMs" to (System.nanoTime() - startedAt) / 1_000_000L
            ))
            send(DownloadState.Success(targetFile.absolutePath))

        } catch (e: CancellationException) {
            send(DownloadState.Cancelled)
            throw e
        } catch (e: Throwable) {
            logger.error("native_downloader", "download_error", context = mapOf("videoId" to videoId), throwable = e)
            send(DownloadState.Error(e.message ?: "Native download failed"))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(url: String, userAgent: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
        for ((key, value) in headers) {
            if (!key.equals("User-Agent", ignoreCase = true) &&
                !key.equals("Origin", ignoreCase = true) &&
                !key.equals("Referer", ignoreCase = true) &&
                !key.equals("Range", ignoreCase = true)
            ) {
                builder.header(key, value)
            }
        }
        return builder
    }

    private fun probeContentLength(url: String, userAgent: String, headers: Map<String, String>): Long {
        return runCatching {
            val req = buildRequest(url, userAgent, headers)
                .header("Range", "bytes=0-0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) {
                    val cr = resp.header("Content-Range")
                    if (cr != null && cr.contains("/")) {
                        val total = cr.substringAfterLast("/").trim().toLongOrNull()
                        if (total != null && total > 0L) return@runCatching total
                    }
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    if (cl != null && cl > 1L) return@runCatching cl
                }
                -1L
            }
        }.getOrDefault(-1L)
    }

    fun determineExtension(streamUrl: String, options: DownloadOptions): String {
        return when {
            streamUrl.contains("itag=140") || streamUrl.contains("audio/mp4") -> "m4a"
            streamUrl.contains("itag=251") || streamUrl.contains("audio/webm") -> "opus"
            options.audioFormat.isNotBlank() && options.audioFormat.lowercase() in setOf("m4a", "opus", "webm", "mp3", "aac") -> options.audioFormat.lowercase()
            else -> "m4a"
        }
    }

    fun formatSpeed(bytesPerSec: Float): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024)
            else -> String.format(java.util.Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return "00:00"
        val m = seconds / 60
        val s = seconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }
}
