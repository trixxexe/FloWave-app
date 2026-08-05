package com.example.flowave.downloader

import android.util.Log
import com.example.flowave.FloWaveRuntime
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
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
class SealStyleDownloadEngine {

    fun executeDownload(url: String, outputDir: File): Flow<DownloadState> = channelFlow {
        send(DownloadState.Initializing)
        if (!FloWaveRuntime.awaitReady()) {
            send(DownloadState.Error("The embedded yt-dlp runtime is unavailable. Restart FloWave and try again."))
            return@channelFlow
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            send(DownloadState.Error("Only HTTP(S) video URLs are supported."))
            return@channelFlow
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            send(DownloadState.Error("Unable to create the download directory."))
            return@channelFlow
        }

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
            addOption("-x")
            addOption("--audio-format", "m4a")
            addOption("--audio-quality", "0")
            addOption("-o", template)
            // Print the final post-processing path so callers never have to
            // guess whether yt-dlp produced m4a, webm, or another container.
            addOption("--print", "after_move:filepath")
        }

        try {
            val response = YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                trySend(DownloadState.Downloading(progress.toFloat(), parseSpeed(line), parseEta(line)))
                if (line.contains("ExtractAudio", ignoreCase = true) ||
                    line.contains("Post-process", ignoreCase = true) ||
                    line.contains("Deleting original", ignoreCase = true)
                ) {
                    trySend(DownloadState.PostProcessing("Converting audio…"))
                }
            }
            val outputPath = findOutputPath(response.out, outputDir)
            if (outputPath == null) {
                send(DownloadState.Error("yt-dlp completed without producing an audio file."))
            } else {
                send(DownloadState.Success(outputPath))
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Download failed", error)
            send(DownloadState.Error(error.message ?: "Download failed."))
        }
    }.flowOn(Dispatchers.IO)

    /** Resolves a playable audio URL through the same embedded yt-dlp runtime. */
    suspend fun resolveAudioUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        if (!FloWaveRuntime.awaitReady()) return@withContext Result.failure(IllegalStateException("yt-dlp runtime unavailable"))
        runCatching {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--get-url")
                addOption("-f", "bestaudio[protocol^=http]/bestaudio")
            }
            val output = YoutubeDL.getInstance().execute(
                request,
                "flowave-resolve-${UUID.randomUUID()}",
                null
            ).out
            output.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                ?: error("yt-dlp returned no playable URL")
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

    companion object {
        private const val TAG = "SealStyleDownloadEngine"
        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "opus", "webm", "aac", "wav", "flac")
    }
}
