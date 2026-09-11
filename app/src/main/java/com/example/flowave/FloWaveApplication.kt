package com.example.flowave

import android.app.Application
import android.util.Log
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

    override fun onCreate() {
        super.onCreate()
        runtimeScope.launch {
            val logger = com.example.flowave.diagnostics.FloWaveLogger.getInstance(this@FloWaveApplication)
            logger.info("downloader", "runtime_initialization_started", context = mapOf(
                "abis" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
                "nativeLibraryDirPresent" to (applicationInfo.nativeLibraryDir?.isNotBlank() == true)
            ))
            runCatching {
                withTimeout(20_000L) {
                    runInterruptible(Dispatchers.IO) {
                        YoutubeDL.init(this@FloWaveApplication)
                        FFmpeg.init(this@FloWaveApplication)
                    }
                }
                FloWaveRuntime.markReady(true)
                logger.info("downloader", "runtime_initialization_succeeded")
            }.onFailure {
                FloWaveRuntime.markReady(false)
                logger.error("downloader", "runtime_initialization_failed", context = mapOf(
                    "failureClass" to it::class.simpleName.orEmpty()
                ), throwable = it)
                Log.e(TAG, "Embedded yt-dlp runtime initialization failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "FloWaveApplication"
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
