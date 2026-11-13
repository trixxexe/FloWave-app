package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.launch

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
    onOpenLogin: (() -> Unit)? = null,
    settingsJson: String = com.example.flowave.utils.SettingsSchema.getDefaultJson(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { YtMusicAuthManager.getInstance(context) }
    val innerTubeRepo = remember { InnerTubeRepository.getInstance(context) }
    val connection by authManager.connection.collectAsStateWithLifecycle()

    var selectedMood by remember { mutableStateOf("All") }
    var moodTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoadingMood by remember { mutableStateOf(false) }
    val moods = listOf("All", "Chill", "Energy", "Workout", "Focus", "Lo-Fi", "Synthwave")

    val activeOnlineTracks = if (selectedMood == "All" || moodTracks.isEmpty()) {
        featuredOnline
    } else {
        moodTracks
    }

    val recentlyPlayed = remember(tracks) {
        tracks.filter { it.lastPlayedTimestamp > 0 }
            .sortedByDescending { it.lastPlayedTimestamp }
            .take(8)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Minimalist AMOLED Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FloWave",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = if (connection.isConnected) {
                            "Signed in • ${connection.accountName}"
                        } else {
                            "Ad-free YouTube Music & Local Audio"
                        },
                        fontSize = 12.sp,
                        color = if (connection.isConnected) CyanNeon else TextMuted
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!connection.isConnected && onOpenLogin != null) {
                        Surface(
                            shape = RoundedCornerShape(100.dp),
                            color = CyanNeon.copy(alpha = 0.14f),
                            modifier = Modifier
                                .border(1.dp, CyanNeon.copy(alpha = 0.35f), RoundedCornerShape(100.dp))
                                .clickable { onOpenLogin() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Sign In",
                                    tint = CyanNeon,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Sign In",
                                    color = CyanNeon,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDownloaderClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(DarkSurface)
                            .border(1.dp, GlassBorder, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Downloader",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onProfileClick,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(DarkSurface)
                            .border(1.dp, GlassBorder, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Settings",
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Mood Chips Row
        item {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(moods) { mood ->
                    val isSelected = selectedMood == mood
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) CyanNeon else DarkSurface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) CyanNeon else GlassBorder,
                                shape = RoundedCornerShape(100.dp)
                            )
                            .clickable {
                                selectedMood = mood
                                if (mood != "All") {
                                    isLoadingMood = true
                                    scope.launch {
                                        moodTracks = runCatching {
                                            innerTubeRepo.searchTracks("$mood music hits")
                                        }.getOrDefault(emptyList())
                                        isLoadingMood = false
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = mood,
                            color = if (isSelected) PureBlack else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        // Featured Carousel
        item {
            Column(modifier = Modifier.padding(top = 18.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedMood == "All") "Featured Picks" else "$selectedMood Mix",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    if (onExploreOnlineClick != null) {
                        Text(
                            text = "Search",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = CyanNeon,
                            modifier = Modifier.clickable { onExploreOnlineClick() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (activeOnlineTracks.isEmpty() || isLoadingMood) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = CyanNeon,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(activeOnlineTracks.take(8), key = { "card_${it.id}" }) { onlineTrack ->
                            Column(
                                modifier = Modifier
                                    .width(136.dp)
                                    .clickable { onOnlineTrackClick(onlineTrack) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(136.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(DarkSurface)
                                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
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
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(PureBlack.copy(alpha = 0.8f))
                                            .border(1.dp, GlassBorderWhite, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = CyanNeon,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = onlineTrack.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
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

        // Quick Picks Vertical List (Start Radio from a Song)
        if (activeOnlineTracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Radio",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Tap to stream",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }

            itemsIndexed(
                items = activeOnlineTracks.drop(2).take(6).ifEmpty { activeOnlineTracks.take(6) },
                key = { _, item -> "quick_${item.id}" }
            ) { _, onlineTrack ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOnlineTrackClick(onlineTrack) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = onlineTrack.thumbnailUrl,
                        contentDescription = onlineTrack.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = onlineTrack.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${onlineTrack.artist} • ${onlineTrack.durationText}",
                            fontSize = 12.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Recently Played Section
        if (recentlyPlayed.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recently Played",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (onPlayQueue != null) {
                        Text(
                            text = "Play all",
                            fontSize = 12.sp,
                            color = CyanNeon,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { onPlayQueue(recentlyPlayed, 0) }
                        )
                    }
                }
            }

            items(recentlyPlayed, key = { "recent_${it.id}" }) { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTrackClick(track) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp)),
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
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
                        Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
