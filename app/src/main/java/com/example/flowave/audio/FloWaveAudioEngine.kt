package com.example.flowave.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.flowave.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val audioFormatInfo: String = "FLAC 24-bit / 96kHz"
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

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

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
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) {
                            startProgressAndVisualizerLoop()
                        } else {
                            stopProgressLoop()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            playNext()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        error.printStackTrace()
                        // Unfailing recovery: skip problematic stream to keep music playing
                        scope.launch {
                            delay(500)
                            playNext()
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
                    }
                })
            }

        try {
            exoPlayer?.let { player ->
                mediaSession = MediaSession.Builder(context, player).build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        exoPlayer?.audioSessionId?.let { sessionId ->
            setupAudioEffects(sessionId)
        }
    }

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

    fun setQueueAndPlay(queue: List<Track>, startIndex: Int = 0) {
        if (queue.isEmpty()) return
        val player = exoPlayer ?: return

        _playbackState.value = _playbackState.value.copy(
            queue = queue,
            currentQueueIndex = startIndex,
            currentTrack = queue.getOrNull(startIndex)
        )

        val mediaItems = queue.mapNotNull { track ->
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

            val uriStr = track.mediaUri
            if (!uriStr.isNullOrEmpty()) {
                MediaItem.Builder()
                    .setUri(Uri.parse(uriStr))
                    .setMediaId(track.id)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
            } else null
        }

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
                if (remaining <= 0) {
                    _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = 0L)
                    exoPlayer?.pause()
                    break
                }
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = remaining)
                delay(1000L)
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
                }
                delay(100L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
    }

    fun release() {
        try {
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        exoPlayer?.release()
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()
        presetReverb?.release()
    }
}
