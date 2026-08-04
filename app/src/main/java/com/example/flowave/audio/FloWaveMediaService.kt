package com.example.flowave.audio

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class FloWaveMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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
                android.util.Log.d("FloWaveMediaService", "MediaSession successfully initialized.")
            }
        } catch (e: Exception) {
            android.util.Log.e("FloWaveMediaService", "Error initializing MediaSession", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
            android.util.Log.e("FloWaveMediaService", "Error releasing MediaSession in onDestroy", e)
        }
        super.onDestroy()
    }
}
