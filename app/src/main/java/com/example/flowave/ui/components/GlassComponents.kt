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
import androidx.compose.ui.graphics.Brush
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
    cornerRadius: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x22FFFFFF),
                        Color(0x08FFFFFF)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0x44FFFFFF),
                        Color(0x0BFFFFFF)
                    )
                ),
                shape = RoundedCornerShape(cornerRadius)
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

// Widget 1: Liquid Glass Stat Card Widget
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
        cornerRadius = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.uppercase(),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = subtitle,
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// Widget 2: Liquid Glass Playback Controller Widget
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
        cornerRadius = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(CyanNeon.copy(alpha = 0.3f), PinkNeon.copy(alpha = 0.2f))
                        )
                    )
                    .border(1.dp, CyanNeon.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Waveform",
                    tint = CyanNeon,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trackTitle,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
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
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(CyanNeon)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = PureBlack
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

// Widget 3: Liquid Glass User Profile Widget
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
        cornerRadius = 28.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(CyanNeon, PinkNeon)
                            )
                        )
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(DarkSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profile.username.take(1).uppercase(),
                        color = CyanNeon,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = profile.username,
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified",
                            tint = CyanNeon,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = profile.bio,
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        color = CyanNeon.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(100.dp),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, CyanNeon.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = profile.preferredQuality,
                            color = CyanNeon,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x22FFFFFF))
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profile",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// Widget 4: Liquid Glass Download Progress Widget
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
        cornerRadius = 20.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
                        DownloadStatus.DONE -> Color(0xFF4CAF50)
                        DownloadStatus.FAILED -> Color(0xFFFF5252)
                    },
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.trackTitle,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
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

                Surface(
                    color = when (entry.status) {
                        DownloadStatus.QUEUED -> Color(0x33FFFFFF)
                        DownloadStatus.DOWNLOADING -> CyanNeon.copy(alpha = 0.2f)
                        DownloadStatus.DONE -> Color(0x334CAF50)
                        DownloadStatus.FAILED -> Color(0x33FF5252)
                    },
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        text = entry.status.name,
                        color = when (entry.status) {
                            DownloadStatus.QUEUED -> TextMuted
                            DownloadStatus.DOWNLOADING -> CyanNeon
                            DownloadStatus.DONE -> Color(0xFF81C784)
                            DownloadStatus.FAILED -> Color(0xFFFF8A80)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            if (entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.QUEUED) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { entry.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = CyanNeon,
                    trackColor = Color(0x33FFFFFF)
                )
            }
        }
    }
}

// Widget 5: Liquid Glass Quality Selector Widget
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
        cornerRadius = 24.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            options.forEach { option ->
                val isSelected = selectedQuality.contains(option.split(" ").last(), ignoreCase = true) ||
                        (option == "Ultra FLAC" && selectedQuality.contains("FLAC", ignoreCase = true)) ||
                        (option == "256k MP3" && selectedQuality.contains("256", ignoreCase = true)) ||
                        (option == "128k AAC" && selectedQuality.contains("128", ignoreCase = true))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isSelected) CyanNeon else Color.Transparent
                        )
                        .clickable { onQualitySelected(option) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) PureBlack else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

// Widget 6: Liquid Glass Quick Action Widget Tile
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
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .testTag("glass_action_tile"),
        cornerRadius = 20.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = CyanNeon,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 11.sp
            )
        }
    }
}
