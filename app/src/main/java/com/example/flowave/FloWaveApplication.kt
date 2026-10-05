package com.example.flowave

import android.app.Application
import android.util.Log
import com.example.flowave.diagnostics.FloWaveLogger
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/**
 * Initializes the same embedded yt-dlp toolchain used by Seal, without spawning
 * an unsupported Linux executable from the Android app process.
 *
 * The integration boundary is FloWave code; the upstream project and license
 * information are recorded in THIRD_PARTY_NOTICES.md.
 */
class FloWaveApplication : Application() {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logger by lazy { FloWaveLogger.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        runtimeScope.launch {
            val libDir = applicationInfo.nativeLibraryDir?.let { java.io.File(it) }
            val pythonZip = libDir?.let { java.io.File(it, "libpython.zip.so") }
            logger.info("downloader", "runtime_initialization_started", context = mapOf(
                "abis" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
                "nativeLibraryDir" to (libDir?.absolutePath ?: "none"),
                "libpythonZipExists" to (pythonZip?.exists() == true),
                "libpythonZipLength" to (pythonZip?.takeIf { it.exists() }?.length() ?: -1L)
            ))
            runCatching {
                withTimeout(45_000L) {
                    runInterruptible(Dispatchers.IO) {
                        YoutubeDL.init(this@FloWaveApplication)
                        FFmpeg.init(this@FloWaveApplication)
                    }
                }
                FloWaveRuntime.markReady(true)
                logger.info("downloader", "runtime_initialization_succeeded")

                // Background async update to NIGHTLY to keep yt-dlp extractor rules current
                runtimeScope.launch {
                    try {
                        YoutubeDL.getInstance().updateYoutubeDL(this@FloWaveApplication, YoutubeDL.UpdateChannel.NIGHTLY)
                        logger.info("downloader", "runtime_background_update_succeeded")
                    } catch (e: Exception) {
                        logger.warn("downloader", "runtime_background_update_failed", throwable = e)
                    }
                }
                Unit
            }.onFailure { error: Throwable ->
                FloWaveRuntime.markReady(false)
                logger.error("downloader", "runtime_initialization_failed", context = mapOf(
                    "failureClass" to error::class.simpleName.orEmpty()
                ), throwable = error)
                Log.e(TAG, "Embedded yt-dlp runtime initialization failed", error)
            }
        }
    }

    companion object {
        private const val TAG = "FloWaveApplication"

        @Volatile
        var appContext: Context? = null
            private set
    }
}

object FloWaveRuntime {
    private val readySignal = CompletableDeferred<Boolean>()

    @Volatile
    var ready: Boolean = false

    fun markReady(value: Boolean) {
        ready = value
        readySignal.complete(value)
    }

    suspend fun awaitReady(timeoutMs: Long = 45_000L): Boolean =
        if (readySignal.isCompleted) ready else withTimeoutOrNull(timeoutMs) { readySignal.await() } == true
}
