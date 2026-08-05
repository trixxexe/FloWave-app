package com.example.flowave

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            runCatching {
                YoutubeDL.init(this@FloWaveApplication)
                FFmpeg.init(this@FloWaveApplication)
                FloWaveRuntime.ready = true
            }.onFailure {
                FloWaveRuntime.ready = false
                Log.e(TAG, "Embedded yt-dlp runtime initialization failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "FloWaveApplication"
    }
}

object FloWaveRuntime {
    @Volatile
    var ready: Boolean = false
}
