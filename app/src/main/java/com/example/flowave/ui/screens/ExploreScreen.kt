package com.example.flowave.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.ui.components.GlassCard
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ExploreScreen(
    innerTubeRepo: InnerTubeRepository,
    audioEngine: FloWaveAudioEngine,
    onDownloadTrack: (InnerTubeTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Trending Charts") }
    var onlineTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val categories = listOf(
        "Trending Charts",
        "Bollywood / Indian",
        "LoFi & Chill",
        "Synthwave & EDM",
        "Hip Hop Hits",
        "Acoustic & Pop"
    )

    // Initial search load
    LaunchedEffect(selectedCategory) {
        isLoading = true
        onlineTracks = innerTubeRepo.searchTracks(selectedCategory)
        isLoading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.TravelExplore,
                contentDescription = null,
                tint = CyanNeon,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "Online Music Hub",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "High-Res Zero-Server InnerTube Extraction",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Bar Input Card
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs, artists, albums...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanNeon) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("explore_search_input")
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            scope.launch {
                                isLoading = true
                                onlineTracks = innerTubeRepo.searchTracks(searchQuery.trim())
                                isLoading = false
                            }
                        } else {
                            Toast.makeText(context, "Enter search terms", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text("Search", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = {
                        selectedCategory = category
                    },
                    label = { Text(category, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanNeon,
                        selectedLabelColor = PureBlack,
                        containerColor = DarkSurface,
                        labelColor = TextPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Results Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$selectedCategory (${onlineTracks.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CyanNeon, strokeWidth = 2.dp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Online Songs List
        if (onlineTracks.isEmpty() && !isLoading) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No online tracks found. Try another search query.", color = TextMuted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(onlineTracks, key = { it.id }) { item ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    Toast.makeText(context, "Resolving stream: ${item.title}...", Toast.LENGTH_SHORT).show()
                                    val streamUrl = innerTubeRepo.getStreamUrl(item.id)
                                    val track = Track(
                                        id = item.id,
                                        title = item.title,
                                        artist = item.artist,
                                        album = item.album,
                                        durationMs = 210000L,
                                        mediaUri = streamUrl,
                                        artworkUri = item.thumbnailUrl,
                                        isOnline = true
                                    )
                                    audioEngine.playTrack(track)
                                }
                            },
                        cornerRadius = 16.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail with play overlay
                            Box(contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = item.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(DarkSurface)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = CyanNeon,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Details
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${item.artist} • ${item.durationText}",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Download Icon
                            IconButton(
                                onClick = {
                                    onDownloadTrack(item)
                                    Toast.makeText(context, "Sent to downloader: ${item.title}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(DarkSurface)
                                    .size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "Download Track",
                                    tint = CyanNeon,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
