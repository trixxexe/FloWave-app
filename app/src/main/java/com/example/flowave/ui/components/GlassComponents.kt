package com.example.flowave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.DownloadStatus
import com.example.flowave.data.model.UserProfile
import com.example.flowave.ui.theme.*

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(DarkSurface)
            .border(
                width = 1.dp,
                color = GlassBorder,
                shape = shape
            ),
        content = content
    )
}

@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(AmoledElevated)
            .border(
                width = 1.dp,
                color = GlassBorderWhite,
                shape = shape
            ),
        content = content
    )
}

@Composable
fun LiquidGlassPill(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 100.dp,
        content = content
    )
}

@Composable
fun LiquidGlassStatWidget(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accentColor: Color = CyanNeon
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("glass_stat_widget"),
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.uppercase(),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun LiquidGlassPlaybackWidget(
    trackTitle: String,
    artistName: String,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("glass_playback_widget"),
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CyanNeon.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Waveform",
                    tint = CyanNeon,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trackTitle,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artistName,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(CyanNeon)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = PureBlack,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onNextClick) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Skip Next",
                    tint = TextPrimary
                )
            }
        }
    }
}

@Composable
fun LiquidGlassProfileWidget(
    profile: UserProfile,
    onEditClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("glass_profile_widget"),
        cornerRadius = 18.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AmoledElevated)
                    .border(1.dp, CyanNeon.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.username.take(1).uppercase(),
                    color = CyanNeon,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.username,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = profile.bio,
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onEditClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AmoledElevated)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit Profile",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun LiquidGlassDownloadProgressWidget(
    entry: DownloadEntry,
    onCancelOrRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("glass_download_widget"),
        cornerRadius = 14.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = when (entry.status) {
                        DownloadStatus.QUEUED -> Icons.Default.Schedule
                        DownloadStatus.DOWNLOADING -> Icons.Default.Download
                        DownloadStatus.DONE -> Icons.Default.CheckCircle
                        DownloadStatus.FAILED -> Icons.Default.Error
                    },
                    contentDescription = entry.status.name,
                    tint = when (entry.status) {
                        DownloadStatus.QUEUED -> TextMuted
                        DownloadStatus.DOWNLOADING -> CyanNeon
                        DownloadStatus.DONE -> Color(0xFF4ADE80)
                        DownloadStatus.FAILED -> ErrorCrimson
                    },
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.trackTitle,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.artistName,
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                Text(
                    text = entry.status.name,
                    color = when (entry.status) {
                        DownloadStatus.QUEUED -> TextMuted
                        DownloadStatus.DOWNLOADING -> CyanNeon
                        DownloadStatus.DONE -> Color(0xFF4ADE80)
                        DownloadStatus.FAILED -> ErrorCrimson
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.QUEUED) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { entry.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CyanNeon,
                    trackColor = GlassBorder
                )
            }
        }
    }
}

@Composable
fun LiquidGlassQualitySelectorWidget(
    selectedQuality: String,
    onQualitySelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf("128k AAC", "256k MP3", "Ultra FLAC")

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("glass_quality_selector"),
        cornerRadius = 14.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = selectedQuality.contains(option.split(" ").last(), ignoreCase = true) ||
                    (option == "Ultra FLAC" && selectedQuality.contains("FLAC", ignoreCase = true)) ||
                    (option == "256k MP3" && selectedQuality.contains("256", ignoreCase = true)) ||
                    (option == "128k AAC" && selectedQuality.contains("128", ignoreCase = true))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) CyanNeon else Color.Transparent)
                        .clickable { onQualitySelected(option) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) PureBlack else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
fun LiquidGlassActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("glass_action_tile"),
        cornerRadius = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = CyanNeon,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }
}
