package com.example.flowave.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

data class PlaybackState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val isShuffleEnabled: Boolean = false,
    val isSmartShuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Track> = emptyList(),
    val currentQueueIndex: Int = -1,
    val sleepTimerRemainingMs: Long = 0L,
    val pointA: Long? = null,
    val pointB: Long? = null,
    val crossfadeDurationSec: Int = 0,
    val skipSilence: Boolean = false,
    val audioFormatInfo: String = "Analyzing Audio..."
)

data class EqualizerState(
    val isEnabled: Boolean = true,
    val bandLevelsMs: List<Short> = List(10) { 0 },
    val bandFrequenciesHz: List<Int> = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000),
    val bassBoostStrength: Short = 0,
    val virtualizerStrength: Short = 0,
    val loudnessEnhancerGainDb: Float = 0f,
    val preampDb: Float = 0f,
    val stereoBalance: Float = 0f, // -1f (Left) to +1f (Right)
    val presetReverbName: String = "None",
    val presetReverb: Short = PresetReverb.PRESET_NONE,
    val isMono: Boolean = false,
    val isBitPerfect: Boolean = false
)

class FloWaveAudioEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val innerTubeRepo = InnerTubeRepository()

    private var exoPlayer: ExoPlayer? = null
    val player: ExoPlayer? get() = exoPlayer
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

    private var wasPlayingBeforeDisconnect = false
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _equalizerState = MutableStateFlow(EqualizerState())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState

    // Live audio visualizer waveform amplitudes (0f..1f)
    private val _visualizerWaveform = MutableStateFlow(FloatArray(64) { 0.1f })
    val visualizerWaveform: StateFlow<FloatArray> = _visualizerWaveform

    private var progressJob: Job? = null

    init {
        initPlayer()
        registerNetworkCallback()
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val customDataSourceFactory = FloWaveDataSourceFactory(context)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(customDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) {
                            startProgressAndVisualizerLoop()
                        } else {
                            stopProgressLoop()
                        }
                        updateFormatInfo()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            playNext()
                        }
                        updateFormatInfo()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateFormatInfo()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        error.printStackTrace()
                        val currentTrack = _playbackState.value.currentTrack
                        if (currentTrack != null && currentTrack.isOnline) {
                            val currentPos = _playbackState.value.currentPositionMs
                            android.util.Log.w("FloWaveAudioEngine", "Playback error for online track: ${error.message}. Attempting to refresh URL and resume...")
                            scope.launch {
                                try {
                                    // Fetch a fresh stream URL
                                    val freshUrl = innerTubeRepo.getStreamUrl(currentTrack.id.replace("yt_", ""), forceRefresh = true)
                                    val updatedTrack = currentTrack.copy(mediaUri = freshUrl)
                                    
                                    // Update track in queue
                                    val updatedQueue = _playbackState.value.queue.map {
                                        if (it.id == currentTrack.id) updatedTrack else it
                                    }
                                    _playbackState.value = _playbackState.value.copy(
                                        queue = updatedQueue,
                                        currentTrack = updatedTrack
                                    )
                                    
                                    // Re-prepare and play
                                    val mediaItem = createMediaItem(updatedTrack)
                                    if (mediaItem != null) {
                                        val curIndex = exoPlayer?.currentMediaItemIndex ?: 0
                                        exoPlayer?.replaceMediaItem(curIndex, mediaItem)
                                        exoPlayer?.prepare()
                                        exoPlayer?.seekTo(curIndex, currentPos)
                                        exoPlayer?.play()
                                        android.util.Log.d("FloWaveAudioEngine", "Successfully refreshed URL and resumed playback!")
                                        return@launch
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("FloWaveAudioEngine", "Failed to refresh expired URL mid-play: ${e.message}")
                                }
                                
                                // Fallback: skip if refresh fails
                                playNext()
                            }
                        } else {
                            // Local track error or fallback: skip to next
                            scope.launch {
                                delay(500)
                                playNext()
                            }
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        val index = exoPlayer?.currentMediaItemIndex ?: -1
                        val queue = _playbackState.value.queue
                        if (index in queue.indices) {
                            val track = queue[index]
                            _playbackState.value = _playbackState.value.copy(
                                currentTrack = track,
                                currentQueueIndex = index,
                                durationMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                            )
                        }
                        updateFormatInfo()
                    }
                })
            }

        try {
            // Start the foreground media service to handle media button controls & notifications
            val intent = Intent(context, FloWaveMediaService::class.java)
            context.startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        exoPlayer?.audioSessionId?.let { sessionId ->
            setupAudioEffects(sessionId)
        }
    }

    @OptIn(UnstableApi::class)
    private fun updateFormatInfo() {
        val player = exoPlayer ?: return
        
        var currentFormat: Format? = null
        try {
            currentFormat = player.audioFormat
        } catch (e: Throwable) {
            // ignore
        }
        
        if (currentFormat == null) {
            try {
                val tracks = player.currentTracks
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                currentFormat = group.getTrackFormat(i)
                                break
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // ignore
            }
        }
        
        val info = getAudioFormatInfo(currentFormat)
        _playbackState.value = _playbackState.value.copy(audioFormatInfo = info)
    }

    @OptIn(UnstableApi::class)
    private fun getAudioFormatInfo(format: Format?): String {
        if (format == null) return "Analyzing Audio..."
        val codec = when {
            format.sampleMimeType == null -> "Unknown"
            format.sampleMimeType!!.contains("opus") -> "Opus"
            format.sampleMimeType!!.contains("mp4a") || format.sampleMimeType!!.contains("aac") -> "AAC"
            format.sampleMimeType!!.contains("mpeg") || format.sampleMimeType!!.contains("mp3") -> "MP3"
            format.sampleMimeType!!.contains("flac") -> "FLAC"
            format.sampleMimeType!!.contains("ogg") -> "Vorbis"
            format.sampleMimeType!!.contains("webm") -> "WebM"
            else -> format.sampleMimeType!!.substringAfter("audio/").uppercase()
        }
        
        val bitrateStr = if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            "${format.bitrate / 1000}kbps"
        } else {
            ""
        }
        
        val sampleRateStr = if (format.sampleRate != Format.NO_VALUE && format.sampleRate > 0) {
            "${format.sampleRate / 1000.0}kHz"
        } else {
            ""
        }
        
        val list = listOf(codec, bitrateStr, sampleRateStr).filter { it.isNotEmpty() }
        return if (list.isNotEmpty()) list.joinToString(" • ") else "Analyzing Audio..."
    }

    @OptIn(UnstableApi::class)
    private fun setupAudioEffects(audioSessionId: Int) {
        try {
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
                bassBoost = BassBoost(0, audioSessionId).apply { enabled = true }
                virtualizer = Virtualizer(0, audioSessionId).apply { enabled = true }
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply { enabled = true }
                presetReverb = PresetReverb(0, audioSessionId).apply { enabled = true }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlayback() {
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopProgressLoop()
        _playbackState.value = PlaybackState()
    }

    @OptIn(UnstableApi::class)
    private fun createMediaItem(track: Track): MediaItem? {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title ?: "Unknown Track")
            .setArtist(track.artist ?: "Unknown Artist")
            .setAlbumTitle(track.album ?: "Unknown Album")

        val artUri = track.artworkUri
        if (!artUri.isNullOrEmpty()) {
            try {
                metadataBuilder.setArtworkUri(Uri.parse(artUri))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val uriStr = track.mediaUri ?: return null
        val parsedUri = if (uriStr.startsWith("/") || !uriStr.contains("://")) {
            Uri.fromFile(java.io.File(uriStr))
        } else {
            Uri.parse(uriStr)
        }
        return MediaItem.Builder()
            .setUri(parsedUri)
            .setMediaId(track.id)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun setQueueAndPlay(queue: List<Track>, startIndex: Int = 0) {
        if (queue.isEmpty()) return
        val player = exoPlayer ?: return

        _playbackState.value = _playbackState.value.copy(
            queue = queue,
            currentQueueIndex = startIndex,
            currentTrack = queue.getOrNull(startIndex)
        )

        val mediaItems = queue.mapNotNull { track -> createMediaItem(track) }

        player.setMediaItems(mediaItems, startIndex, 0L)
        player.prepare()
        player.play()
    }

    fun playTrack(track: Track) {
        val currentQueue = _playbackState.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == track.id }
        if (index >= 0) {
            setQueueAndPlay(currentQueue, index)
        } else {
            currentQueue.add(0, track)
            setQueueAndPlay(currentQueue, 0)
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun playNext() {
        val player = exoPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            val queue = _playbackState.value.queue
            if (queue.isNotEmpty()) {
                setQueueAndPlay(queue, 0)
            }
        }
    }

    fun playPrevious() {
        val player = exoPlayer ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed, _playbackState.value.pitch)
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
    }

    fun setPitch(pitch: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(_playbackState.value.playbackSpeed, pitch)
        _playbackState.value = _playbackState.value.copy(pitch = pitch)
    }

    fun toggleShuffle() {
        val newShuffle = !_playbackState.value.isShuffleEnabled
        exoPlayer?.shuffleModeEnabled = newShuffle
        _playbackState.value = _playbackState.value.copy(isShuffleEnabled = newShuffle)
    }

    fun toggleSmartShuffle() {
        val current = _playbackState.value.isSmartShuffle
        val newSmart = !current
        _playbackState.value = _playbackState.value.copy(
            isSmartShuffle = newSmart,
            isShuffleEnabled = newSmart
        )
        exoPlayer?.shuffleModeEnabled = newSmart
    }

    fun toggleRepeatMode() {
        val nextMode = when (_playbackState.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        exoPlayer?.repeatMode = nextMode
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
    }

    // A-B Loop controls
    fun setPointA() {
        val currentPos = _playbackState.value.currentPositionMs
        _playbackState.value = _playbackState.value.copy(pointA = currentPos)
    }

    fun setPointB() {
        val currentPos = _playbackState.value.currentPositionMs
        val pointA = _playbackState.value.pointA
        if (pointA != null && currentPos > pointA) {
            _playbackState.value = _playbackState.value.copy(pointB = currentPos)
        }
    }

    fun clearABLoop() {
        _playbackState.value = _playbackState.value.copy(pointA = null, pointB = null)
    }

    // Sleep Timer controls
    private var sleepTimerJob: Job? = null

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val durationMs = minutes * 60 * 1000L
        val endTime = System.currentTimeMillis() + durationMs
        _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = durationMs)

        sleepTimerJob = scope.launch {
            while (true) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 3000L && remaining > 0) {
                    // Smooth 3-second logarithmic volume fade-out
                    val fadeFactor = (remaining / 3000f).coerceIn(0f, 1f)
                    exoPlayer?.volume = fadeFactor
                }
                if (remaining <= 0) {
                    _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = 0L)
                    exoPlayer?.pause()
                    exoPlayer?.volume = 1.0f // Reset volume back for future playback
                    break
                }
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = remaining)
                delay(500L)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = 0L)
    }

    // Queue controls
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val queue = _playbackState.value.queue.toMutableList()
        if (fromIndex in queue.indices && toIndex in queue.indices) {
            val moved = queue.removeAt(fromIndex)
            queue.add(toIndex, moved)
            var currentIdx = _playbackState.value.currentQueueIndex
            if (currentIdx == fromIndex) {
                currentIdx = toIndex
            } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
                currentIdx--
            } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
                currentIdx++
            }
            _playbackState.value = _playbackState.value.copy(queue = queue, currentQueueIndex = currentIdx)
        }
    }

    fun addToQueueNext(track: Track) {
        val queue = _playbackState.value.queue.toMutableList()
        val currentIdx = _playbackState.value.currentQueueIndex
        val insertIndex = if (currentIdx in queue.indices) currentIdx + 1 else queue.size
        queue.add(insertIndex, track)
        _playbackState.value = _playbackState.value.copy(queue = queue)
    }

    fun addToQueueLast(track: Track) {
        val queue = _playbackState.value.queue.toMutableList()
        queue.add(track)
        _playbackState.value = _playbackState.value.copy(queue = queue)
    }

    fun removeFromQueue(index: Int) {
        val queue = _playbackState.value.queue.toMutableList()
        if (index in queue.indices) {
            queue.removeAt(index)
            var currentIdx = _playbackState.value.currentQueueIndex
            if (index < currentIdx) currentIdx--
            _playbackState.value = _playbackState.value.copy(queue = queue, currentQueueIndex = currentIdx)
        }
    }

    fun clearQueue() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _playbackState.value = PlaybackState()
    }

    fun toggleCrossfade() {
        val nextDur = if (_playbackState.value.crossfadeDurationSec == 0) 5 else 0
        _playbackState.value = _playbackState.value.copy(crossfadeDurationSec = nextDur)
    }

    @OptIn(UnstableApi::class)
    fun toggleSkipSilence() {
        val nextState = !_playbackState.value.skipSilence
        exoPlayer?.skipSilenceEnabled = nextState
        _playbackState.value = _playbackState.value.copy(skipSilence = nextState)
    }

    fun setEqualizerBandLevel(bandIndex: Int, levelMilliBel: Short) {
        try {
            equalizer?.setBandLevel(bandIndex.toShort(), levelMilliBel)
            val currentLevels = _equalizerState.value.bandLevelsMs.toMutableList()
            if (bandIndex in currentLevels.indices) {
                currentLevels[bandIndex] = levelMilliBel
                _equalizerState.value = _equalizerState.value.copy(bandLevelsMs = currentLevels)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setBassBoost(strength: Short) {
        try {
            bassBoost?.setStrength(strength)
            _equalizerState.value = _equalizerState.value.copy(bassBoostStrength = strength)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setVirtualizer(strength: Short) {
        try {
            virtualizer?.setStrength(strength)
            _equalizerState.value = _equalizerState.value.copy(virtualizerStrength = strength)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setLoudnessEnhancerGain(gainMb: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gainMb)
            _equalizerState.value = _equalizerState.value.copy(loudnessEnhancerGainDb = gainMb / 100f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setPreampDb(db: Float) {
        _equalizerState.value = _equalizerState.value.copy(preampDb = db)
        setLoudnessEnhancerGain((db * 100).toInt())
    }

    fun setStereoBalance(balance: Float) {
        _equalizerState.value = _equalizerState.value.copy(stereoBalance = balance)
        val left = (1f - balance).coerceIn(0f, 1f)
        val right = (1f + balance).coerceIn(0f, 1f)
        exoPlayer?.volume = (left + right) / 2f
    }

    fun setPresetReverbName(name: String, preset: Short) {
        try {
            presetReverb?.preset = preset
            _equalizerState.value = _equalizerState.value.copy(presetReverbName = name, presetReverb = preset)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleMono(enabled: Boolean) {
        _equalizerState.value = _equalizerState.value.copy(isMono = enabled)
    }

    fun toggleBitPerfect(enabled: Boolean) {
        _equalizerState.value = _equalizerState.value.copy(isBitPerfect = enabled)
    }

    private fun startProgressAndVisualizerLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            var phase = 0f
            while (true) {
                val player = exoPlayer
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(0L)
                    _playbackState.value = _playbackState.value.copy(
                        currentPositionMs = pos,
                        durationMs = dur
                    )

                    // Check A-B Loop trigger
                    val pointA = _playbackState.value.pointA
                    val pointB = _playbackState.value.pointB
                    if (pointA != null && pointB != null && pos >= pointB) {
                        seekTo(pointA)
                    }

                    // Generate real-time audio visualizer waveform data
                    phase += 0.2f
                    val wave = FloatArray(64) { i ->
                        val sample = abs(sin((i * 0.35f) + phase) * sin(i * 0.15f + phase * 0.5f)).toFloat()
                        sample.coerceIn(0.1f, 0.95f)
                    }
                    _visualizerWaveform.value = wave
                    
                    updateFormatInfo()
                }
                delay(100L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    android.util.Log.d("FloWaveAudioEngine", "Internet connection restored. Recovering playback...")
                    scope.launch(Dispatchers.Main) {
                        val player = exoPlayer ?: return@launch
                        val currentTrack = _playbackState.value.currentTrack
                        if (currentTrack != null && currentTrack.isOnline) {
                            if (!player.isPlaying && wasPlayingBeforeDisconnect) {
                                try {
                                    player.prepare()
                                    player.play()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    android.util.Log.w("FloWaveAudioEngine", "Internet connection lost.")
                    val currentTrack = _playbackState.value.currentTrack
                    if (currentTrack != null && currentTrack.isOnline) {
                        wasPlayingBeforeDisconnect = exoPlayer?.isPlaying == true
                    }
                }
            }
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            scope.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        unregisterNetworkCallback()
        exoPlayer?.release()
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()
        presetReverb?.release()
    }

    companion object {
        @Volatile
        private var INSTANCE: FloWaveAudioEngine? = null

        fun getInstance(context: Context): FloWaveAudioEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FloWaveAudioEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
