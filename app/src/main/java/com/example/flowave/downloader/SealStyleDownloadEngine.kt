package com.example.flowave.downloader

import android.content.Context
import android.util.Log
import com.example.flowave.FloWaveRuntime
import com.example.flowave.audio.PlaybackIdentity
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.diagnostics.FloWaveLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * FloWave's GPL-3.0 download adapter.
 *
 * This uses the embedded yt-dlp/FFmpeg Android runtime shipped by the
 * GPL-3.0 youtubedl-android project (the same runtime family used by Seal).
 * If the yt-dlp binary is a stub/placeholder or fails execution, it automatically
 * and cleanly falls back to pure-Kotlin native InnerTube resolution and download.
 */
class SealStyleDownloadEngine(private val appContext: Context? = null) {
    private val logger = appContext?.applicationContext?.let { FloWaveLogger.getInstance(it) }

    fun isAvailable(): Boolean {
        val ctx = appContext ?: com.example.flowave.FloWaveApplication.appContext
        return YtDlpBinaryValidator.isRuntimeAvailable(ctx)
    }

    suspend fun inspect(input: String, allowFallbackToNative: Boolean = true): Result<List<DownloadMediaInfo>> = withContext(Dispatchers.IO) {
        val operation = if (DownloadInput.isHttpUrl(input)) "url_metadata" else "keyword_search"
        val startedAt = System.nanoTime()
        val ctx = appContext ?: com.example.flowave.FloWaveApplication.appContext
        logger?.info("downloader", "inspect_started", context = mapOf(
            "operation" to operation,
            "inputLength" to input.length
        ))

        // If embedded runtime is a stub or unavailable, fall back to pure-Kotlin native search
        if (!isAvailable() || !FloWaveRuntime.awaitReady(2_000L)) {
            logger?.info("downloader", "inspect_stub_or_unavailable_fallback_native", context = mapOf("input" to input))
            if (allowFallbackToNative && ctx != null) {
                return@withContext fallbackNativeInspect(ctx, input)
            }
            val error = IllegalStateException("The embedded yt-dlp runtime is unavailable")
            return@withContext Result.failure(error)
        }

        try {
            val request = YoutubeDLRequest(DownloadInput.sourceFor(input)).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--extractor-args", "youtube:player_client=ios,android")
            }
            val output = withTimeout(90_000L) {
                runInterruptible(Dispatchers.IO) {
                    executeWithUpdateRetry(
                        request, "flowave-inspect-${UUID.randomUUID()}", null
                    ).out
                }
            }
            val results = parseMediaInfo(output)
            if (results.isEmpty()) error("yt-dlp returned no media results")
            logger?.info("downloader", "inspect_succeeded", context = mapOf(
                "operation" to operation,
                "resultCount" to results.size,
                "durationMs" to elapsedMs(startedAt)
            ))
            Result.success(results)
        } catch (error: TimeoutCancellationException) {
            logger?.error("downloader", "inspect_failed", context = mapOf(
                "operation" to operation,
                "failureClass" to "timeout",
                "durationMs" to elapsedMs(startedAt)
            ), throwable = error)
            if (allowFallbackToNative && ctx != null) {
                return@withContext fallbackNativeInspect(ctx, input)
            }
            Result.failure(IllegalStateException("yt-dlp inspection timed out", error))
        } catch (error: CancellationException) {
            logger?.debug("downloader", "inspect_cancelled", context = mapOf("operation" to operation))
            throw error
        } catch (error: Throwable) {
            logger?.warn("downloader", "inspect_failed_fallback_native", context = mapOf(
                "operation" to operation,
                "failureClass" to error::class.simpleName.orEmpty(),
                "durationMs" to elapsedMs(startedAt)
            ), throwable = error)
            if (allowFallbackToNative && ctx != null) {
                return@withContext fallbackNativeInspect(ctx, input)
            }
            Result.failure(error)
        }
    }

    private suspend fun fallbackNativeInspect(context: Context, input: String): Result<List<DownloadMediaInfo>> {
        return runCatching {
            val innerTube = InnerTubeRepository.getInstance(context)
            val videoId = PlaybackIdentity.extractVideoId(input)
            if (videoId != null) {
                val meta = innerTube.getTrackMetadata(videoId)
                if (meta != null) {
                    listOf(
                        DownloadMediaInfo(
                            id = meta.id,
                            webpageUrl = "https://www.youtube.com/watch?v=${meta.id}",
                            title = meta.title,
                            creator = meta.artist,
                            thumbnailUrl = meta.thumbnailUrl,
                            durationSeconds = innerTube.parseDurationText(meta.durationText) / 1000L
                        )
                    )
                } else emptyList()
            } else {
                val results = innerTube.searchTracks(input)
                results.map { item ->
                    DownloadMediaInfo(
                        id = item.id,
                        webpageUrl = "https://www.youtube.com/watch?v=${item.id}",
                        title = item.title,
                        creator = item.artist,
                        thumbnailUrl = item.thumbnailUrl,
                        durationSeconds = innerTube.parseDurationText(item.durationText) / 1000L
                    )
                }
            }
        }
    }

    fun executeDownload(url: String, outputDir: File): Flow<DownloadState> =
        executeDownload(url, outputDir, DownloadOptions())

    fun executeDownload(url: String, outputDir: File, options: DownloadOptions): Flow<DownloadState> {
        val ctx = appContext ?: com.example.flowave.FloWaveApplication.appContext
        // If embedded runtime is unavailable or stub detected, directly return pure-Kotlin native downloader
        if (!isAvailable() && ctx != null) {
            logger?.info("downloader", "ytdlp_stub_direct_fallback_native_download", context = mapOf("url" to url))
            return FloWaveNativeDownloader(ctx).download(url, outputDir, options)
        }

        return channelFlow {
            val startedAt = System.nanoTime()
            logger?.info("downloader", "download_started", context = mapOf(
                "kind" to options.kind.name.lowercase(),
                "inputIsUrl" to DownloadInput.isHttpUrl(url)
            ))
            send(DownloadState.Initializing)
            if (!FloWaveRuntime.awaitReady(15_000L) || !isAvailable()) {
                logger?.info("downloader", "ytdlp_not_ready_fallback_native", context = mapOf("url" to url))
                if (ctx != null) {
                    FloWaveNativeDownloader(ctx).download(url, outputDir, options).collect { send(it) }
                    return@channelFlow
                }
                send(DownloadState.Error("The embedded yt-dlp runtime is unavailable. Restart FloWave and try again."))
                return@channelFlow
            }
            if (!DownloadInput.isHttpUrl(url)) {
                send(DownloadState.Error("Only HTTP(S) video URLs are supported."))
                return@channelFlow
            }
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                send(DownloadState.Error("Unable to create the download directory."))
                return@channelFlow
            }

            send(DownloadState.Preparing)
            val processId = "flowave-${UUID.randomUUID()}"
            val template = File(outputDir, "%(title).200B [%(id)s].%(ext)s").absolutePath
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--extractor-args", "youtube:player_client=android,ios,web")
                addOption("--no-mtime")
                addOption("--no-overwrites")
                addOption("--restrict-filenames")
                addOption("--newline")
                addOption("--retries", "3")
                addOption("--fragment-retries", "3")
                addOption("--socket-timeout", "30")
                if (options.kind == DownloadMediaKind.AUDIO) {
                    addOption("-x")
                    addOption("--audio-format", options.audioFormat)
                    addOption("--audio-quality", options.audioQuality)
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                } else {
                    addOption("-f", options.formatSelector ?: "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                }
                if (options.kind == DownloadMediaKind.AUDIO) {
                    options.formatSelector?.let { addOption("-f", it) }
                }
                addOption("-o", template)
                addOption("--print", "after_move:filepath")
            }

            try {
                val response = withTimeout(6 * 60_000L) {
                    runInterruptible(Dispatchers.IO) {
                        executeWithUpdateRetry(request, processId) { progress, _, line ->
                            trySend(DownloadState.Downloading(progress.toFloat(), parseSpeed(line), parseEta(line)))
                            if (line.contains("ExtractAudio", ignoreCase = true) ||
                                line.contains("Post-process", ignoreCase = true) ||
                                line.contains("Deleting original", ignoreCase = true)
                            ) {
                                trySend(DownloadState.PostProcessing("Converting audio…"))
                            }
                        }
                    }
                }
                val outputPath = findOutputPath(response.out, outputDir)
                if (outputPath == null) {
                    logger?.error("downloader", "download_output_missing", context = mapOf("durationMs" to elapsedMs(startedAt)))
                    if (ctx != null) {
                        logger?.info("downloader", "missing_output_fallback_native", context = mapOf("url" to url))
                        FloWaveNativeDownloader(ctx).download(url, outputDir, options).collect { send(it) }
                        return@channelFlow
                    }
                    send(DownloadState.Error("yt-dlp completed without producing a media file."))
                } else {
                    logger?.info("downloader", "download_completed", context = mapOf(
                        "outputBytes" to File(outputPath).length(),
                        "durationMs" to elapsedMs(startedAt)
                    ))
                    send(DownloadState.Success(outputPath))
                }
            } catch (error: TimeoutCancellationException) {
                logger?.error("downloader", "download_timeout", context = mapOf(
                    "processId" to processId,
                    "durationMs" to elapsedMs(startedAt)
                ), throwable = error)
                if (ctx != null) {
                    logger?.info("downloader", "timeout_fallback_native", context = mapOf("url" to url))
                    FloWaveNativeDownloader(ctx).download(url, outputDir, options).collect { send(it) }
                    return@channelFlow
                }
                send(DownloadState.Error("yt-dlp timed out. Check the network and try again."))
            } catch (error: Throwable) {
                Log.e(TAG, "Download failed with yt-dlp, falling back to native", error)
                logger?.error("downloader", "download_failed_fallback_native", context = mapOf(
                    "failureClass" to error::class.simpleName.orEmpty(),
                    "durationMs" to elapsedMs(startedAt)
                ), throwable = error)
                if (ctx != null) {
                    FloWaveNativeDownloader(ctx).download(url, outputDir, options).collect { send(it) }
                    return@channelFlow
                }
                if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    send(DownloadState.Error(error.message ?: "Download failed."))
                } else {
                    send(DownloadState.Cancelled)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun parseMediaInfo(stdout: String): List<DownloadMediaInfo> {
        val root = JSONObject(stdout.trim())
        val entries = root.optJSONArray("entries")
        val objects = if (entries != null) {
            (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
        } else listOf(root)
        return objects.mapNotNull { item ->
            val rawUrl = item.optString("webpage_url").ifBlank { item.optString("url") }
            val title = item.optString("title").trim()
            val id = item.optString("id").ifBlank { null }
            val webpageUrl = when {
                DownloadInput.isHttpUrl(rawUrl) -> rawUrl
                !id.isNullOrBlank() -> "https://www.youtube.com/watch?v=$id"
                else -> ""
            }
            if (webpageUrl.isBlank() || title.isBlank()) return@mapNotNull null
            DownloadMediaInfo(
                id = id,
                webpageUrl = webpageUrl,
                title = title,
                creator = item.optString("uploader").ifBlank { item.optString("channel") },
                thumbnailUrl = item.optString("thumbnail").ifBlank { null },
                durationSeconds = item.optLong("duration", -1L).takeIf { it >= 0L }
            )
        }
    }

    /** Resolves a playable audio URL through yt-dlp, cleanly falling back to native InnerTube on failure. */
    suspend fun resolveAudioUrl(url: String, allowFallbackToNative: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: com.example.flowave.FloWaveApplication.appContext
        val videoId = PlaybackIdentity.extractVideoId(url)

        if (!isAvailable() || !FloWaveRuntime.awaitReady(2_000L)) {
            logger?.debug("downloader", "playback_runtime_unavailable_fallback_native")
            if (allowFallbackToNative && videoId != null && ctx != null) {
                return@withContext runCatching {
                    InnerTubeRepository.getInstance(ctx).getStreamResolution(videoId).url
                }
            }
            return@withContext Result.failure(IllegalStateException("yt-dlp runtime unavailable"))
        }

        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--extractor-args", "youtube:player_client=ios,visionos,android_vr,mweb")
                addOption("--no-warnings")
                addOption("--get-url")
                addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            }
            val output = withTimeout(90_000L) {
                runInterruptible(Dispatchers.IO) {
                    executeWithUpdateRetry(
                        request,
                        "flowave-resolve-${UUID.randomUUID()}",
                        null
                    ).out
                }
            }
            val resolved = output.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                ?: error("yt-dlp returned no playable URL")
            Result.success(resolved)
        } catch (error: TimeoutCancellationException) {
            logger?.warn("downloader", "playback_resolver_timeout_fallback_native", throwable = error)
            if (allowFallbackToNative && videoId != null && ctx != null) {
                return@withContext runCatching {
                    InnerTubeRepository.getInstance(ctx).getStreamResolution(videoId).url
                }
            }
            Result.failure(IllegalStateException("yt-dlp resolver timed out", error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger?.warn("downloader", "playback_resolver_failed_fallback_native", context = mapOf(
                "failureClass" to error::class.simpleName.orEmpty()
            ), throwable = error)
            if (allowFallbackToNative && videoId != null && ctx != null) {
                return@withContext runCatching {
                    InnerTubeRepository.getInstance(ctx).getStreamResolution(videoId).url
                }
            }
            Result.failure(error)
        }
    }

    private fun findOutputPath(stdout: String, outputDir: File): String? {
        val printed = stdout.lineSequence().map(String::trim)
            .filter { it.startsWith("/") || it.matches(Regex("^[A-Za-z]:[\\\\/].*")) }
            .map(::File)
            .lastOrNull { it.isFile && it.length() > 0L }
        if (printed != null) return printed.absolutePath
        return outputDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L && it.extension.lowercase() in AUDIO_EXTENSIONS }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    private fun parseSpeed(line: String): String =
        Regex("\\bat\\s+([^ ]+)").find(line)?.groupValues?.get(1) ?: ""

    private fun parseEta(line: String): String =
        Regex("\\bETA\\s+([^ ]+)").find(line)?.groupValues?.get(1) ?: ""

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private fun executeWithUpdateRetry(
        request: YoutubeDLRequest, 
        processId: String, 
        callback: ((Float, Long, String) -> Unit)? = null
    ): com.yausername.youtubedl_android.YoutubeDLResponse {
        return try {
            if (callback == null) {
                YoutubeDL.getInstance().execute(request, processId, null)
            } else {
                YoutubeDL.getInstance().execute(request, processId, callback)
            }
        } catch (e: com.yausername.youtubedl_android.YoutubeDLException) {
            val msg = e.message ?: ""
            if (msg.contains("400") || msg.contains("Precondition") || msg.contains("Sign in") || msg.contains("Video unavailable")) {
                val ctx = appContext?.applicationContext ?: com.example.flowave.FloWaveApplication.appContext
                if (ctx != null) {
                    try {
                        YoutubeDL.getInstance().updateYoutubeDL(
                            ctx, YoutubeDL.UpdateChannel.NIGHTLY
                        )
                    } catch (updateErr: Exception) {
                        Log.w(TAG, "Reactive update failed: ${updateErr.message}", updateErr)
                    }
                }
                if (callback == null) {
                    YoutubeDL.getInstance().execute(request, processId, null)
                } else {
                    YoutubeDL.getInstance().execute(request, processId, callback)
                }
            } else {
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "SealStyleDownloadEngine"
        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "opus", "webm", "aac", "wav", "flac")
    }
}
