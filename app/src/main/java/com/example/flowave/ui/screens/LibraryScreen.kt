package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.flowave.data.model.Playlist
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.data.ytmusic.YtPlaylistSummary
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.launch

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

    // 0: Playlists, 1: Liked, 2: On Device, 3: History
    var selectedTab by remember { mutableIntStateOf(0) }
    var accountPlaylists by remember { mutableStateOf<List<YtPlaylistSummary>>(emptyList()) }
    var likedTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var isLoadingLiked by remember { mutableStateOf(false) }

    var activePlaylist by remember { mutableStateOf<YtPlaylistSummary?>(null) }
    var activePlaylistTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isLoadingActiveTracks by remember { mutableStateOf(false) }

    val deviceTracks = remember(localTracks) {
        localTracks.filter { !it.isOnline }
    }

    val historyTracks = remember(localTracks, recentlyPlayedTracks) {
        if (recentlyPlayedTracks.isNotEmpty()) {
            recentlyPlayedTracks
        } else {
            localTracks.filter { it.lastPlayedTimestamp > 0 }.sortedByDescending { it.lastPlayedTimestamp }
        }
    }

    LaunchedEffect(connection.isConnected) {
        if (connection.isConnected) {
            isLoadingPlaylists = true
            coroutineScope.launch {
                accountPlaylists = innerTubeRepo.fetchLibraryPlaylists()
                isLoadingPlaylists = false
            }
        } else {
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
        if (selectedTab == 1 && likedTracks.isEmpty() && connection.isConnected) {
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
            .background(PureBlack)
            .statusBarsPadding()
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activePlaylist != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { activePlaylist = null }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = activePlaylist?.title.orEmpty(),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (activePlaylistTracks.isNotEmpty()) {
                    Button(
                        onClick = {
                            val first = activePlaylistTracks.firstOrNull()
                            if (first != null) onOnlineTrackClick(first)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanNeon,
                            contentColor = PureBlack
                        ),
                        shape = RoundedCornerShape(100.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    text = "Library",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = (-0.5).sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!connection.isConnected) {
                        Button(
                            onClick = onOpenLogin,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyanNeon,
                                contentColor = PureBlack
                            ),
                            shape = RoundedCornerShape(100.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign In", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isLoadingPlaylists = true
                                    accountPlaylists = innerTubeRepo.fetchLibraryPlaylists()
                                    if (selectedTab == 1) {
                                        likedTracks = innerTubeRepo.fetchLikedMusic()
                                    }
                                    isLoadingPlaylists = false
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurface)
                                .border(1.dp, GlassBorder, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh Library",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        if (activePlaylist == null) {
            // Minimalist Tab Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Playlists", "Liked", "On Device", "History").forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isSelected) CyanNeon else DarkSurface)
                            .border(
                                1.dp,
                                if (isSelected) CyanNeon else GlassBorder,
                                RoundedCornerShape(100.dp)
                            )
                            .clickable { selectedTab = index }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) PureBlack else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Unauthenticated banner on Playlists/Liked tabs
            if (!connection.isConnected && (selectedTab == 0 || selectedTab == 1)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                        .clickable { onOpenLogin() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sign in to YouTube Music",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Sync your personal playlists, liked songs & cloud library",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextMuted
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    if (isLoadingPlaylists) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyanNeon, strokeWidth = 2.dp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 140.dp, top = 6.dp)
                        ) {
                            items(accountPlaylists, key = { it.id }) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            activePlaylist = playlist
                                            isLoadingActiveTracks = true
                                            coroutineScope.launch {
                                                activePlaylistTracks = innerTubeRepo.fetchPlaylistTracks(playlist.id)
                                                isLoadingActiveTracks = false
                                            }
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(DarkSurface)
                                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp)),
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
                                            Icon(
                                                Icons.Default.QueueMusic,
                                                contentDescription = null,
                                                tint = CyanNeon,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.title,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = playlist.author ?: "YouTube Music",
                                            fontSize = 12.sp,
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

                1 -> {
                    val localFavorites = remember(localTracks) { localTracks.filter { it.isFavorite } }
                    if (isLoadingLiked) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyanNeon, strokeWidth = 2.dp)
                        }
                    } else if (likedTracks.isEmpty() && localFavorites.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No liked tracks yet", color = TextMuted, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 140.dp, top = 6.dp)
                        ) {
                            items(likedTracks, key = { "yt_like_${it.id}" }) { track ->
                                OnlineLibraryRow(
                                    track = track,
                                    onClick = { onOnlineTrackClick(track) },
                                    onDownload = { onDownloadOnlineTrack(track) }
                                )
                            }
                            items(localFavorites, key = { "loc_like_${it.id}" }) { track ->
                                LocalLibraryRow(
                                    track = track,
                                    onClick = { onTrackClick(track) },
                                    onEditTag = { onEditTagClick(track) }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onScanStorageClick,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan Device", color = TextPrimary, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onImportFilesClick,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.AudioFile, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import Files", color = TextPrimary, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = onImportFolderClick,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Folder", color = TextPrimary, fontSize = 12.sp)
                            }
                        }

                        if (deviceTracks.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "No local audio files found. Tap Scan Device or Import Files.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 140.dp)
                            ) {
                                items(deviceTracks, key = { "dev_${it.id}" }) { track ->
                                    LocalLibraryRow(
                                        track = track,
                                        onClick = { onTrackClick(track) },
                                        onEditTag = { onEditTagClick(track) }
                                    )
                                }
                            }
                        }
                    }
                }

                3 -> {
                    if (historyTracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No listening history yet", color = TextMuted, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 140.dp, top = 6.dp)
                        ) {
                            items(historyTracks, key = { "hist_${it.id}" }) { track ->
                                LocalLibraryRow(
                                    track = track,
                                    onClick = { onTrackClick(track) },
                                    onEditTag = { onEditTagClick(track) }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Active Playlist Tracks View
            if (isLoadingActiveTracks) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyanNeon, strokeWidth = 2.dp)
                }
            } else if (activePlaylistTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (connection.isConnected) "No tracks found in this playlist" else "Sign in to view your personal tracks",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp, top = 6.dp)
                ) {
                    items(activePlaylistTracks, key = { "pl_item_${it.id}" }) { track ->
                        OnlineLibraryRow(
                            track = track,
                            onClick = { onOnlineTrackClick(track) },
                            onDownload = { onDownloadOnlineTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineLibraryRow(
    track: InnerTubeTrack,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
        )

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
                text = "${track.artist} • ${track.durationText}",
                fontSize = 12.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onDownload,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = "Download",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun LocalLibraryRow(
    track: Track,
    onClick: () -> Unit,
    onEditTag: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp),
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

        if (!track.isOnline) {
            IconButton(
                onClick = onEditTag,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit Tags",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = CyanNeon,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
