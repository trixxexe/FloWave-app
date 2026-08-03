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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class DownloadTask(
    val id: String,
    val title: String,
    val artist: String,
    val progressPercent: Int,
    val status: String
)

class FloWaveDownloader(
    private val context: Context,
    private val repository: FloWaveRepository
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadDao = AppDatabase.getDatabase(context).downloadDao()
    private val innerTubeRepo = InnerTubeRepository()

    val allDownloadEntries: Flow<List<DownloadEntry>> = downloadDao.getAllDownloads()

    private fun isStreamUrlExpired(url: String): Boolean {
        if (!url.contains("expire=")) return false
        val expireStr = url.substringAfter("expire=").substringBefore("&")
        val expireTimeSec = expireStr.toLongOrNull() ?: return false
        return (expireTimeSec - 300) < (System.currentTimeMillis() / 1000)
    }

    private fun getExtensionFromMime(contentType: String, url: String): String {
        if (contentType.isNotEmpty()) {
            val lower = contentType.lowercase()
            when {
                lower.contains("audio/mpeg") || lower.contains("audio/mp3") -> return "mp3"
                lower.contains("audio/ogg") || lower.contains("audio/opus") || lower.contains("ogg") -> return "opus"
                lower.contains("audio/webm") || lower.contains("video/webm") -> return "webm"
                lower.contains("audio/mp4") || lower.contains("audio/m4a") || lower.contains("video/mp4") -> return "m4a"
                lower.contains("audio/flac") -> return "flac"
                lower.contains("audio/wav") || lower.contains("audio/x-wav") -> return "wav"
                lower.contains("audio/aac") || lower.contains("audio/x-aac") -> return "aac"
            }
        }

        val decodedUrl = try {
            java.net.URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }

        val mimeRegex = Regex("[?&]mime=([^&]+)")
        val match = mimeRegex.find(decodedUrl)
        if (match != null) {
            val mimeType = match.groupValues[1].lowercase()
            when {
                mimeType.contains("audio/mpeg") || mimeType.contains("audio/mp3") -> return "mp3"
                mimeType.contains("audio/ogg") || mimeType.contains("audio/opus") || mimeType.contains("ogg") -> return "opus"
                mimeType.contains("audio/webm") || mimeType.contains("video/webm") -> return "webm"
                mimeType.contains("audio/mp4") || mimeType.contains("audio/m4a") || mimeType.contains("video/mp4") -> return "m4a"
                mimeType.contains("audio/flac") -> return "flac"
                mimeType.contains("audio/wav") || mimeType.contains("audio/x-wav") -> return "wav"
                mimeType.contains("audio/aac") || mimeType.contains("audio/x-aac") -> return "aac"
            }
        }

        val pathExtension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(url)
        if (!pathExtension.isNullOrEmpty()) {
            return pathExtension.lowercase()
        }
        return "webm"
    }

    suspend fun downloadAudioTrack(track: InnerTubeTrack, streamUrl: String) = withContext(Dispatchers.IO) {
        val taskId = track.id
        var fileToCleanup: File? = null

        val initialEntry = DownloadEntry(
            id = taskId,
            trackTitle = track.title,
            artistName = track.artist,
            thumbnailUrl = track.thumbnailUrl,
            downloadUrl = streamUrl,
            progress = 0f,
            status = DownloadStatus.DOWNLOADING
        )
        downloadDao.insertDownload(initialEntry)

        try {
            var currentStreamUrl = streamUrl
            
            // Step 1: Check and auto-refresh stream URL if expired before download begins
            if (isStreamUrlExpired(currentStreamUrl)) {
                android.util.Log.d("FloWaveDownloader", "Download URL expired. Refreshing for ${track.title}...")
                try {
                    currentStreamUrl = innerTubeRepo.getStreamUrl(track.id, forceRefresh = true)
                } catch (e: Exception) {
                    android.util.Log.e("FloWaveDownloader", "Failed to refresh expired download URL: ${e.message}")
                }
            }

            // Step 2: Establish connection with Retries & Exponential Backoff
            var response: Response? = null
            var retryCount = 0
            val maxRetries = 3
            var delayMs = 1000L
            
            // To support Range (Resume) requests, we first perform a lightweight HEAD request to get headers
            var extension = "mp3"
            var totalExpectedLength = 0L
            try {
                val headRequest = Request.Builder().url(currentStreamUrl).head().build()
                val headResponse = client.newCall(headRequest).execute()
                headResponse.use { hr ->
                    if (hr.isSuccessful) {
                        val contentType = hr.header("Content-Type") ?: ""
                        extension = getExtensionFromMime(contentType, currentStreamUrl)
                        totalExpectedLength = hr.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("FloWaveDownloader", "HEAD request failed: ${e.message}. Falling back to standard GET.")
            }

            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir
            
            // Sanitize unicode filenames, strip invalid and control characters, and limit length to avoid exFAT/ext4 limit issues
            var safeTitle = track.title.replace(Regex("[\\x00-\\x1F\\\\/:*?\"<>|\\s]"), "_")
            if (safeTitle.length > 100) {
                safeTitle = safeTitle.substring(0, 100)
            }
            val fileName = "${safeTitle}_${track.id}.$extension"
            val file = File(musicDir, fileName)
            fileToCleanup = file

            var existingBytes = 0L
            if (file.exists()) {
                existingBytes = file.length()
            }

            // Check local storage space before resuming or downloading
            val initialFreeSpace = musicDir.freeSpace
            if (totalExpectedLength > 0 && initialFreeSpace < (totalExpectedLength - existingBytes)) {
                downloadDao.markFailed(taskId, "Insufficient storage. Required: ${(totalExpectedLength - existingBytes) / (1024 * 1024)}MB, Available: ${initialFreeSpace / (1024 * 1024)}MB", DownloadStatus.FAILED)
                return@withContext
            }

            // If file already exists and is complete, don't restart downloading
            if (totalExpectedLength > 0 && existingBytes == totalExpectedLength) {
                android.util.Log.d("FloWaveDownloader", "File already fully downloaded. Skipping network request.")
                downloadDao.updateProgress(taskId, 1.0f, totalExpectedLength, DownloadStatus.DONE)
                handleCompletedDownload(track, file, musicDir, taskId)
                return@withContext
            }

            while (retryCount < maxRetries) {
                try {
                    val requestBuilder = Request.Builder().url(currentStreamUrl)
                    if (existingBytes > 0) {
                        requestBuilder.header("Range", "bytes=$existingBytes-")
                    }
                    val request = requestBuilder.build()
                    val res = client.newCall(request).execute()
                    
                    if (res.isSuccessful) {
                        response = res
                        break
                    } else {
                        // Refresh URL on 403 or 410 (Expired link)
                        if (res.code == 403 || res.code == 410) {
                            android.util.Log.w("FloWaveDownloader", "Received HTTP ${res.code}. Requesting fresh URL...")
                            currentStreamUrl = innerTubeRepo.getStreamUrl(track.id, forceRefresh = true)
                            res.close()
                            retryCount++
                            delay(delayMs)
                            delayMs *= 2
                            continue
                        }
                        
                        // Retry on server errors
                        if (res.code in listOf(429, 500, 502, 503, 504)) {
                            res.close()
                            retryCount++
                            delay(delayMs)
                            delayMs *= 2
                            continue
                        }
                        
                        res.close()
                        break
                    }
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount >= maxRetries) {
                        throw e
                    }
                    delay(delayMs)
                    delayMs *= 2
                }
            }

            response?.use { resp ->
                val body = resp.body
                if (resp.isSuccessful && body != null) {
                    // If response code is 206 Partial Content, the server accepted Range resume
                    val isRangeSupported = resp.code == 206
                    
                    var downloadedBytes = if (isRangeSupported) existingBytes else 0L
                    val totalBytes = if (isRangeSupported) {
                        body.contentLength() + existingBytes
                    } else {
                        body.contentLength()
                    }.coerceAtLeast(totalExpectedLength)

                    // Double check storage space with real payload length
                    val neededSpace = if (isRangeSupported) (totalBytes - existingBytes) else totalBytes
                    if (neededSpace > 0 && musicDir.freeSpace < neededSpace) {
                        downloadDao.markFailed(taskId, "Insufficient storage. Required: ${neededSpace / (1024 * 1024)}MB", DownloadStatus.FAILED)
                        return@withContext
                    }

                    body.byteStream().use { inputStream ->
                        FileOutputStream(file, isRangeSupported).use { outputStream ->
                            val buffer = ByteArray(16 * 1024)
                            var bytesRead: Int
                            var lastUpdatedPercent = (downloadedBytes.toFloat() / totalBytes.toFloat() * 100).toInt()

                            while (isActive) {
                                bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break
                                
                                outputStream.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                
                                if (totalBytes > 0) {
                                    val progressFloat = downloadedBytes.toFloat() / totalBytes.toFloat()
                                    val percent = (progressFloat * 100).toInt()
                                    if (percent >= lastUpdatedPercent + 2 || percent == 100) {
                                        lastUpdatedPercent = percent
                                        downloadDao.updateProgress(taskId, progressFloat, downloadedBytes, DownloadStatus.DOWNLOADING)
                                    }
                                }
                            }
                            outputStream.flush()
                        }
                    }
                    
                    if (!isActive) {
                        downloadDao.markFailed(taskId, "Download cancelled", DownloadStatus.FAILED)
                        // Cancelled, delete the partial/corrupted file
                        if (file.exists()) {
                            file.delete()
                        }
                        return@withContext
                    }

                    // Download completed successfully, do not delete the file
                    fileToCleanup = null
                    handleCompletedDownload(track, file, musicDir, taskId)
                } else {
                    val errorCode = resp.code
                    downloadDao.markFailed(taskId, "HTTP Error Code: $errorCode", DownloadStatus.FAILED)
                    // HTTP error, delete any empty or partial file created
                    if (file.exists()) {
                        file.delete()
                    }
                }
            } ?: run {
                downloadDao.markFailed(taskId, "Failed to connect to media servers.", DownloadStatus.FAILED)
                if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: SocketTimeoutException) {
            android.util.Log.e("FloWaveDownloader", "Download timed out for $taskId", e)
            downloadDao.markFailed(taskId, "Connection timed out. Please retry in a better network zone.", DownloadStatus.FAILED)
            fileToCleanup?.let { if (it.exists()) it.delete() }
        } catch (e: java.io.FileNotFoundException) {
            android.util.Log.e("FloWaveDownloader", "Storage file creation failed for $taskId", e)
            downloadDao.markFailed(taskId, "Local file creation failed. No storage space or bad directory path.", DownloadStatus.FAILED)
            fileToCleanup?.let { if (it.exists()) it.delete() }
        } catch (e: IOException) {
            android.util.Log.e("FloWaveDownloader", "I/O Network error downloading $taskId", e)
            downloadDao.markFailed(taskId, "Network connection broken: ${e.localizedMessage}", DownloadStatus.FAILED)
            fileToCleanup?.let { if (it.exists()) it.delete() }
        } catch (e: Exception) {
            android.util.Log.e("FloWaveDownloader", "Unexpected error downloading $taskId", e)
            downloadDao.markFailed(taskId, e.localizedMessage ?: "Unexpected download issue", DownloadStatus.FAILED)
            fileToCleanup?.let { if (it.exists()) it.delete() }
        }
    }

    private suspend fun handleCompletedDownload(track: InnerTubeTrack, file: File, musicDir: File, taskId: String): Boolean {
        val retriever = android.media.MediaMetadataRetriever()
        var durationMs: Long = 0L
        try {
            retriever.setDataSource(file.absolutePath)
            val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = timeStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            android.util.Log.e("FloWaveDownloader", "Failed to extract duration from ${file.absolutePath}", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                android.util.Log.e("FloWaveDownloader", "Failed to release MediaMetadataRetriever", e)
            }
        }

        if (durationMs <= 0L) {
            android.util.Log.e("FloWaveDownloader", "Extracted duration is invalid ($durationMs) for ${file.absolutePath}. Deleting file.")
            if (file.exists()) {
                file.delete()
            }
            downloadDao.markFailed(taskId, "Invalid or corrupt downloaded audio file (missing duration metadata).", DownloadStatus.FAILED)
            return false
        }

        downloadDao.markCompleted(taskId, file.absolutePath, DownloadStatus.DONE)
        saveToOfflineLibrary(track, file, musicDir, durationMs)
        return true
    }

    private suspend fun saveToOfflineLibrary(track: InnerTubeTrack, file: File, musicDir: File, durationMs: Long) {
        val downloadedTrack = Track(
            id = "dl_${track.id}",
            title = track.title,
            artist = track.artist,
            album = if (track.album.isNotEmpty()) track.album else "Downloaded",
            durationMs = durationMs,
            mediaUri = file.absolutePath,
            artworkUri = track.thumbnailUrl,
            isOnline = false,
            source = "DOWNLOADED",
            folderPath = musicDir.absolutePath
        )
        repository.insertTrack(downloadedTrack)
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        downloadDao.deleteDownloadById(id)
    }
}
