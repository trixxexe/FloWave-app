package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.flowave.ui.components.GlassCard
import com.example.flowave.ui.theme.*

@Composable
fun HomeScreen(
    tracks: List<Track>,
    featuredOnline: List<InnerTubeTrack>,
    totalListeningTimeMs: Long,
    totalPlayCount: Int,
    onTrackClick: (Track) -> Unit,
    onOnlineTrackClick: (InnerTubeTrack) -> Unit,
    onProfileClick: () -> Unit,
    onDownloaderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("FloWave", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text("Ultra-Fidelity Zero-Server Audio", fontSize = 12.sp, color = CyanNeon)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDownloaderClick, modifier = Modifier.testTag("home_downloader_btn")) {
                    Icon(Icons.Default.Download, contentDescription = "Downloader", tint = CyanNeon)
                }

                IconButton(onClick = onProfileClick, modifier = Modifier.testTag("home_profile_btn")) {
                    Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = TextPrimary, modifier = Modifier.size(32.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Hero Banner Card
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            cornerRadius = 24.dp
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=800",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    PureBlack.copy(alpha = 0.85f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Hi-Res Cyber Stream", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Instant YouTube InnerTube scraper & FLAC passthrough", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            featuredOnline.firstOrNull()?.let { onOnlineTrackClick(it) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                        shape = CircleShape,
                        modifier = Modifier.testTag("home_quick_play_btn")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Quick Stream", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Quick Mood Filters Row
        var selectedMood by remember { mutableStateOf("All") }
        val moods = listOf("All", "Energy ⚡", "Chill ☕", "Focus 🎯", "Sleep 🌙", "Gym 🏋️")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(moods) { mood ->
                FilterChip(
                    selected = selectedMood == mood,
                    onClick = { selectedMood = mood },
                    label = { Text(mood, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanNeon,
                        selectedLabelColor = PureBlack
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Listening Stats Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(totalListeningTimeMs / (1000 * 60))} mins", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyanNeon)
                    Text("Total Time", fontSize = 11.sp, color = TextMuted)
                }

                Divider(modifier = Modifier.height(30.dp).width(1.dp), color = Color(0x22FFFFFF))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$totalPlayCount", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PurpleNeon)
                    Text("Songs Played", fontSize = 11.sp, color = TextMuted)
                }

                Divider(modifier = Modifier.height(30.dp).width(1.dp), color = Color(0x22FFFFFF))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("3 Days 🔥", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PinkNeon)
                    Text("Streak", fontSize = 11.sp, color = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // InnerTube Trending YT Streams
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trending YT Music Streams", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("InnerTube", fontSize = 12.sp, color = CyanNeon)
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(featuredOnline) { track ->
                GlassCard(
                    modifier = Modifier
                        .width(140.dp)
                        .clickable { onOnlineTrackClick(track) },
                    cornerRadius = 18.dp
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(14.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = track.title,
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
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
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Local Library Quick Access
        Text("Local Audio Tracks", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Spacer(modifier = Modifier.height(12.dp))

        if (tracks.isEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No local music scanned yet. Go to Library to scan storage.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tracks.take(5).forEach { track ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(track) },
                        cornerRadius = 16.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = track.artworkUri ?: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=200",
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = CyanNeon)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
