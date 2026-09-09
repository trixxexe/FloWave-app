package com.example.flowave.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.data.model.LrcLine
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.flowave.ui.components.CanvasVisualizer
import com.example.flowave.ui.components.DynamicAudioDeviceSelectorPill
import com.example.flowave.ui.components.GlassCard
import com.example.flowave.ui.components.VisualizerType
import com.example.flowave.ui.theme.*

@Composable
fun PlayerScreen(
    audioEngine: FloWaveAudioEngine,
    lrcLines: List<LrcLine>,
    onCloseClick: () -> Unit,
    onOpenEqualizerClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playbackState by audioEngine.playbackState.collectAsStateWithLifecycle()
    val visualizerWaveform by audioEngine.visualizerWaveform.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack

    var activeTab by remember { mutableStateOf(0) } // 0: Player, 1: Karaoke Lyrics, 2: Queue
    var selectedVisualizerType by remember { mutableStateOf(VisualizerType.BARS) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    if (track == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No track selected", color = TextSecondary)
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Blurred Album Art Background Layer
        AsyncImage(
            model = track.artworkUri ?: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=600",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.60f),
                            DarkBackground.copy(alpha = 0.95f),
                            DarkBackground
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseClick, modifier = Modifier.testTag("player_close_btn")) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close", tint = TextPrimary)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        label = { Text("Visualizer", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanNeon,
                            selectedLabelColor = PureBlack
                        )
                    )
                    FilterChip(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        label = { Text("LRC Lyrics", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanNeon,
                            selectedLabelColor = PureBlack
                        )
                    )
                    FilterChip(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        label = { Text("Queue (${playbackState.queue.size})", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanNeon,
                            selectedLabelColor = PureBlack
                        )
                    )
                }

                Row {
                    IconButton(onClick = { showSleepTimerDialog = true }) {
                        BadgedBox(badge = {
                            if (playbackState.sleepTimerRemainingMs > 0) {
                                Badge(containerColor = CyanNeon, contentColor = PureBlack) {
                                    Text("${playbackState.sleepTimerRemainingMs / 60000}m", fontSize = 9.sp)
                                }
                            }
                        }) {
                            Icon(Icons.Default.Timer, contentDescription = "Sleep Timer", tint = if (playbackState.sleepTimerRemainingMs > 0) CyanNeon else TextPrimary)
                        }
                    }
                    IconButton(onClick = onOpenEqualizerClick, modifier = Modifier.testTag("player_eq_btn")) {
                        Icon(Icons.Default.Equalizer, contentDescription = "Equalizer", tint = CyanNeon)
                    }
                    IconButton(
                        onClick = {
                            audioEngine.stopPlayback()
                            onCloseClick()
                        },
                        modifier = Modifier.testTag("player_stop_btn_top")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop & Reset App", tint = Color.Red)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic System Audio Route Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                DynamicAudioDeviceSelectorPill()
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (activeTab == 0) {
                // Main Artwork & Visualizer Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    // Artwork Glass Card
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .aspectRatio(1f),
                        cornerRadius = 28.dp
                    ) {
                        AsyncImage(
                            model = track.artworkUri ?: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=600",
                            contentDescription = "Artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Audio Visualizer Canvas Layer Overlay
                    CanvasVisualizer(
                        waveform = visualizerWaveform,
                        type = selectedVisualizerType,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .align(Alignment.BottomCenter)
                    )
                }

                // Visualizer Mode Selector Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VisualizerType.entries.forEach { type ->
                        IconButton(
                            onClick = { selectedVisualizerType = type },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = type.name.take(3),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedVisualizerType == type) CyanNeon else TextMuted
                            )
                        }
                    }
                }
            } else if (activeTab == 1) {
                // Synced LRC Karaoke Lyrics Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 24.dp
                    ) {
                        if (lrcLines.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No synced LRC lyrics found", color = TextMuted)
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
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                itemsIndexed(lrcLines) { index, line ->
                                    val isActive = index == activeIndex
                                    Text(
                                        text = line.text,
                                        fontSize = if (isActive) 22.sp else 16.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) CyanNeon else TextMuted,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Queue Management Drawer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 24.dp
                    ) {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Up Next (${playbackState.queue.size})", color = TextPrimary, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { audioEngine.clearQueue() }) {
                                    Text("Clear All", color = Color.Red, fontSize = 12.sp)
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(playbackState.queue) { idx, item ->
                                    val isCurrent = idx == playbackState.currentQueueIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isCurrent) CyanNeon.copy(alpha = 0.15f) else Color.Transparent)
                                            .clickable { audioEngine.setQueueAndPlay(playbackState.queue, idx) }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.title,
                                                color = if (isCurrent) CyanNeon else TextPrimary,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp,
                                                maxLines = 1
                                            )
                                            Text(item.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                                        }
                                        Row {
                                            IconButton(
                                                onClick = { if (idx > 0) audioEngine.reorderQueue(idx, idx - 1) },
                                                enabled = idx > 0
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowUp, "Move up", tint = if (idx > 0) CyanNeon else TextMuted, modifier = Modifier.size(20.dp))
                                            }
                                            IconButton(
                                                onClick = { if (idx < playbackState.queue.lastIndex) audioEngine.reorderQueue(idx, idx + 1) },
                                                enabled = idx < playbackState.queue.lastIndex
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowDown, "Move down", tint = if (idx < playbackState.queue.lastIndex) CyanNeon else TextMuted, modifier = Modifier.size(20.dp))
                                            }
                                            IconButton(onClick = { audioEngine.removeFromQueue(idx) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Track Details Info & Audio Format Badge
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CyanNeon.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = playbackState.audioFormatInfo,
                                color = CyanNeon,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "${track.artist} • ${track.album}",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                        Icon(Icons.Default.Speed, contentDescription = "Speed", tint = CyanNeon)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Seekbar Slider & Timestamps
            val posMs = playbackState.currentPositionMs
            val durMs = playbackState.durationMs.coerceAtLeast(1L)
            var sliderPos by remember { mutableFloatStateOf(0f) }
            var isUserSeeking by remember { mutableStateOf(false) }

            val currentProgress = if (isUserSeeking) sliderPos else (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

            Column(modifier = Modifier.fillMaxWidth()) {
                if (playbackState.isBuffering) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
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
                        inactiveTrackColor = Color(0x33FFFFFF)
                    ),
                    modifier = Modifier.testTag("player_seekbar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = formatMs(if (isUserSeeking) (sliderPos * durMs).toLong() else posMs), color = TextMuted, fontSize = 12.sp)
                    Text(text = formatMs(durMs), color = TextMuted, fontSize = 12.sp)
                }
            }

            playbackState.errorMessage?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Playback failed", color = Color(0xFFFF9E9E), fontSize = 12.sp)
                    TextButton(onClick = { audioEngine.retryCurrentTrack() }) {
                        Text("Retry", color = CyanNeon)
                    }
                }
            }

            // A-B Loop Controls & Smart Features Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { audioEngine.setPointA() }) {
                    Text(
                        text = playbackState.pointA?.let { "A: ${formatMs(it)}" } ?: "Set A",
                        color = if (playbackState.pointA != null) CyanNeon else TextMuted,
                        fontSize = 11.sp
                    )
                }
                TextButton(onClick = { audioEngine.setPointB() }) {
                    Text(
                        text = playbackState.pointB?.let { "B: ${formatMs(it)}" } ?: "Set B",
                        color = if (playbackState.pointB != null) CyanNeon else TextMuted,
                        fontSize = 11.sp
                    )
                }
                if (playbackState.pointA != null || playbackState.pointB != null) {
                    TextButton(onClick = { audioEngine.clearABLoop() }) {
                        Text("Clear Loop", color = Color.Red, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { audioEngine.toggleSkipSilence() }) {
                    Text(
                        text = if (playbackState.skipSilence) "Skip Silence ON" else "Skip Silence OFF",
                        color = if (playbackState.skipSilence) CyanNeon else TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Playback Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle
                IconButton(onClick = { audioEngine.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.isShuffleEnabled) CyanNeon else TextMuted
                    )
                }

                // Previous
                IconButton(onClick = { audioEngine.playPrevious() }, modifier = Modifier.testTag("player_prev_btn")) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = TextPrimary, modifier = Modifier.size(36.dp))
                }

                // Play / Pause FAB
                FloatingActionButton(
                    onClick = { audioEngine.togglePlayPause() },
                    containerColor = CyanNeon,
                    contentColor = PureBlack,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(68.dp)
                        .testTag("player_play_pause_fab")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Next
                IconButton(onClick = { audioEngine.playNext() }, modifier = Modifier.testTag("player_next_btn")) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = TextPrimary, modifier = Modifier.size(36.dp))
                }

                // Stop Button
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
                        tint = Color.Red,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Repeat
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

    // Speed & Pitch Adjustment Dialog
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed & Pitch", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Speed: ${"%.2f".format(playbackState.playbackSpeed)}x", color = TextSecondary)
                    Slider(
                        value = playbackState.playbackSpeed,
                        onValueChange = { audioEngine.setPlaybackSpeed(it) },
                        valueRange = 0.5f..2.5f
                    )

                    Text("Pitch: ${"%.2f".format(playbackState.pitch)}x", color = TextSecondary)
                    Slider(
                        value = playbackState.pitch,
                        onValueChange = { audioEngine.setPitch(it) },
                        valueRange = 0.5f..2.0f
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Done", color = CyanNeon)
                }
            },
            containerColor = DarkSurface
        )
    }

    // Sleep Timer Setup Dialog
    if (showSleepTimerDialog) {
        var selectedMinutes by remember { mutableIntStateOf(15) }
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Turn off audio automatically in:", color = TextSecondary)
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
                            "Active timer: ${playbackState.sleepTimerRemainingMs / 1000}s remaining",
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
                    Text("Start Timer", color = CyanNeon)
                }
            },
            dismissButton = {
                if (playbackState.sleepTimerRemainingMs > 0) {
                    TextButton(onClick = {
                        audioEngine.cancelSleepTimer()
                        showSleepTimerDialog = false
                    }) {
                        Text("Cancel Timer", color = Color.Red)
                    }
                }
            },
            containerColor = DarkSurface
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
