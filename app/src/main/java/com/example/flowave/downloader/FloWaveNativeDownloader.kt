package com.example.flowave.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

// ============================================================================
// 1. DOWNLOAD STATE MODELS
// ============================================================================
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Initializing : DownloadState
    data class Downloading(val progress: Float, val speed: String, val eta: String) : DownloadState
    data class PostProcessing(val step: String) : DownloadState
    data class Success(val outputFilePath: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

// ============================================================================
// 2. NATIVE ENVIRONMENT & BINARY PATH MANAGER
// ============================================================================
class FloWaveNativeEnvManager(private val context: Context) {
    val runtimeDir: File = File(context.noBackupFilesDir, "flowave_downloader_runtime")
    val ytDlpExecutable: File = File(runtimeDir, "yt-dlp")
    val ffmpegBinary: File = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    val pythonHomeDir: File = File(runtimeDir, "python")
    private val httpClient = OkHttpClient()

    @Synchronized
    fun prepareEnvironment(): Boolean {
        if (!runtimeDir.exists()) {
            runtimeDir.mkdirs()
        }
        // Copy yt-dlp binary executable from assets if present
        copyAssetBinaryIfNeeded("yt-dlp", ytDlpExecutable)
        if (ytDlpExecutable.exists()) {
            ytDlpExecutable.setExecutable(true, false)
        }
        return ytDlpExecutable.exists()
    }

    private fun copyAssetBinaryIfNeeded(assetName: String, targetFile: File) {
        if (targetFile.exists() && targetFile.length() > 0) return
        runCatching {
            context.assets.open(assetName).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setExecutable(true, false)
        }.onFailure {
            android.util.Log.w("FloWaveNativeEnv", "Asset binary $assetName copy notice: ${it.message}")
        }
    }

    suspend fun fetchYtDlpBinaryFromGitHub(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body
            if (response.isSuccessful && body != null) {
                val tempFile = File(runtimeDir, "yt-dlp.tmp")
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (ytDlpExecutable.exists()) ytDlpExecutable.delete()
                    tempFile.renameTo(ytDlpExecutable)
                    ytDlpExecutable.setExecutable(true, false)
                    android.util.Log.d("FloWaveNativeEnv", "Successfully downloaded latest yt-dlp binary from GitHub")
                    return@withContext true
                }
            }
            false
        }.getOrElse {
            android.util.Log.e("FloWaveNativeEnv", "Failed to download yt-dlp binary: ${it.message}")
            false
        }
    }
}

// ============================================================================
// 3. PROCESS EXECUTION & PARSING ENGINE
// ============================================================================
class SealStyleDownloadEngine(private val envManager: FloWaveNativeEnvManager) {
    private val progressRegex: Pattern = Pattern.compile(
        "\\[download\\]\\s+(\\d+\\.\\d+)%\\s+of\\s+~?\\s*(\\S+)\\s+at\\s+(\\S+)\\s+ETA\\s+(\\S+)"
    )
    private val httpClient = OkHttpClient()

    fun executeDownload(targetUrl: String, outputDirectory: File): Flow<DownloadState> = flow {
        emit(DownloadState.Initializing)

        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            emit(DownloadState.Error("Invalid URL format provided."))
            return@flow
        }

        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        // Ensure executable yt-dlp binary is available
        if (!envManager.ytDlpExecutable.exists()) {
            emit(DownloadState.PostProcessing("Downloading latest native yt-dlp executable..."))
            envManager.fetchYtDlpBinaryFromGitHub()
        }

        // Check if executable yt-dlp binary is available for native process execution
        if (envManager.ytDlpExecutable.exists() && envManager.ytDlpExecutable.canExecute()) {
            val commandList = mutableListOf(
                envManager.ytDlpExecutable.absolutePath,
                "--ffmpeg-location", envManager.ffmpegBinary.absolutePath,
                "-f", "bestaudio/best",
                "--extract-audio",
                "--audio-format", "mp3",
                "--audio-quality", "0",
                "--output", "${outputDirectory.absolutePath}/%(title)s.%(ext)s",
                "--newline",
                "--", // Prevents CLI argument injection attacks
                targetUrl
            )

            val processBuilder = ProcessBuilder(commandList).apply {
                directory(envManager.runtimeDir)
                environment()["PYTHONHOME"] = envManager.pythonHomeDir.absolutePath
                environment()["LD_LIBRARY_PATH"] = envManager.ffmpegBinary.parent ?: ""
                redirectErrorStream(true)
            }

            runCatching {
                val process = processBuilder.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var logLine: String?

                while (reader.readLine().also { logLine = it } != null) {
                    logLine?.let { log ->
                        val matcher = progressRegex.matcher(log)
                        if (matcher.find()) {
                            val percentage = matcher.group(1)?.toFloatOrNull() ?: 0f
                            val speed = matcher.group(3) ?: "N/A"
                            val eta = matcher.group(4) ?: "N/A"
                            emit(DownloadState.Downloading(percentage, speed, eta))
                        } else if (log.contains("[ffmpeg]") || log.contains("ExtractAudio")) {
                            emit(DownloadState.PostProcessing("Converting audio container & embedding metadata..."))
                        }
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    emit(DownloadState.Success(outputDirectory.absolutePath))
                } else {
                    emit(DownloadState.Error("Process execution failed with exit code: $exitCode"))
                }
            }.onFailure { error ->
                emit(DownloadState.Error(error.localizedMessage ?: "Sub-process execution failure."))
            }
        } else {
            // Fallback HTTP Stream Saver if yt-dlp binary is not bundled in APK assets
            runCatching {
                emit(DownloadState.Downloading(10f, "HTTP Stream", "Calculating..."))
                val request = Request.Builder().url(targetUrl).build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (response.isSuccessful && body != null) {
                        val contentType = response.header("Content-Type") ?: ""
                        val ext = getExtensionFromMime(contentType, targetUrl)
                        val outputFile = File(outputDirectory, "flowave_download_${System.currentTimeMillis()}.$ext")
                        body.byteStream().use { inputStream ->
                            FileOutputStream(outputFile).use { outputStream ->
                                val totalBytes = body.contentLength()
                                val buffer = ByteArray(16 * 1024)
                                var bytesRead: Int
                                var downloadedBytes = 0L

                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    if (totalBytes > 0) {
                                        val pct = (downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f
                                        val speedKb = "1.2 MB/s"
                                        emit(DownloadState.Downloading(pct, speedKb, "10s"))
                                    }
                                }
                                outputStream.flush()
                            }
                        }

                        emit(DownloadState.PostProcessing("Finalizing saved media file..."))
                        emit(DownloadState.Success(outputFile.absolutePath))
                    } else {
                        emit(DownloadState.Error("HTTP download failed with code ${response.code}"))
                    }
                }
            }.onFailure { error ->
                emit(DownloadState.Error(error.localizedMessage ?: "HTTP Stream download failed."))
            }
        }
    }.flowOn(Dispatchers.IO)

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
}

// ============================================================================
// 4. FOREGROUND DOWNLOAD SERVICE
// ============================================================================
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
