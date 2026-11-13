package com.example.flowave.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.remote.InnerTubeRepository
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
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var currentActiveQuery by remember { mutableStateOf("") }
    var onlineTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val quickSearchTags = listOf(
        "Top Global Hits",
        "The Weeknd",
        "Arijit Singh",
        "Taylor Swift",
        "Lo-Fi Beats",
        "Synthwave",
        "Hip Hop",
        "Acoustic Chill"
    )

    fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        currentActiveQuery = trimmed
        focusManager.clearFocus()
        scope.launch {
            isLoading = true
            try {
                onlineTracks = innerTubeRepo.searchTracks(trimmed)
            } catch (e: Exception) {
                Toast.makeText(context, "Search failed: ${e.message}", Toast.LENGTH_SHORT).show()
                onlineTracks = emptyList()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (onlineTracks.isEmpty()) {
            isLoading = true
            try {
                onlineTracks = innerTubeRepo.getTrendingTracks()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .statusBarsPadding()
    ) {
        // Minimalist Header
        Text(
            text = "Explore",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
        )

        // Minimalist Search Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Songs, artists, or albums...",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(searchQuery) }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedBorderColor = CyanNeon,
                    unfocusedBorderColor = GlassBorder,
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanNeon,
                    contentColor = PureBlack
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp)
            ) {
                Text("Go", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quick Search Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickSearchTags) { tag ->
                val isSelected = currentActiveQuery.equals(tag, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (isSelected) CyanNeon else DarkSurface)
                        .border(
                            1.dp,
                            if (isSelected) CyanNeon else GlassBorder,
                            RoundedCornerShape(100.dp)
                        )
                        .clickable {
                            searchQuery = tag
                            performSearch(tag)
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = tag,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) PureBlack else TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Section Header + Play All
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (currentActiveQuery.isNotEmpty()) {
                    "Results for \"$currentActiveQuery\""
                } else {
                    "Trending on YouTube Music"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = CyanNeon,
                    strokeWidth = 2.dp
                )
            } else if (onlineTracks.isNotEmpty()) {
                Text(
                    text = "Play all",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CyanNeon,
                    modifier = Modifier.clickable {
                        scope.launch {
                            audioEngine.playOnlineQueue(onlineTracks, 0)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (onlineTracks.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (currentActiveQuery.isEmpty()) {
                            "Search YouTube Music catalog"
                        } else {
                            "No results found for \"$currentActiveQuery\""
                        },
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(onlineTracks, key = { index, item -> "${item.id}_$index" }) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    try {
                                        audioEngine.playOnlineQueue(onlineTracks, index)
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Playback error: ${e.localizedMessage ?: e.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurface)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
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

                        IconButton(
                            onClick = {
                                onDownloadTrack(item)
                                Toast.makeText(
                                    context,
                                    "Downloading ${item.title}...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Download Track",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
