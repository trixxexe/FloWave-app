package com.example.flowave.downloader

import android.content.Context
import android.os.Environment
import com.example.flowave.data.local.AppDatabase
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.DownloadStatus
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.repository.FloWaveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

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
    private val client = OkHttpClient()
    private val downloadDao = AppDatabase.getDatabase(context).downloadDao()

    val allDownloadEntries: Flow<List<DownloadEntry>> = downloadDao.getAllDownloads()

    suspend fun downloadAudioTrack(track: InnerTubeTrack, streamUrl: String) = withContext(Dispatchers.IO) {
        val taskId = track.id

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
            val request = Request.Builder().url(streamUrl).build()
            val response = client.newCall(request).execute()
            val body = response.body
            if (response.isSuccessful && body != null) {
                val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                    ?: context.filesDir
                val fileName = "${track.title.replace(Regex("[^a-zA-Z0-9.-]"), "_")}.mp3"
                val file = File(musicDir, fileName)

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(file)
                val totalBytes = body.contentLength()
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var downloadedBytes = 0L

                var lastUpdatedPercent = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        val progressFloat = downloadedBytes.toFloat() / totalBytes.toFloat()
                        val percent = (progressFloat * 100).toInt()
                        if (percent >= lastUpdatedPercent + 5) {
                            lastUpdatedPercent = percent
                            downloadDao.updateProgress(taskId, progressFloat, downloadedBytes, DownloadStatus.DOWNLOADING)
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                downloadDao.markCompleted(taskId, file.absolutePath, DownloadStatus.DONE)

                // Save into Room DB
                val downloadedTrack = Track(
                    id = "dl_${track.id}",
                    title = track.title,
                    artist = track.artist,
                    album = if (track.album.isNotEmpty()) track.album else "Downloaded",
                    durationMs = 210000L,
                    mediaUri = file.absolutePath,
                    artworkUri = track.thumbnailUrl,
                    isOnline = false,
                    source = "DOWNLOADED",
                    folderPath = musicDir.absolutePath
                )
                repository.insertTrack(downloadedTrack)
            } else {
                downloadDao.markFailed(taskId, "HTTP Error ${response.code}", DownloadStatus.FAILED)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            downloadDao.markFailed(taskId, e.localizedMessage ?: "Download error", DownloadStatus.FAILED)
        }
    }

    suspend fun deleteDownload(id: String) = withContext(Dispatchers.IO) {
        downloadDao.deleteDownloadById(id)
    }
}
