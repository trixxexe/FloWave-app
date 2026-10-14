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
import androidx.core.app.ServiceCompat
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
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var downloadEngine: SealStyleDownloadEngine
    private val lock = Any()
    private val activeTasks = mutableMapOf<String, Job>()
    @Volatile private var latestStartId = 0
    private val downloadDao by lazy { AppDatabase.getDatabase(applicationContext).downloadDao() }
    private val repository by lazy { FloWaveRepository(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        downloadEngine = SealStyleDownloadEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) {
            latestStartId = startId
        }
        val targetUrl = intent?.getStringExtra(EXTRA_URL)
        if (targetUrl.isNullOrBlank() || !DownloadInput.isHttpUrl(targetUrl)) {
            synchronized(lock) {
                if (activeTasks.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }
        val outputDir = File(
            getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir,
            "FloWaveDownloads"
        )
        val taskId = "url_${UUID.randomUUID()}"
        val downloadEntry = DownloadEntry(
            id = taskId,
            trackTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: targetUrl,
            artistName = intent.getStringExtra(EXTRA_ARTIST).orEmpty().ifBlank { "Online download" },
            thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL),
            downloadUrl = targetUrl,
            status = DownloadStatus.DOWNLOADING
        )

        val initialNotification = buildNotification("Initializing download...")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        val job = serviceScope.launch {
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
                        is DownloadState.Cancelled -> {
                            downloadDao.markFailed(taskId, "Download cancelled", DownloadStatus.FAILED)
                            updateNotification("Download cancelled", 0)
                        }
                        else -> {}
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                downloadDao.markFailed(taskId, "Download cancelled", DownloadStatus.FAILED)
                throw cancelled
            } catch (error: Exception) {
                downloadDao.markFailed(
                    taskId,
                    error.message ?: "Download failed",
                    DownloadStatus.FAILED
                )
                updateNotification("Error: ${error.message ?: "Download failed"}", 0)
            } finally {
                synchronized(lock) {
                    activeTasks.remove(taskId)
                    if (activeTasks.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }

        synchronized(lock) {
            activeTasks[taskId] = job
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(lock) {
            activeTasks.clear()
        }
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
        val entry = downloadDao.getDownloadById(taskId)
        val title = entry?.trackTitle?.takeIf { it.isNotBlank() && !it.startsWith("http") }
            ?: file.nameWithoutExtension.substringBeforeLast(" [").ifBlank { file.nameWithoutExtension }
        val artist = entry?.artistName?.takeIf { it.isNotBlank() && it != "Online download" } ?: "Downloaded"
        val artworkUri = entry?.thumbnailUrl

        downloadDao.markCompleted(taskId, file.absolutePath, DownloadStatus.DONE, System.currentTimeMillis())
        repository.insertTrack(
            Track(
                id = "dl_$taskId",
                title = title,
                artist = artist,
                album = "Downloaded",
                durationMs = durationMs,
                mediaUri = file.absolutePath,
                artworkUri = artworkUri,
                isOnline = false,
                source = "DOWNLOADED",
                folderPath = file.parent
            )
        )
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf(file.absolutePath),
            null
        ) { path, uri ->
            android.util.Log.d("FloWaveDownloadService", "MediaScanner indexed $path: uri=$uri")
        }
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
