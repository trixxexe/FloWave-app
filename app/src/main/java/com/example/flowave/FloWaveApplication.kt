package com.example.flowave

import android.app.Application
import android.content.Context
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Online-only YouTube Music client application.
 * Starts instantaneously without blocking on native binary extractions or Python environments.
 */
class FloWaveApplication : Application() {
    private val logger by lazy { FloWaveLogger.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        FloWaveRuntime.markReady(true)
        logger.info("app", "application_started_online_mode")
    }

    companion object {
        private const val TAG = "FloWaveApplication"

        @Volatile
        var appContext: Context? = null
            private set
    }
}

object FloWaveRuntime {
    private val readySignal = CompletableDeferred<Boolean>().apply { complete(true) }

    @Volatile
    var ready: Boolean = true

    fun markReady(value: Boolean) {
        ready = value
        if (!readySignal.isCompleted) {
            readySignal.complete(value)
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 1000L): Boolean =
        if (readySignal.isCompleted) ready else withTimeoutOrNull(timeoutMs) { readySignal.await() } == true
}
