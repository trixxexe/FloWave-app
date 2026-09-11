package com.example.flowave.downloader

import android.content.Context
import android.util.Log
import com.example.flowave.FloWaveRuntime
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * FloWave's GPL-3.0 download adapter.
 *
 * This uses the embedded yt-dlp/FFmpeg Android runtime shipped by the
 * GPL-3.0 youtubedl-android project (the same runtime family used by Seal).
 * It deliberately does not execute a downloaded Linux binary, which cannot run
 * reliably on Android and was the reason the previous downloader failed.
 */
class SealStyleDownloadEngine(context: Context? = null) {
    private val logger = context?.applicationContext?.let { FloWaveLogger.getInstance(it) }

    suspend fun inspect(input: String): Result<List<DownloadMediaInfo>> = withContext(Dispatchers.IO) {
        val operation = if (DownloadInput.isHttpUrl(input)) "url_metadata" else "keyword_search"
        val startedAt = System.nanoTime()
        logger?.info("downloader", "inspect_started", context = mapOf(
            "operation" to operation,
            "inputLength" to input.length
        ))
        if (!FloWaveRuntime.awaitReady(15_000L)) {
            val error = IllegalStateException("The embedded yt-dlp runtime is unavailable")
            logger?.error("downloader", "inspect_runtime_unavailable", context = mapOf(
                "operation" to operation,
                "durationMs" to elapsedMs(startedAt)
            ), throwable = error)
            return@withContext Result.failure<List<DownloadMediaInfo>>(error)
        }
        try {
            val request = YoutubeDLRequest(DownloadInput.sourceFor(input)).apply {
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--no-playlist")
            }
            val output = withTimeout(35_000L) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(
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
            Result.failure<List<DownloadMediaInfo>>(IllegalStateException("yt-dlp inspection timed out", error))
        } catch (error: CancellationException) {
            logger?.debug("downloader", "inspect_cancelled", context = mapOf("operation" to operation))
            throw error
        } catch (error: Throwable) {
            logger?.error("downloader", "inspect_failed", context = mapOf(
                "operation" to operation,
                "failureClass" to error::class.simpleName.orEmpty(),
                "durationMs" to elapsedMs(startedAt)
            ), throwable = error)
            Result.failure<List<DownloadMediaInfo>>(error)
        }
    }

    fun executeDownload(url: String, outputDir: File): Flow<DownloadState> =
        executeDownload(url, outputDir, DownloadOptions())

    fun executeDownload(url: String, outputDir: File, options: DownloadOptions): Flow<DownloadState> = channelFlow {
        val startedAt = System.nanoTime()
        logger?.info("downloader", "download_started", context = mapOf(
            "kind" to options.kind.name.lowercase(),
            "inputIsUrl" to DownloadInput.isHttpUrl(url)
        ))
        send(DownloadState.Initializing)
        if (!FloWaveRuntime.awaitReady(15_000L)) {
            logger?.error("downloader", "download_runtime_unavailable")
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
            // Print the final post-processing path so callers never have to
            // guess whether yt-dlp produced m4a, webm, or another container.
            addOption("--print", "after_move:filepath")
        }

        try {
            val response = withTimeout(6 * 60_000L) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
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
            send(DownloadState.Error("yt-dlp timed out. Check the network and try again."))
        } catch (error: Throwable) {
            Log.e(TAG, "Download failed", error)
            logger?.error("downloader", "download_failed", context = mapOf(
                "failureClass" to error::class.simpleName.orEmpty(),
                "durationMs" to elapsedMs(startedAt)
            ), throwable = error)
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                send(DownloadState.Error(error.message ?: "Download failed."))
            } else {
                send(DownloadState.Cancelled)
            }
        }
    }.flowOn(Dispatchers.IO)

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

    /** Resolves a playable audio URL through the same embedded yt-dlp runtime. */
    suspend fun resolveAudioUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        if (!FloWaveRuntime.awaitReady(2_000L)) {
            logger?.debug("downloader", "playback_runtime_unavailable")
            return@withContext Result.failure(IllegalStateException("yt-dlp runtime unavailable"))
        }
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--get-url")
                addOption("-f", "bestaudio[protocol^=http]/bestaudio")
            }
            val output = withTimeout(25_000L) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(
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
            logger?.warn("downloader", "playback_resolver_timeout", throwable = error)
            Result.failure(IllegalStateException("yt-dlp resolver timed out", error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger?.warn("downloader", "playback_resolver_failed", context = mapOf(
                "failureClass" to error::class.simpleName.orEmpty()
            ), throwable = error)
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

    companion object {
        private const val TAG = "SealStyleDownloadEngine"
        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "opus", "webm", "aac", "wav", "flac")
    }
}
