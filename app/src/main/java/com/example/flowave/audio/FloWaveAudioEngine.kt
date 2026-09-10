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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.example.flowave.data.model.Track
import androidx.room.withTransaction
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.diagnostics.FloWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val audioFormatInfo: String = "Analyzing Audio...",
    val isBuffering: Boolean = false,
    val errorMessage: String? = null
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
    private val logger = FloWaveLogger.getInstance(context)
    private val innerTubeRepo = InnerTubeRepository.getInstance(context)
    private val db = com.example.flowave.data.local.AppDatabase.getDatabase(context)
    private val queueDao = db.queueDao()
    private val trackDao = db.trackDao()
    private val persistenceMutex = Mutex()

    private val panningAudioProcessor = PanningAudioProcessor()
    private var mediaController: androidx.media3.session.MediaController? = null

    private var exoPlayer: ExoPlayer? = null
    val player: ExoPlayer? get() = exoPlayer
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var presetReverb: PresetReverb? = null

    private var wasPlayingBeforeDisconnect = false
    private var networkAvailable = true
    private var connectivityManager: android.net.ConnectivityManager? = null
    private val trackRetryCount = mutableMapOf<String, Int>()
    private var lastErrorToastTime = 0L
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    private val _equalizerState = MutableStateFlow(EqualizerState())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState

    // Live audio visualizer waveform amplitudes (0f..1f)
    private val _visualizerWaveform = MutableStateFlow(FloatArray(64) { 0.1f })
    val visualizerWaveform: StateFlow<FloatArray> = _visualizerWaveform

    private var progressJob: Job? = null
    private var playerListener: Player.Listener? = null
    private var onlineRecoveryJob: Job? = null

    private fun canonicalTrack(track: Track): Track = PlaybackIdentity.canonical(track)

    private fun httpStatusCode(error: PlaybackException): Int? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    private fun updatePlaybackState(block: (PlaybackState) -> PlaybackState) {
        _playbackState.value = block(_playbackState.value)
    }

    private fun updateEqualizerState(block: (EqualizerState) -> EqualizerState) {
        _equalizerState.value = block(_equalizerState.value)
    }

    init {
        logger.info("player", "engine_created")
        initPlayer()
        registerNetworkCallback()
        restoreQueueAndState()
    }

    private fun persistQueueAndState() {
        scope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                try {
                    val state = _playbackState.value
                // Online items are persisted by stable source ID. Their
                // expiring URLs are never persisted as the playback URI is
                // normalized to flowave://youtube/<videoId> below.
                val persistableQueue = state.queue.map(::canonicalTrack)
                val currentIdx = state.currentQueueIndex.coerceIn(0, (persistableQueue.size - 1).coerceAtLeast(0))
                val position = withContext(Dispatchers.Main) { exoPlayer?.currentPosition ?: 0L }
                    db.withTransaction {
                        queueDao.clearQueueItems()
                        if (persistableQueue.isNotEmpty()) trackDao.insertTracks(persistableQueue)
                        val queueItems = persistableQueue.mapIndexed { index, track ->
                            com.example.flowave.data.model.QueueItem(trackId = track.id, orderIndex = index)
                        }
                        if (queueItems.isNotEmpty()) queueDao.insertQueueItems(queueItems)
                        queueDao.saveQueueState(
                            com.example.flowave.data.model.QueueState(
                                currentQueueIndex = currentIdx,
                                currentPositionMs = position,
                                repeatMode = state.repeatMode,
                                isShuffleEnabled = state.isShuffleEnabled,
                                isPlaying = state.isPlaying
                            )
                        )
                    }
                } catch (e: Exception) {
                    logger.error("database", "queue_persist_failed", throwable = e)
                    android.util.Log.e("FloWaveAudioEngine", "Failed to persist queue: ${e.message}")
                }
            }
        }
    }

    private fun persistQueueStateOnly() {
        scope.launch(Dispatchers.IO) {
            try {
                val state = _playbackState.value
                val currentIdx = state.currentQueueIndex
                val position = withContext(Dispatchers.Main) { exoPlayer?.currentPosition ?: 0L }
                queueDao.saveQueueState(
                    com.example.flowave.data.model.QueueState(
                        currentQueueIndex = currentIdx,
                        currentPositionMs = position,
                        repeatMode = state.repeatMode,
                        isShuffleEnabled = state.isShuffleEnabled,
                        isPlaying = state.isPlaying
                    )
                )
            } catch (e: Exception) {
                logger.warn("database", "queue_state_persist_failed", throwable = e)
                // Ignore transient write errors
            }
        }
    }

    private fun recordTrackStarted(track: Track) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val stored = trackDao.getTrackById(track.id)
                val normalized = canonicalTrack(track)
                if (stored == null) {
                    trackDao.insertTrack(normalized.copy(playCount = 1, lastPlayedTimestamp = System.currentTimeMillis()))
                } else {
                    trackDao.updateTrack(
                        stored.copy(
                            playCount = stored.playCount + 1,
                            lastPlayedTimestamp = System.currentTimeMillis(),
                            sourceId = normalized.sourceId ?: stored.sourceId,
                            mediaUri = normalized.mediaUri
                        )
                    )
                }
            }.onFailure { android.util.Log.w("FloWaveAudioEngine", "Could not record recent track", it) }
        }
    }

    private fun restoreQueueAndState() {
        scope.launch(Dispatchers.IO) {
            try {
                val dbState = queueDao.getQueueState() ?: return@launch
                val dbItems = queueDao.getQueueItems()
                if (dbItems.isEmpty()) {
                    logger.debug("queue", "restore_empty")
                    return@launch
                }
                
                val tracks = dbItems.mapNotNull { item ->
                    trackDao.getTrackById(item.trackId)
                }.map(::canonicalTrack)
                
                if (tracks.isNotEmpty()) {
                    val restoredIndex = dbState.currentQueueIndex.coerceIn(0, tracks.lastIndex)
                    withContext(Dispatchers.Main) {
                        _playbackState.value = _playbackState.value.copy(
                            queue = tracks,
                            currentQueueIndex = restoredIndex,
                            currentTrack = tracks.getOrNull(restoredIndex),
                            currentPositionMs = dbState.currentPositionMs,
                            repeatMode = dbState.repeatMode,
                            isShuffleEnabled = dbState.isShuffleEnabled,
                            isPlaying = dbState.isPlaying
                        )
                        
                        val mediaItems = tracks.mapNotNull { track -> createMediaItem(track) }
                        exoPlayer?.setMediaItems(mediaItems)
                        exoPlayer?.seekTo(restoredIndex, dbState.currentPositionMs.coerceAtLeast(0L))
                        exoPlayer?.repeatMode = dbState.repeatMode
                        exoPlayer?.shuffleModeEnabled = dbState.isShuffleEnabled
                        exoPlayer?.playWhenReady = dbState.isPlaying
                        exoPlayer?.prepare()
                    }
                    if (tracks.size != dbItems.size || restoredIndex != dbState.currentQueueIndex) {
                        persistQueueAndState()
                        logger.warn("queue", "restore_repaired", context = mapOf("stored" to dbItems.size, "restored" to tracks.size))
                    }
                    logger.info("queue", "restored", context = mapOf("count" to tracks.size, "index" to restoredIndex))
                }
            } catch (e: Exception) {
                logger.error("queue", "restore_failed", throwable = e)
                android.util.Log.e("FloWaveAudioEngine", "Failed to restore queue: ${e.message}")
            }
        }
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

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(panningAudioProcessor))
                    .build()
            }
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLooper(android.os.Looper.getMainLooper())
            .build().apply {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        logger.debug("player", if (isPlaying) "playing" else "paused")
                        _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                        if (isPlaying) {
                            startProgressAndVisualizerLoop()
                        } else {
                            stopProgressLoop()
                        }
                        updateFormatInfo()
                        persistQueueStateOnly()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        logger.debug("player", "state_changed", context = mapOf("state" to state))
                        _playbackState.value = _playbackState.value.copy(
                            isBuffering = state == Player.STATE_BUFFERING,
                            errorMessage = if (state == Player.STATE_READY) null else _playbackState.value.errorMessage
                        )
                        updateFormatInfo()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateFormatInfo()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        val p = exoPlayer ?: return
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = p.currentPosition,
                            durationMs = p.duration.coerceAtLeast(0L)
                        )
                        updateFormatInfo()
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        _playbackState.value = _playbackState.value.copy(
                            currentPositionMs = player.currentPosition,
                            durationMs = player.duration.coerceAtLeast(0L)
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val failedTrack = _playbackState.value.currentTrack
                        logger.error(
                            "player",
                            "media3_error",
                            error.message.orEmpty(),
                            mapOf("code" to error.errorCodeName, "online" to failedTrack?.isOnline, "trackId" to failedTrack?.id),
                            error
                        )
                        android.util.Log.e("FloWaveAudioEngine", "Player error encountered: ${error.message}", error)
                        _playbackState.value = _playbackState.value.copy(
                            isBuffering = false,
                            errorMessage = error.localizedMessage ?: error.message ?: "Playback failed"
                        )
                        
                        val now = System.currentTimeMillis()
                        if (now - lastErrorToastTime > 5000L) {
                            lastErrorToastTime = now
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(context, "Playback error: ${error.localizedMessage ?: error.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        
                        val currentTrack = _playbackState.value.currentTrack
                        if (currentTrack != null && currentTrack.isOnline) {
                            if (onlineRecoveryJob?.isActive == true) return
                            if (!networkAvailable) {
                                wasPlayingBeforeDisconnect = true
                                return
                            }
                            val httpStatus = httpStatusCode(error)
                            android.util.Log.w(
                                "FloWaveAudioEngine",
                                "Online playback failure for ${currentTrack.id}; HTTP status=$httpStatus, media3Code=${error.errorCode}"
                            )
                            if (!OnlinePlaybackPolicy.isRefreshableHttpStatus(httpStatus)) {
                                trackRetryCount.remove(currentTrack.id)
                                playNext()
                                return
                            }
                            val retries = trackRetryCount.getOrDefault(currentTrack.id, 0)
                            val currentPos = _playbackState.value.currentPositionMs
                            if (retries < 3) {
                                trackRetryCount[currentTrack.id] = retries + 1
                                val delayMs = OnlinePlaybackPolicy.retryDelayMs(retries)
                                android.util.Log.w("FloWaveAudioEngine", "Playback error for online track ${currentTrack.title} (retry ${retries + 1}/3). Delaying $delayMs ms then refreshing...")
                                onlineRecoveryJob = scope.launch {
                                    delay(delayMs)
                                    try {
                                        val sourceId = currentTrack.sourceId
                                            ?: currentTrack.id.removePrefix("yt_")
                                        // The user may have selected another item while the
                                        // refresh was running. Never overwrite that newer state.
                                        if (_playbackState.value.currentTrack?.id != currentTrack.id) {
                                            return@launch
                                        }
                                        innerTubeRepo.invalidateStreamUrl(sourceId)
                                        FloWaveCacheManager.invalidate(sourceId)
                                        val curIndex = exoPlayer?.currentMediaItemIndex ?: 0
                                        // Let a new error event schedule the next recovery attempt.
                                        if (onlineRecoveryJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                                            onlineRecoveryJob = null
                                        }
                                        exoPlayer?.prepare()
                                        exoPlayer?.seekTo(curIndex, currentPos)
                                        exoPlayer?.play()
                                        android.util.Log.d("FloWaveAudioEngine", "Invalidated online stream and resumed lazy resolution")
                                        return@launch
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        android.util.Log.e("FloWaveAudioEngine", "Failed to refresh expired URL mid-play: ${e.message}")
                                        if (_playbackState.value.currentTrack?.id == currentTrack.id) playNext()
                                    }
                                }.also { job ->
                                    job.invokeOnCompletion { if (onlineRecoveryJob === job) onlineRecoveryJob = null }
                                }
                            } else {
                                android.util.Log.e("FloWaveAudioEngine", "Max retries reached for track ${currentTrack.title}. Skipping to next track.")
                                trackRetryCount.remove(currentTrack.id)
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
                        logger.info(
                            "player",
                            "track_transition",
                            context = mapOf("reason" to reason, "index" to (exoPlayer?.currentMediaItemIndex ?: -1))
                        )
                        trackRetryCount.clear()
                        val index = exoPlayer?.currentMediaItemIndex ?: -1
                        val queue = _playbackState.value.queue
                        if (index in queue.indices) {
                            val track = queue[index]
                            _playbackState.value = _playbackState.value.copy(
                                currentTrack = track,
                                currentQueueIndex = index,
                                durationMs = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L,
                                errorMessage = null
                            )
                            recordTrackStarted(track)
                        }
                        updateFormatInfo()
                        persistQueueAndState()
                    }
                }
                playerListener = listener
                addListener(listener)
            }

        try {
            val sessionToken = androidx.media3.session.SessionToken(
                context,
                android.content.ComponentName(context, FloWaveMediaService::class.java)
            )
            val controllerFuture = androidx.media3.session.MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture.addListener(
                {
                    try {
                        mediaController = controllerFuture.get()
                        android.util.Log.d("FloWaveAudioEngine", "MediaController bound to FloWaveMediaService successfully")
                    } catch (e: Exception) {
                        android.util.Log.e("FloWaveAudioEngine", "Failed to bind MediaController: ${e.message}")
                    }
                },
                androidx.core.content.ContextCompat.getMainExecutor(context)
            )
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error binding MediaController or initializing Service connection", e)
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
        updatePlaybackState { it.copy(audioFormatInfo = info) }
    }

    @OptIn(UnstableApi::class)
    private fun getAudioFormatInfo(format: Format?): String {
        if (format == null) return "Analyzing Audio..."
        val mime = format.sampleMimeType
        val codec = when {
            mime == null -> "Unknown"
            mime.contains("opus") -> "Opus"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"
            mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
            mime.contains("flac") -> "FLAC"
            mime.contains("ogg") -> "Vorbis"
            mime.contains("webm") -> "WebM"
            else -> mime.substringAfter("audio/").uppercase()
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
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        
        try {
            equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
        } catch (t: Throwable) {
            android.util.Log.w("FloWaveAudioEngine", "Equalizer not available: ${t.message}")
            equalizer = null
        }
        
        try {
            bassBoost = BassBoost(0, audioSessionId).apply { enabled = true }
        } catch (t: Throwable) {
            android.util.Log.w("FloWaveAudioEngine", "BassBoost not available: ${t.message}")
            bassBoost = null
        }
        
        try {
            virtualizer = Virtualizer(0, audioSessionId).apply { enabled = true }
        } catch (t: Throwable) {
            android.util.Log.w("FloWaveAudioEngine", "Virtualizer not available: ${t.message}")
            virtualizer = null
        }
        
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply { enabled = true }
        } catch (t: Throwable) {
            android.util.Log.w("FloWaveAudioEngine", "LoudnessEnhancer not available: ${t.message}")
            loudnessEnhancer = null
        }
        
        try {
            presetReverb = PresetReverb(0, audioSessionId).apply { enabled = true }
        } catch (t: Throwable) {
            android.util.Log.w("FloWaveAudioEngine", "PresetReverb not available: ${t.message}")
            presetReverb = null
        }
    }

    fun stopPlayback() {
        logger.info("player", "stop_requested")
        onlineRecoveryJob?.cancel()
        onlineRecoveryJob = null
        trackRetryCount.clear()
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error stopping playback", e)
        }
        stopProgressLoop()
        _playbackState.value = PlaybackState()
        persistQueueAndState()
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
                android.util.Log.e("FloWaveAudioEngine", "Error parsing artwork URI", e)
            }
        }

        val uriStr = PlaybackIdentity.mediaUri(track)
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
        logger.info("queue", "set_queue", context = mapOf("count" to queue.size, "startIndex" to startIndex, "online" to queue.count { it.isOnline }))
        if (queue.isEmpty()) return
        val player = exoPlayer ?: return

        val normalizedQueue = queue.map(::canonicalTrack)
        val playableEntries = normalizedQueue.mapNotNull { track ->
            createMediaItem(track)?.let { mediaItem -> track to mediaItem }
        }
        if (playableEntries.isEmpty()) return
        val requestedTrack = normalizedQueue.getOrNull(startIndex)
        val effectiveIndex = playableEntries.indexOfFirst { it.first.id == requestedTrack?.id }
            .takeIf { it >= 0 } ?: 0
        val playableQueue = playableEntries.map { it.first }

        _playbackState.value = _playbackState.value.copy(
            queue = playableQueue,
            currentQueueIndex = effectiveIndex,
            currentTrack = playableQueue.getOrNull(effectiveIndex),
            errorMessage = null
        )

        player.setMediaItems(playableEntries.map { it.second }, effectiveIndex, 0L)
        player.prepare()
        player.play()
        persistQueueAndState()
    }

    private fun rebuildPlayerQueue(queue: List<Track>, startIndex: Int, positionMs: Long, resume: Boolean) {
        val player = exoPlayer ?: return
        val entries = queue.map { canonicalTrack(it) to createMediaItem(canonicalTrack(it)) }
            .mapNotNull { (track, item) -> item?.let { track to it } }
        if (entries.isEmpty()) {
            player.stop()
            player.clearMediaItems()
            return
        }
        val index = startIndex.coerceIn(0, entries.lastIndex)
        player.setMediaItems(entries.map { it.second }, index, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = resume
    }

    /** Starts an online result; the stream is resolved lazily by Media3. */
    suspend fun playOnlineTrack(track: com.example.flowave.data.model.InnerTubeTrack) {
        logger.info("online", "play_requested", context = mapOf("videoId" to track.id))
        playTrack(innerTubeRepo.createOnlineTrack(track))
    }

    fun playTrack(track: Track) {
        logger.info("player", "track_requested", context = mapOf("trackId" to track.id, "source" to track.source))
        val normalizedTrack = canonicalTrack(track)
        val currentQueue = _playbackState.value.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == normalizedTrack.id }
        if (index >= 0) {
            setQueueAndPlay(currentQueue, index)
        } else {
            currentQueue.add(0, normalizedTrack)
            setQueueAndPlay(currentQueue, 0)
        }
    }

    fun togglePlayPause() {
        logger.debug("player", "toggle_play_pause")
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun retryCurrentTrack() {
        logger.info("player", "retry_requested", context = mapOf("trackId" to _playbackState.value.currentTrack?.id))
        val track = _playbackState.value.currentTrack ?: return
        val player = exoPlayer ?: return
        if (track.isOnline) {
            val sourceId = PlaybackIdentity.sourceId(track) ?: return
            innerTubeRepo.invalidateStreamUrl(sourceId)
            FloWaveCacheManager.invalidate(sourceId)
        }
        _playbackState.value = _playbackState.value.copy(errorMessage = null)
        player.prepare()
        player.play()
    }

    fun toggleCurrentFavorite() {
        val current = _playbackState.value.currentTrack ?: return
        val updated = current.copy(isFavorite = !current.isFavorite)
        val updatedQueue = _playbackState.value.queue.map { if (it.id == updated.id) updated else it }
        _playbackState.value = _playbackState.value.copy(currentTrack = updated, queue = updatedQueue)
        scope.launch(Dispatchers.IO) { trackDao.insertTrack(updated) }
    }

    fun playNext() {
        logger.info("queue", "next_requested")
        val player = exoPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            val queue = _playbackState.value.queue
            if (queue.isNotEmpty() && _playbackState.value.repeatMode == Player.REPEAT_MODE_ALL) {
                setQueueAndPlay(queue, 0)
            } else {
                player.pause()
                persistQueueStateOnly()
            }
        }
    }

    fun playPrevious() {
        logger.info("queue", "previous_requested")
        val player = exoPlayer ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        logger.debug("player", "seek", context = mapOf("positionMs" to positionMs))
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
        logger.info("queue", "shuffle_toggled")
        val newShuffle = !_playbackState.value.isShuffleEnabled
        exoPlayer?.shuffleModeEnabled = newShuffle
        _playbackState.value = _playbackState.value.copy(isShuffleEnabled = newShuffle)
        persistQueueStateOnly()
    }

    fun toggleSmartShuffle() {
        val current = _playbackState.value.isSmartShuffle
        val newSmart = !current
        _playbackState.value = _playbackState.value.copy(
            isSmartShuffle = newSmart,
            isShuffleEnabled = newSmart
        )
        exoPlayer?.shuffleModeEnabled = newSmart
        persistQueueStateOnly()
    }

    fun toggleRepeatMode() {
        logger.info("queue", "repeat_toggled")
        val nextMode = when (_playbackState.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        exoPlayer?.repeatMode = nextMode
        _playbackState.value = _playbackState.value.copy(repeatMode = nextMode)
        persistQueueStateOnly()
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
        logger.info("queue", "reordered", context = mapOf("from" to fromIndex, "to" to toIndex))
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
            rebuildPlayerQueue(
                queue = queue,
                startIndex = currentIdx,
                positionMs = exoPlayer?.currentPosition ?: 0L,
                resume = exoPlayer?.playWhenReady == true
            )
            persistQueueAndState()
        }
    }

    fun addToQueueNext(track: Track) {
        logger.info("queue", "added_next", context = mapOf("trackId" to track.id, "source" to track.source))
        val queue = _playbackState.value.queue.toMutableList()
        val currentIdx = _playbackState.value.currentQueueIndex
        val insertIndex = if (currentIdx in queue.indices) currentIdx + 1 else queue.size
        queue.add(insertIndex, canonicalTrack(track))
        val newCurrentIdx = if (insertIndex <= currentIdx) currentIdx + 1 else currentIdx
        _playbackState.value = _playbackState.value.copy(queue = queue, currentQueueIndex = newCurrentIdx)
        rebuildPlayerQueue(queue, newCurrentIdx, exoPlayer?.currentPosition ?: 0L, exoPlayer?.playWhenReady == true)
        persistQueueAndState()
    }

    fun addToQueueLast(track: Track) {
        logger.info("queue", "added_last", context = mapOf("trackId" to track.id, "source" to track.source))
        val queue = _playbackState.value.queue.toMutableList()
        queue.add(canonicalTrack(track))
        _playbackState.value = _playbackState.value.copy(queue = queue)
        rebuildPlayerQueue(queue, _playbackState.value.currentQueueIndex, exoPlayer?.currentPosition ?: 0L, exoPlayer?.playWhenReady == true)
        persistQueueAndState()
    }

    fun removeFromQueue(index: Int) {
        logger.info("queue", "removed", context = mapOf("index" to index))
        val queue = _playbackState.value.queue.toMutableList()
        if (index in queue.indices) {
            queue.removeAt(index)
            var currentIdx = _playbackState.value.currentQueueIndex
            if (index < currentIdx) currentIdx--
            if (queue.isEmpty()) {
                clearQueue()
                return
            }
            if (index == _playbackState.value.currentQueueIndex) currentIdx = currentIdx.coerceIn(0, queue.lastIndex)
            _playbackState.value = _playbackState.value.copy(
                queue = queue,
                currentQueueIndex = currentIdx,
                currentTrack = queue.getOrNull(currentIdx)
            )
            rebuildPlayerQueue(queue, currentIdx, 0L, exoPlayer?.playWhenReady == true)
            persistQueueAndState()
        }
    }

    fun clearQueue() {
        logger.info("queue", "cleared")
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _playbackState.value = PlaybackState()
        persistQueueAndState()
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
            android.util.Log.e("FloWaveAudioEngine", "Error setting equalizer band level", e)
        }
    }

    fun setBassBoost(strength: Short) {
        try {
            bassBoost?.setStrength(strength)
            _equalizerState.value = _equalizerState.value.copy(bassBoostStrength = strength)
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error setting bass boost strength", e)
        }
    }

    fun setVirtualizer(strength: Short) {
        try {
            virtualizer?.setStrength(strength)
            _equalizerState.value = _equalizerState.value.copy(virtualizerStrength = strength)
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error setting virtualizer strength", e)
        }
    }

    fun setLoudnessEnhancerGain(gainMb: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gainMb)
            _equalizerState.value = _equalizerState.value.copy(loudnessEnhancerGainDb = gainMb / 100f)
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error setting loudness enhancer gain", e)
        }
    }

    fun setPreampDb(db: Float) {
        _equalizerState.value = _equalizerState.value.copy(preampDb = db)
        setLoudnessEnhancerGain((db * 100).toInt())
    }

    fun setStereoBalance(balance: Float) {
        _equalizerState.value = _equalizerState.value.copy(stereoBalance = balance)
        panningAudioProcessor.setBalance(balance)
        exoPlayer?.volume = 1f
    }

    fun setPresetReverbName(name: String, preset: Short) {
        try {
            presetReverb?.preset = preset
            _equalizerState.value = _equalizerState.value.copy(presetReverbName = name, presetReverb = preset)
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error setting preset reverb", e)
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
            var tickCount = 0
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

                    tickCount++
                    if (tickCount % 6 == 0) { // 6 * 500ms = 3000ms = 3 seconds (was 30 * 100ms)
                        persistQueueStateOnly()
                    }
                }
                delay(500L) // 500ms delay is 5x more battery friendly!
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
                    networkAvailable = true
                    scope.launch(Dispatchers.Main) {
                        val player = exoPlayer ?: return@launch
                        val currentTrack = _playbackState.value.currentTrack
                        if (currentTrack != null && currentTrack.isOnline) {
                            if (!player.isPlaying && wasPlayingBeforeDisconnect) {
                                try {
                                    val sourceId = currentTrack.sourceId ?: currentTrack.id.removePrefix("yt_")
                                    innerTubeRepo.invalidateStreamUrl(sourceId)
                                    FloWaveCacheManager.invalidate(sourceId)
                                    player.prepare()
                                    player.play()
                                    wasPlayingBeforeDisconnect = false
                                } catch (e: Exception) {
                                    android.util.Log.e("FloWaveAudioEngine", "Error preparing/playing on connection restore", e)
                                }
                            }
                        }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    android.util.Log.w("FloWaveAudioEngine", "Internet connection lost.")
                    networkAvailable = false
                    val currentTrack = _playbackState.value.currentTrack
                    if (currentTrack != null && currentTrack.isOnline) {
                        wasPlayingBeforeDisconnect = exoPlayer?.isPlaying == true
                    }
                }
            }
            networkCallback?.let { callback ->
                connectivityManager?.registerDefaultNetworkCallback(callback)
            }
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error registering network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error unregistering network callback", e)
        }
    }

    fun release() {
        logger.info("lifecycle", "engine_released")
        onlineRecoveryJob?.cancel()
        onlineRecoveryJob = null
        trackRetryCount.clear()
        try {
            scope.cancel()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error cancelling coroutine scope during release", e)
        }
        try {
            mediaController?.release()
            mediaController = null
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing mediaController during release", e)
        }
        unregisterNetworkCallback()
        
        playerListener?.let {
            exoPlayer?.removeListener(it)
            playerListener = null
        }

        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing exoPlayer", e)
        }

        try {
            equalizer?.release()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing Equalizer", e)
        } finally {
            equalizer = null
        }

        try {
            bassBoost?.release()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing BassBoost", e)
        } finally {
            bassBoost = null
        }

        try {
            virtualizer?.release()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing Virtualizer", e)
        } finally {
            virtualizer = null
        }

        try {
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing LoudnessEnhancer", e)
        } finally {
            loudnessEnhancer = null
        }

        try {
            presetReverb?.release()
        } catch (e: Exception) {
            android.util.Log.e("FloWaveAudioEngine", "Error releasing PresetReverb", e)
        } finally {
            presetReverb = null
        }

        // Release the audio SimpleCache
        FloWaveCacheManager.releaseCache()
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
