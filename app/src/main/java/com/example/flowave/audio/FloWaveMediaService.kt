package com.example.flowave.audio

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.flowave.diagnostics.FloWaveLogger

@OptIn(UnstableApi::class)
class FloWaveMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val logger by lazy { FloWaveLogger.getInstance(this) }

    private fun initializeMediaSession() {
        if (mediaSession != null) return
        try {
            val audioEngine = FloWaveAudioEngine.getInstance(this)
            audioEngine.player?.let { exoPlayer ->
                mediaSession = MediaSession.Builder(this, exoPlayer)
                    .setCallback(object : MediaSession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo
                        ): MediaSession.ConnectionResult {
                            val connectionResult = super.onConnect(session, controller)
                            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
                            return MediaSession.ConnectionResult.accept(
                                availableSessionCommands.build(),
                                connectionResult.availablePlayerCommands
                            )
                        }
                    })
                    .build()
                logger.info("media_service", "session_initialized")
            }
        } catch (e: Exception) {
            logger.error("media_service", "session_initialization_failed", throwable = e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger.info("lifecycle", "media_service_created")
        initializeMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        logger.debug("media_service", "start_command", context = mapOf("startId" to startId))
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (mediaSession == null) {
            initializeMediaSession()
        }
        return mediaSession
    }

    override fun onDestroy() {
        try {
            mediaSession?.run {
                release()
            }
            mediaSession = null
        } catch (e: Exception) {
            logger.error("media_service", "session_release_failed", throwable = e)
        }
        logger.info("lifecycle", "media_service_destroyed")
        super.onDestroy()
    }
}
