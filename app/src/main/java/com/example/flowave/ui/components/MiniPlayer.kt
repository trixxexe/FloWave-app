package com.example.flowave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.flowave.ui.theme.*

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
    showProgress: Boolean = true,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack ?: return
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(AmoledElevated)
            .border(1.dp, GlassBorderWhite, shape)
            .clickable { onExpandClick() }
            .testTag("mini_player_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    if (!track.artworkUri.isNullOrEmpty()) {
                        AsyncImage(
                            model = track.artworkUri,
                            contentDescription = "Track Artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "No Artwork",
                            tint = CyanNeon,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Artist
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (playbackState.isBuffering) "Loading stream..." else track.artist,
                        color = if (playbackState.isBuffering) CyanNeon else TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite
                IconButton(
                    onClick = onFavoriteClick,
                    modifier = Modifier
                        .size(36.dp)
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
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(CyanNeon.copy(alpha = 0.14f))
                        .testTag("mini_player_play_pause_btn")
                ) {
                    if (playbackState.isBuffering && !playbackState.isPlaying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = CyanNeon,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = CyanNeon,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                // Next
                IconButton(
                    onClick = onNextClick,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("mini_player_next_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Close
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier
                        .size(30.dp)
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

            playbackState.errorMessage?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Stream error — tap Retry",
                        color = ErrorCrimson,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    TextButton(
                        onClick = onRetryClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Retry", color = CyanNeon, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (playbackState.isBuffering) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = CyanNeon,
                    trackColor = Color.Transparent
                )
            } else if (showProgress && playbackState.durationMs > 0) {
                val posMs = playbackState.currentPositionMs
                val durMs = playbackState.durationMs.coerceAtLeast(1L)
                val progress = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .testTag("mini_player_seekbar"),
                    color = CyanNeon,
                    trackColor = GlassBorder
                )
            }
        }
    }
}
