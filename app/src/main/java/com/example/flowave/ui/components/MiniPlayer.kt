package com.example.flowave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.audio.PlaybackState
import com.example.flowave.ui.theme.CyanNeon
import com.example.flowave.ui.theme.TextMuted
import com.example.flowave.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    playbackState: PlaybackState,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onExpandClick: () -> Unit,
    onCloseClick: () -> Unit,
    onRetryClick: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack ?: return

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clickable { onExpandClick() }
            .testTag("mini_player_card"),
        cornerRadius = 14.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork
                if (!track.artworkUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = track.artworkUri,
                        contentDescription = "Track Artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E2830)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "No Artwork",
                            tint = CyanNeon,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("mini_player_favorite_btn")
                ) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (track.isFavorite) CyanNeon else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Play / Pause
                IconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .testTag("mini_player_play_pause_btn")
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = CyanNeon,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Next
                IconButton(
                    onClick = onNextClick,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("mini_player_next_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Close / Cross Dismiss Button
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("mini_player_close_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hide MiniPlayer",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (playbackState.isBuffering) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = CyanNeon,
                    trackColor = Color.Transparent
                )
            }
            playbackState.errorMessage?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Playback unavailable", color = Color(0xFFFF9E9E), fontSize = 10.sp, maxLines = 1)
                    TextButton(onClick = onRetryClick, contentPadding = PaddingValues(0.dp)) {
                        Text("Retry", color = CyanNeon, fontSize = 11.sp)
                    }
                }
            }

            // Sleek Progress Line
            if (playbackState.durationMs > 0) {
                val posMs = playbackState.currentPositionMs
                val durMs = playbackState.durationMs.coerceAtLeast(1L)
                var sliderPos by remember { mutableFloatStateOf(0f) }
                var isUserSeeking by remember { mutableStateOf(false) }

                val currentProgress = if (isUserSeeking) sliderPos else (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

                Slider(
                    value = currentProgress,
                    onValueChange = {
                        isUserSeeking = true
                        sliderPos = it
                    },
                    onValueChangeFinished = {
                        isUserSeeking = false
                        onSeek((sliderPos * durMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = CyanNeon,
                        activeTrackColor = CyanNeon,
                        inactiveTrackColor = Color(0x22FFFFFF)
                    ),
                    thumb = {
                        if (isUserSeeking) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(CyanNeon)
                            )
                        } else {
                            Box(modifier = Modifier.size(0.dp))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .testTag("mini_player_seekbar")
                )
            }
        }
    }
}
