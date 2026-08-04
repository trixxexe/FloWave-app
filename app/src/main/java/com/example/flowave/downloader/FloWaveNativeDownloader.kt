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
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val ytDlpFileName = when {
                abi.startsWith("arm64") -> "yt-dlp_linux_aarch64"
                abi.startsWith("armeabi") -> "yt-dlp_linux_armv7l"
                abi.startsWith("x86_64") -> "yt-dlp_linux"
                abi.startsWith("x86") -> "yt-dlp_linux_x86"
                else -> "yt-dlp_linux_aarch64"
            }
            val url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/$ytDlpFileName"
            android.util.Log.d("FloWaveNativeEnv", "Downloading yt-dlp for ABI: $abi → $ytDlpFileName")
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

