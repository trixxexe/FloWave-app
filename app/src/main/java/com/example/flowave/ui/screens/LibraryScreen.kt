package com.example.flowave.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Playlist
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.data.ytmusic.YtPlaylistSummary
import com.example.flowave.ui.components.LiquidGlassCard
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Online YouTube Music Library Screen. Displays personal account playlists,
 * Liked Music (VLLM), and favorites in continuous G2 Squircle Liquid Glass.
 */
@Composable
fun LibraryScreen(
    localTracks: List<Track>,
    searchOnlineResults: List<InnerTubeTrack>,
    onTrackClick: (Track) -> Unit,
    onOnlineTrackClick: (InnerTubeTrack) -> Unit,
    onDownloadOnlineTrack: (InnerTubeTrack) -> Unit = {},
    onScanStorageClick: () -> Unit = {},
    onImportFilesClick: () -> Unit = {},
    onImportFolderClick: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onEditTagClick: (Track) -> Unit = {},
    onToggleFavoriteClick: (Track) -> Unit = {},
    recentlyPlayedTracks: List<Track> = emptyList(),
    playlists: List<Playlist> = emptyList(),
    onCreatePlaylist: (String) -> Unit = {},
    onPlayPlaylist: (Playlist) -> Unit = {},
    onPlayQueue: ((List<Track>, Int) -> Unit)? = null,
    onAddToQueueNext: ((Track) -> Unit)? = null,
    onAddToQueueLast: ((Track) -> Unit)? = null,
    onOpenLogin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { YtMusicAuthManager.getInstance(context) }
    val innerTubeRepo = remember { InnerTubeRepository.getInstance(context) }
    val connection by authManager.connection.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0: Playlists, 1: Liked Songs, 2: History
    var accountPlaylists by remember { mutableStateOf<List<YtPlaylistSummary>>(emptyList()) }
    var likedTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var isLoadingLiked by remember { mutableStateOf(false) }

    // Selected playlist view (if user taps into a playlist)
    var activePlaylist by remember { mutableStateOf<YtPlaylistSummary?>(null) }
    var activePlaylistTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoadingActiveTracks by remember { mutableStateOf(false) }

    LaunchedEffect(connection.isConnected) {
        if (connection.isConnected) {
            isLoadingPlaylists = true
            coroutineScope.launch {
                accountPlaylists = innerTubeRepo.fetchLibraryPlaylists()
                isLoadingPlaylists = false
            }
        } else {
            // Unauthenticated baseline: always show Liked Music entry
            accountPlaylists = listOf(
                YtPlaylistSummary(
                    id = "VLLM",
                    title = "Liked Music",
                    author = "YouTube Music",
                    trackCountText = null,
                    artworkUrl = "https://music.youtube.com/img/on_platform_logo.png"
                )
            )
        }
    }

    LaunchedEffect(selectedTab, connection.isConnected) {
        if (selectedTab == 1 && likedTracks.isEmpty()) {
            isLoadingLiked = true
            coroutineScope.launch {
                likedTracks = innerTubeRepo.fetchLikedMusic()
                isLoadingLiked = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activePlaylist != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { activePlaylist = null }
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = activePlaylist?.title.orEmpty(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = "Library",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                if (!connection.isConnected) {
                    val loginInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .expressivePressScale(loginInteraction)
                            .clip(SquircleShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(CyanNeon, PurpleNeon)))
                            .clickable(interactionSource = loginInteraction, indication = null) {
                                onOpenLogin()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Login, contentDescription = null, tint = PureBlack, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sign In", color = PureBlack, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (activePlaylist == null) {
            // Sign-In Invitation Banner if not signed in
            if (!connection.isConnected) {
                LiquidGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    cornerRadius = 20.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(CyanNeon.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(24.dp))
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text("Connect YouTube Music", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Sync your playlists, likes & history", color = TextMuted, fontSize = 12.sp)
                        }

                        Button(
                            onClick = onOpenLogin,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                            shape = SquircleShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Tabs Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Playlists", "Liked Songs", "History").forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    val tabInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .expressivePressScale(tabInteraction)
                            .clip(SquircleShape(16.dp))
                            .background(
                                if (isSelected) CyanNeon else Color(0x22FFFFFF)
                            )
                            .clickable(interactionSource = tabInteraction, indication = null) {
                                selectedTab = index
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) PureBlack else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> { // Playlists Tab
                    if (isLoadingPlaylists) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyanNeon)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(accountPlaylists) { playlist ->
                                val itemInteraction = remember { MutableInteractionSource() }
                                LiquidGlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .expressivePressScale(itemInteraction)
                                        .clickable(interactionSource = itemInteraction, indication = null) {
                                            activePlaylist = playlist
                                            isLoadingActiveTracks = true
                                            coroutineScope.launch {
                                                activePlaylistTracks = innerTubeRepo.fetchPlaylistTracks(playlist.id)
                                                isLoadingActiveTracks = false
                                            }
                                        },
                                    cornerRadius = 20.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(SquircleShape(14.dp))
                                                .background(DarkSurface),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!playlist.artworkUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = playlist.artworkUrl,
                                                    contentDescription = playlist.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(Icons.Default.QueueMusic, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(28.dp))
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.title,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = playlist.author ?: "YouTube Music",
                                                fontSize = 13.sp,
                                                color = TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> { // Liked Songs Tab
                    if (isLoadingLiked) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyanNeon)
                        }
                    } else if (likedTracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No liked tracks found", color = TextMuted, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(likedTracks) { track ->
                                TrackRow(track = track, onClick = { onOnlineTrackClick(track) })
                            }
                        }
                    }
                }

                2 -> { // History Tab
                    val history = remember(localTracks) {
                        localTracks.filter { it.lastPlayedTimestamp > 0 }.sortedByDescending { it.lastPlayedTimestamp }
                    }
                    if (history.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No stream history yet", color = TextMuted, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(history) { track ->
                                val trackInteraction = remember { MutableInteractionSource() }
                                LiquidGlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .expressivePressScale(trackInteraction)
                                        .clickable(interactionSource = trackInteraction, indication = null) {
                                            onTrackClick(track)
                                        },
                                    cornerRadius = 16.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(SquircleShape(12.dp))
                                                .background(DarkSurface),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!track.artworkUri.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = track.artworkUri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(Icons.Default.MusicNote, contentDescription = null, tint = CyanNeon)
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

                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = CyanNeon)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Selected Playlist Detail View
            if (isLoadingActiveTracks) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanNeon)
                }
            } else if (activePlaylistTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tracks found in playlist", color = TextMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activePlaylistTracks) { track ->
                        TrackRow(track = track, onClick = { onOnlineTrackClick(track) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: InnerTubeTrack,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .expressivePressScale(interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(SquircleShape(12.dp))
                    .background(DarkSurface)
            ) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${track.artist} • ${track.durationText}",
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
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
