package com.example.flowave.downloader

import android.content.Context
import android.os.Environment
import com.example.flowave.data.local.AppDatabase
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.DownloadStatus
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.repository.FloWaveRepository
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

/** Coordinates UI download records with the embedded yt-dlp pipeline. */
class FloWaveDownloader(
    private val context: Context,
    private val repository: FloWaveRepository
) {
    private val logger = FloWaveLogger.getInstance(context)
    private val downloadDao = AppDatabase.getDatabase(context).downloadDao()
    private val innerTubeRepo = InnerTubeRepository.getInstance(context)
    private val sealEngine = SealStyleDownloadEngine()

    val allDownloadEntries: Flow<List<DownloadEntry>> = downloadDao.getAllDownloads()

    suspend fun startDownload(track: InnerTubeTrack) = downloadAudioTrack(track, "")

    /**
     * The streamUrl parameter is retained for source compatibility with the UI;
     * yt-dlp receives the canonical watch URL so it can select and post-process
     * a complete audio format instead of saving a transient segmented stream.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun downloadAudioTrack(track: InnerTubeTrack, streamUrl: String) = withContext(Dispatchers.IO) {
        logger.info("download", "started", context = mapOf("videoId" to track.id))
        val sourceUrl = if (track.id.startsWith("http://") || track.id.startsWith("https://")) {
            track.id
        } else {
            "https://www.youtube.com/watch?v=${track.id}"
        }
        downloadDao.insertDownload(
            DownloadEntry(
                id = track.id,
                trackTitle = track.title,
                artistName = track.artist,
                thumbnailUrl = track.thumbnailUrl,
                downloadUrl = sourceUrl,
                progress = 0f,
                status = DownloadStatus.DOWNLOADING
            )
        )

        val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        sealEngine.executeDownload(sourceUrl, outputDir).collect { state ->
            when (state) {
                is DownloadState.Downloading -> downloadDao.updateProgress(
                    track.id,
                    state.progress.coerceIn(0f, 100f) / 100f,
                    0L,
                    DownloadStatus.DOWNLOADING
                )
                is DownloadState.Success -> {
                    logger.info("download", "engine_succeeded", context = mapOf("videoId" to track.id))
                    val file = File(state.outputFilePath)
                    if (file.isFile && file.length() > 0L) {
                        handleCompletedDownload(track, file, file.parentFile ?: outputDir)
                    } else {
                        downloadDao.markFailed(track.id, "yt-dlp produced an empty file", DownloadStatus.FAILED)
                    }
                }
                is DownloadState.Error -> downloadDao.markFailed(track.id, state.message, DownloadStatus.FAILED)
                else -> Unit
            }
        }
    }

    private suspend fun handleCompletedDownload(
        track: InnerTubeTrack,
        file: File,
        musicDir: File
    ): Boolean {
        var durationMs = 0L
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (error: Exception) {
            logger.warn("download", "metadata_failed", context = mapOf("source" to "downloaded_file"), throwable = error)
            android.util.Log.w(TAG, "Duration metadata unavailable for ${file.name}", error)
        } finally {
            runCatching { retriever.release() }
        }

        // Valid Opus/WebM files can omit container duration. Preserve a
        // non-empty post-processed file and use the source duration as a safe
        // fallback instead of deleting a playable download.
        if (durationMs <= 0L && file.length() > 0L) {
            durationMs = innerTubeRepo.parseDurationText(track.durationText).coerceAtLeast(1L) * 1000L
        }
        if (durationMs <= 0L) {
            downloadDao.markFailed(track.id, "Invalid downloaded audio file", DownloadStatus.FAILED)
            return false
        }

        downloadDao.markCompleted(track.id, file.absolutePath, DownloadStatus.DONE, System.currentTimeMillis())
        logger.info("download", "completed", context = mapOf("videoId" to track.id))
        repository.insertTrack(
            Track(
                id = "dl_${track.id}",
                title = track.title,
                artist = track.artist,
                album = track.album.ifEmpty { "Downloaded" },
                durationMs = durationMs,
                mediaUri = file.absolutePath,
                artworkUri = track.thumbnailUrl,
                isOnline = false,
                source = "DOWNLOADED",
                folderPath = musicDir.absolutePath
            )
        )
        return true
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        logger.info("download", "deleted", context = mapOf("downloadId" to id))
        downloadDao.deleteDownloadById(id)
    }

    companion object {
        private const val TAG = "FloWaveDownloader"
    }
}
