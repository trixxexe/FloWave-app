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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

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
    var currentActiveQuery by remember { mutableStateOf("") }
    var onlineTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val quickSearchTags = listOf(
        "Top Songs 2026",
        "Arijit Singh Hits",
        "Taylor Swift",
        "LoFi Beats",
        "Cyberpunk Synthwave",
        "Punjabi Banger Hits",
        "Chill Pop"
    )

    fun performSearch(query: String) {
        if (query.isBlank()) return
        currentActiveQuery = query.trim()
        scope.launch {
            isLoading = true
            try {
                onlineTracks = innerTubeRepo.searchTracks(currentActiveQuery)
            } catch (e: Exception) {
                Toast.makeText(context, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                onlineTracks = emptyList()
            } finally {
                isLoading = false
            }
        }
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
                    text = "Live Music Search",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Instant Zero-Server InnerTube Search Engine",
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
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search any song, artist, or album...", color = TextMuted, fontSize = 13.sp) },
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("explore_search_input")
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { performSearch(searchQuery) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Search", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Search Filter Chips Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickSearchTags) { tag ->
                FilterChip(
                    selected = currentActiveQuery.equals(tag, ignoreCase = true),
                    onClick = {
                        searchQuery = tag
                        performSearch(tag)
                    },
                    label = { Text(tag, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
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

        // Search Status Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentActiveQuery.isNotEmpty()) {
                Text(
                    text = "Results for \"$currentActiveQuery\" (${onlineTracks.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            } else {
                Text(
                    text = "Type a song name above or pick a tag",
                    fontSize = 14.sp,
                    color = TextMuted
                )
            }

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CyanNeon, strokeWidth = 2.dp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Online Search Results List
        if (onlineTracks.isEmpty() && !isLoading) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (currentActiveQuery.isEmpty()) "Search millions of online tracks instantly" else "No online tracks found for \"$currentActiveQuery\"",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
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
                                    try {
                                        Toast.makeText(context, "Resolving stream for ${item.title}...", Toast.LENGTH_SHORT).show()
                                        val streamUrl = withTimeoutOrNull(10000L) {
                                            innerTubeRepo.getStreamUrl(item.id)
                                        } ?: throw IOException("Timeout resolving stream")
                                        if (streamUrl.isBlank() || !streamUrl.startsWith("http")) {
                                            Toast.makeText(context, "Invalid stream URL received", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }
                                        val track = Track(
                                            id = "yt_${item.id}",
                                            title = item.title,
                                            artist = item.artist,
                                            album = item.album ?: "YouTube Music",
                                            durationMs = 210000L,
                                            mediaUri = streamUrl,
                                            artworkUri = item.thumbnailUrl,
                                            isOnline = true,
                                            source = "YOUTUBE"
                                        )
                                        audioEngine.playTrack(track)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
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
                            Box(contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = item.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(54.dp)
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

                            IconButton(
                                onClick = {
                                    onDownloadTrack(item)
                                    Toast.makeText(context, "Extracting audio: ${item.title}", Toast.LENGTH_SHORT).show()
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
