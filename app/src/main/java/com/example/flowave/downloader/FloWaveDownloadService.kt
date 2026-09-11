package com.example.flowave.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.flowave.data.local.AppDatabase
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.DownloadStatus
import com.example.flowave.data.model.Track
import com.example.flowave.data.repository.FloWaveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class FloWaveDownloadService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var downloadEngine: SealStyleDownloadEngine
    private val activeDownloads = java.util.concurrent.atomic.AtomicInteger(0)
    private val downloadDao by lazy { AppDatabase.getDatabase(applicationContext).downloadDao() }
    private val repository by lazy { FloWaveRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        downloadEngine = SealStyleDownloadEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetUrl = intent?.getStringExtra(EXTRA_URL) ?: run {
            if (activeDownloads.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!DownloadInput.isHttpUrl(targetUrl)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val outputDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
            "FloWaveDownloads"
        )
        val taskId = "url_${UUID.randomUUID()}"
        val downloadEntry = DownloadEntry(
            id = taskId,
            trackTitle = intent?.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: targetUrl,
            artistName = intent?.getStringExtra(EXTRA_ARTIST).orEmpty().ifBlank { "Online download" },
            thumbnailUrl = intent?.getStringExtra(EXTRA_THUMBNAIL),
            downloadUrl = targetUrl,
            status = DownloadStatus.DOWNLOADING
        )

        activeDownloads.incrementAndGet()
        val initialNotification = buildNotification("Initializing download...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        serviceScope.launch {
            try {
                if (downloadDao.getDownloadByUrl(targetUrl) != null) {
                    return@launch
                }
                downloadDao.insertDownload(downloadEntry)
                downloadEngine.executeDownload(targetUrl, outputDir).collect { state ->
                    when (state) {
                        is DownloadState.Downloading -> {
                            val text = "${state.progress.toInt()}% at ${state.speed} (ETA: ${state.eta})"
                            updateNotification(text, state.progress.toInt())
                            downloadDao.updateProgress(taskId, (state.progress / 100f).coerceIn(0f, 1f), 0L, DownloadStatus.DOWNLOADING)
                        }
                        is DownloadState.PostProcessing -> {
                            updateNotification(state.step, 100)
                        }
                        is DownloadState.Success -> {
                            completeDirectDownload(taskId, state.outputFilePath)
                            updateNotification("Download finished successfully!", 100)
                        }
                        is DownloadState.Error -> {
                            downloadDao.markFailed(taskId, state.message, DownloadStatus.FAILED)
                            updateNotification("Error: ${state.message}", 0)
                        }
                        else -> {}
                    }
                }
            } catch (error: Exception) {
                downloadDao.markFailed(
                    taskId,
                    error.message ?: "Download failed",
                    DownloadStatus.FAILED
                )
                updateNotification("Error: ${error.message ?: "Download failed"}", 0)
            } finally {
                val remaining = activeDownloads.decrementAndGet()
                if (remaining <= 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FloWave Native Downloader",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int = 0): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FloWave Downloader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val notification = buildNotification(text, progress)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun completeDirectDownload(taskId: String, outputPath: String) {
        val file = File(outputPath)
        if (!file.isFile || file.length() == 0L) {
            downloadDao.markFailed(taskId, "yt-dlp produced an empty file", DownloadStatus.FAILED)
            return
        }
        var durationMs = 0L
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // A valid source may omit container duration; the file is still
            // imported and can be measured by the player when opened.
        } finally {
            runCatching { retriever.release() }
        }
        val title = file.nameWithoutExtension.substringBeforeLast(" [").ifBlank { file.nameWithoutExtension }
        downloadDao.markCompleted(taskId, file.absolutePath, DownloadStatus.DONE, System.currentTimeMillis())
        repository.insertTrack(
            Track(
                id = "dl_$taskId",
                title = title,
                artist = "Downloaded",
                album = "Downloaded",
                durationMs = durationMs,
                mediaUri = file.absolutePath,
                isOnline = false,
                source = "DOWNLOADED",
                folderPath = file.parent
            )
        )
    }

    companion object {
        const val EXTRA_URL = "extra_target_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_THUMBNAIL = "extra_thumbnail"
        private const val CHANNEL_ID = "flowave_download_channel"
        private const val NOTIFICATION_ID = 2001
    }
}
