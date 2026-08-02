package com.example.flowave.audio

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class FloWaveMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            mediaSession?.run {
                release()
            }
            mediaSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}
