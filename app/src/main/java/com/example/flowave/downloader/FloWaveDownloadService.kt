package com.example.flowave.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class FloWaveDownloadService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var downloadEngine: SealStyleDownloadEngine

    override fun onCreate() {
        super.onCreate()
        val envManager = FloWaveNativeEnvManager(applicationContext)
        envManager.prepareEnvironment()
        downloadEngine = SealStyleDownloadEngine(envManager)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetUrl = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val outputDir = File(getExternalFilesDir(null), "FloWaveDownloads")

        val initialNotification = buildNotification("Initializing download...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        serviceScope.launch {
            downloadEngine.executeDownload(targetUrl, outputDir).collect { state ->
                when (state) {
                    is DownloadState.Downloading -> {
                        val text = "${state.progress.toInt()}% at ${state.speed} (ETA: ${state.eta})"
                        updateNotification(text, state.progress.toInt())
                    }
                    is DownloadState.PostProcessing -> {
                        updateNotification(state.step, 100)
                    }
                    is DownloadState.Success -> {
                        updateNotification("Download finished successfully!", 100)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    is DownloadState.Error -> {
                        updateNotification("Error: ${state.message}", 0)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }

        return START_STICKY
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

    companion object {
        const val EXTRA_URL = "extra_target_url"
        private const val CHANNEL_ID = "flowave_download_channel"
        private const val NOTIFICATION_ID = 2001
    }
}
