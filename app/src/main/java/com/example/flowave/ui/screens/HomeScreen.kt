package com.example.flowave.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.ui.components.LiquidGlassCard
import com.example.flowave.ui.theme.*

/**
 * Online-first minimalist Home Screen featuring personalized YouTube Music
 * recommendations, Quick Picks, and recently streamed tracks with iOS Liquid Glass styling.
 */
@Composable
fun HomeScreen(
    tracks: List<Track>,
    featuredOnline: List<InnerTubeTrack>,
    totalListeningTimeMs: Long,
    totalPlayCount: Int,
    onTrackClick: (Track) -> Unit,
    onOnlineTrackClick: (InnerTubeTrack) -> Unit,
    onProfileClick: () -> Unit,
    onDownloaderClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onExploreOnlineClick: (() -> Unit)? = null,
    onPlayQueue: ((List<Track>, Int) -> Unit)? = null,
    settingsJson: String = com.example.flowave.utils.SettingsSchema.getDefaultJson(),
    modifier: Modifier = Modifier
) {
    var selectedMood by remember { mutableStateOf("All") }
    val moods = listOf("All", "Quick Picks", "Chill", "Energy", "Workout", "Focus")

    val recentlyPlayed = remember(tracks) {
        tracks.filter { it.lastPlayedTimestamp > 0 }.sortedByDescending { it.lastPlayedTimestamp }.take(10)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        // Mood / Filter Chips in Liquid Glass
        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(moods) { mood ->
                    val isSelected = selectedMood == mood
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .expressivePressScale(interactionSource)
                            .clip(SquircleShape(16.dp))
                            .background(
                                if (isSelected) Brush.horizontalGradient(listOf(CyanNeon, PurpleNeon))
                                else Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x0AFFFFFF)))
                            )
                            .clickable(interactionSource = interactionSource, indication = null) {
                                selectedMood = mood
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = mood,
                            color = if (isSelected) PureBlack else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Quick Picks / Featured Online Recommendations
        item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "START RADIO FROM A SONG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Quick Picks",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    if (onExploreOnlineClick != null) {
                        Text(
                            text = "Explore all",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyanNeon,
                            modifier = Modifier.clickable { onExploreOnlineClick() }
                        )
                    }
                }

                if (featuredOnline.isEmpty()) {
                    // Placeholder shimmer in iOS liquid glass
                    LiquidGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .height(140.dp),
                        cornerRadius = 24.dp
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = CyanNeon,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(featuredOnline) { onlineTrack ->
                            val itemInteraction = remember { MutableInteractionSource() }
                            Column(
                                modifier = Modifier
                                    .width(140.dp)
                                    .expressivePressScale(itemInteraction)
                                    .clickable(interactionSource = itemInteraction, indication = null) {
                                        onOnlineTrackClick(onlineTrack)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(140.dp)
                                        .clip(SquircleShape(20.dp))
                                        .background(DarkSurface)
                                ) {
                                    AsyncImage(
                                        model = onlineTrack.thumbnailUrl,
                                        contentDescription = onlineTrack.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xCC000000)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = CyanNeon,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = onlineTrack.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    text = onlineTrack.artist,
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Recently Played Streamed Tracks Shelf
        if (recentlyPlayed.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LISTEN AGAIN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Recently Played",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentlyPlayed.forEach { track ->
                        val trackInteraction = remember { MutableInteractionSource() }
                        LiquidGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .expressivePressScale(trackInteraction)
                                .clickable(interactionSource = trackInteraction, indication = null) {
                                    onTrackClick(track)
                                },
                            cornerRadius = 18.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(SquircleShape(12.dp))
                                        .background(Color(0x33FFFFFF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!track.artworkUri.isNullOrBlank()) {
                                        AsyncImage(
                                            model = track.artworkUri,
                                            contentDescription = track.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = CyanNeon,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = "Play",
                                    tint = CyanNeon,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
