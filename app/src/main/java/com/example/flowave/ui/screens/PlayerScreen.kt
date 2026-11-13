package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.data.model.LrcLine
import com.example.flowave.ui.components.CanvasVisualizer
import com.example.flowave.ui.components.DynamicAudioDeviceSelectorPill
import com.example.flowave.ui.components.VisualizerType
import com.example.flowave.ui.theme.*
import com.example.flowave.utils.SettingsSchema

@Composable
fun PlayerScreen(
    audioEngine: FloWaveAudioEngine,
    lrcLines: List<LrcLine>,
    onCloseClick: () -> Unit,
    onOpenEqualizerClick: () -> Unit,
    settingsJson: String = SettingsSchema.getDefaultJson(),
    modifier: Modifier = Modifier
) {
    val playbackState by audioEngine.playbackState.collectAsStateWithLifecycle()
    val visualizerWaveform by audioEngine.visualizerWaveform.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack

    var activeTab by remember { mutableIntStateOf(0) } // 0: Art/Visualizer, 1: Lyrics, 2: Queue
    var selectedVisualizerType by remember { mutableStateOf(VisualizerType.BARS) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    if (track == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PureBlack),
            contentAlignment = Alignment.Center
        ) {
            Text("No track selected", color = TextSecondary)
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Minimalist Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                        .border(1.dp, GlassBorder, CircleShape)
                        .testTag("player_close_btn")
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = TextPrimary
                    )
                }

                // Segmented Mode Pills
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(DarkSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(100.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(
                        0 to "Player",
                        1 to "Lyrics",
                        2 to "Queue (${playbackState.queue.size})"
                    ).forEach { (idx, label) ->
                        val selected = activeTab == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (selected) CyanNeon else Color.Transparent)
                                .clickable { activeTab = idx }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) PureBlack else TextSecondary
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { showSleepTimerDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        BadgedBox(badge = {
                            if (playbackState.sleepTimerRemainingMs > 0) {
                                Badge(containerColor = CyanNeon, contentColor = PureBlack) {
                                    Text("${playbackState.sleepTimerRemainingMs / 60000}m", fontSize = 8.sp)
                                }
                            }
                        }) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = "Sleep Timer",
                                tint = if (playbackState.sleepTimerRemainingMs > 0) CyanNeon else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenEqualizerClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("player_eq_btn")
                    ) {
                        Icon(
                            Icons.Default.Equalizer,
                            contentDescription = "Equalizer",
                            tint = CyanNeon,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            audioEngine.stopPlayback()
                            onCloseClick()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("player_stop_btn_top")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Audio output pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                DynamicAudioDeviceSelectorPill()
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Center Content Area
            when (activeTab) {
                0 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.84f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(DarkSurface)
                                .border(1.dp, GlassBorderWhite, RoundedCornerShape(24.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!track.artworkUri.isNullOrBlank()) {
                                AsyncImage(
                                    model = track.artworkUri,
                                    contentDescription = "Artwork",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = CyanNeon,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                        }

                        if (SettingsSchema.getBoolean(settingsJson, "show_visualizer")) {
                            CanvasVisualizer(
                                waveform = visualizerWaveform,
                                type = selectedVisualizerType,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .align(Alignment.BottomCenter)
                            )
                        }
                    }

                    if (SettingsSchema.getBoolean(settingsJson, "show_visualizer")) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VisualizerType.entries.forEach { type ->
                                val selected = selectedVisualizerType == type
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) CyanNeon.copy(alpha = 0.16f) else Color.Transparent)
                                        .clickable { selectedVisualizerType = type }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = type.name.take(4),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) CyanNeon else TextMuted
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                    ) {
                        if (lrcLines.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No synced lyrics available for this track", color = TextMuted, fontSize = 14.sp)
                            }
                        } else {
                            val currentPos = playbackState.currentPositionMs
                            val activeIndex = lrcLines.indexOfLast { it.timestampMs <= currentPos }.coerceAtLeast(0)

                            LaunchedEffect(activeIndex) {
                                lazyListState.animateScrollToItem(activeIndex)
                            }

                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                itemsIndexed(lrcLines) { index, line ->
                                    val isActive = index == activeIndex
                                    Text(
                                        text = line.text,
                                        fontSize = if (isActive) 20.sp else 15.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) CyanNeon else TextMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { audioEngine.seekTo(line.timestampMs) }
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(DarkSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Up Next (${playbackState.queue.size})",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                TextButton(onClick = { audioEngine.clearQueue() }) {
                                    Text("Clear", color = ErrorCrimson, fontSize = 12.sp)
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(playbackState.queue) { idx, item ->
                                    val isCurrent = idx == playbackState.currentQueueIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isCurrent) CyanNeon.copy(alpha = 0.12f) else Color.Transparent)
                                            .clickable { audioEngine.setQueueAndPlay(playbackState.queue, idx) }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.title,
                                                color = if (isCurrent) CyanNeon else TextPrimary,
                                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                item.artist,
                                                color = TextMuted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row {
                                            IconButton(
                                                onClick = { if (idx > 0) audioEngine.reorderQueue(idx, idx - 1) },
                                                enabled = idx > 0,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.KeyboardArrowUp,
                                                    contentDescription = "Move up",
                                                    tint = if (idx > 0) TextSecondary else TextMuted.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { if (idx < playbackState.queue.lastIndex) audioEngine.reorderQueue(idx, idx + 1) },
                                                enabled = idx < playbackState.queue.lastIndex,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.KeyboardArrowDown,
                                                    contentDescription = "Move down",
                                                    tint = if (idx < playbackState.queue.lastIndex) TextSecondary else TextMuted.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { audioEngine.removeFromQueue(idx) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Remove",
                                                    tint = TextMuted,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Track Metadata & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = track.artist,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DarkSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                        ) {
                            Text(
                                text = playbackState.audioFormatInfo,
                                color = CyanNeon,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { audioEngine.toggleCurrentFavorite() }) {
                        Icon(
                            if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (track.isFavorite) CyanNeon else TextMuted
                        )
                    }
                    IconButton(onClick = { showSpeedDialog = true }) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Speed",
                            tint = if (playbackState.playbackSpeed != 1.0f || playbackState.pitch != 1.0f) CyanNeon else TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Seekbar & Timestamps
            val posMs = playbackState.currentPositionMs
            val durMs = playbackState.durationMs.coerceAtLeast(1L)
            var sliderPos by remember { mutableFloatStateOf(0f) }
            var isUserSeeking by remember { mutableStateOf(false) }
            val currentProgress = if (isUserSeeking) sliderPos else (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

            Column(modifier = Modifier.fillMaxWidth()) {
                if (playbackState.isBuffering) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = CyanNeon,
                        trackColor = Color.Transparent
                    )
                }
                Slider(
                    value = currentProgress,
                    onValueChange = {
                        isUserSeeking = true
                        sliderPos = it
                    },
                    onValueChangeFinished = {
                        isUserSeeking = false
                        audioEngine.seekTo((sliderPos * durMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = CyanNeon,
                        activeTrackColor = CyanNeon,
                        inactiveTrackColor = GlassBorderWhite
                    ),
                    modifier = Modifier.testTag("player_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMs(if (isUserSeeking) (sliderPos * durMs).toLong() else posMs),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatMs(durMs),
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            playbackState.errorMessage?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Playback failed", color = ErrorCrimson, fontSize = 12.sp)
                    TextButton(onClick = { audioEngine.retryCurrentTrack() }) {
                        Text("Retry", color = CyanNeon, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Minimal A-B Loop & Silence Skip Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { audioEngine.setPointA() }) {
                    Text(
                        text = playbackState.pointA?.let { "A: ${formatMs(it)}" } ?: "Loop A",
                        color = if (playbackState.pointA != null) CyanNeon else TextMuted,
                        fontSize = 11.sp
                    )
                }
                TextButton(onClick = { audioEngine.setPointB() }) {
                    Text(
                        text = playbackState.pointB?.let { "B: ${formatMs(it)}" } ?: "Loop B",
                        color = if (playbackState.pointB != null) CyanNeon else TextMuted,
                        fontSize = 11.sp
                    )
                }
                if (playbackState.pointA != null || playbackState.pointB != null) {
                    TextButton(onClick = { audioEngine.clearABLoop() }) {
                        Text("Clear", color = ErrorCrimson, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { audioEngine.toggleSkipSilence() }) {
                    Text(
                        text = if (playbackState.skipSilence) "Skip Silence: ON" else "Skip Silence: OFF",
                        color = if (playbackState.skipSilence) CyanNeon else TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Primary Transport Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { audioEngine.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.isShuffleEnabled) CyanNeon else TextMuted
                    )
                }

                IconButton(
                    onClick = { audioEngine.playPrevious() },
                    modifier = Modifier.testTag("player_prev_btn")
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                FloatingActionButton(
                    onClick = { audioEngine.togglePlayPause() },
                    containerColor = CyanNeon,
                    contentColor = PureBlack,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("player_play_pause_fab")
                ) {
                    if (playbackState.isBuffering && !playbackState.isPlaying) {
                        CircularProgressIndicator(
                            color = PureBlack,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { audioEngine.playNext() },
                    modifier = Modifier.testTag("player_next_btn")
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = TextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(
                    onClick = {
                        audioEngine.stopPlayback()
                        onCloseClick()
                    },
                    modifier = Modifier.testTag("player_stop_btn")
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop Track",
                        tint = TextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = { audioEngine.toggleRepeatMode() }) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (playbackState.repeatMode != 0) CyanNeon else TextMuted
                    )
                }
            }
        }
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Speed & Pitch", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Speed: ${"%.2f".format(playbackState.playbackSpeed)}x", color = TextSecondary)
                    Slider(
                        value = playbackState.playbackSpeed,
                        onValueChange = { audioEngine.setPlaybackSpeed(it) },
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                    )

                    Text("Pitch: ${"%.2f".format(playbackState.pitch)}x", color = TextSecondary)
                    Slider(
                        value = playbackState.pitch,
                        onValueChange = { audioEngine.setPitch(it) },
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CyanNeon, activeTrackColor = CyanNeon)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Done", color = CyanNeon)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    audioEngine.setPlaybackSpeed(1.0f)
                    audioEngine.setPitch(1.0f)
                }) {
                    Text("Reset", color = TextMuted)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showSleepTimerDialog) {
        var selectedMinutes by remember { mutableIntStateOf(15) }
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Stop playback automatically after:", color = TextSecondary, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf(5, 15, 30, 45, 60).forEach { mins ->
                            FilterChip(
                                selected = selectedMinutes == mins,
                                onClick = { selectedMinutes = mins },
                                label = { Text("${mins}m", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanNeon,
                                    selectedLabelColor = PureBlack
                                )
                            )
                        }
                    }
                    if (playbackState.sleepTimerRemainingMs > 0) {
                        Text(
                            "Remaining: ${playbackState.sleepTimerRemainingMs / 1000}s",
                            color = CyanNeon,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    audioEngine.startSleepTimer(selectedMinutes)
                    showSleepTimerDialog = false
                }) {
                    Text("Start", color = CyanNeon)
                }
            },
            dismissButton = {
                if (playbackState.sleepTimerRemainingMs > 0) {
                    TextButton(onClick = {
                        audioEngine.cancelSleepTimer()
                        showSleepTimerDialog = false
                    }) {
                        Text("Cancel Timer", color = ErrorCrimson)
                    }
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
