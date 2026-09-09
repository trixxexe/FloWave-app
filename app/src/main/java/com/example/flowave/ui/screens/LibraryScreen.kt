package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun LibraryScreen(
    localTracks: List<Track>,
    searchOnlineResults: List<InnerTubeTrack>,
    onTrackClick: (Track) -> Unit,
    onOnlineTrackClick: (InnerTubeTrack) -> Unit,
    onDownloadOnlineTrack: (InnerTubeTrack) -> Unit,
    onScanStorageClick: () -> Unit,
    onImportFilesClick: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onEditTagClick: (Track) -> Unit,
    onToggleFavoriteClick: (Track) -> Unit,
    onPlayQueue: ((List<Track>, Int) -> Unit)? = null,
    onAddToQueueNext: ((Track) -> Unit)? = null,
    onAddToQueueLast: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0: Local Songs, 1: Folders, 2: Online Search, 3: Favorites
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var folderSortBy by remember { mutableStateOf("Title") } // "Title", "Artist"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        // Header & Scan Storage Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Music Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onImportFilesClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("import_files_btn")
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", fontSize = 12.sp)
                }
                Button(
                    onClick = onScanStorageClick,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("scan_storage_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Text Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                onSearchQueryChange(it)
            },
            placeholder = { Text("Search songs, artists, InnerTube...", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanNeon) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        onSearchQueryChange("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkSurface,
                unfocusedContainerColor = DarkSurface,
                focusedBorderColor = CyanNeon,
                unfocusedBorderColor = Color(0x33FFFFFF),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_search_input")
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Tabs Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = CyanNeon,
            divider = {}
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Local (${localTracks.size})", fontSize = 12.sp, color = if (selectedTab == 0) CyanNeon else TextMuted) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Folders", fontSize = 12.sp, color = if (selectedTab == 1) CyanNeon else TextMuted) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Online Search", fontSize = 12.sp, color = if (selectedTab == 2) CyanNeon else TextMuted) }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text("Favorites", fontSize = 12.sp, color = if (selectedTab == 3) CyanNeon else TextMuted) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Content Area
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> {
                    val filtered = if (searchQuery.isEmpty()) localTracks else localTracks.filter {
                        it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
                    }

                    if (filtered.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No local songs found.", color = TextMuted)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.id }) { track ->
                                TrackListItem(
                                    track = track,
                                    onTrackClick = { onTrackClick(track) },
                                    onEditTagClick = { onEditTagClick(track) },
                                    onFavoriteClick = { onToggleFavoriteClick(track) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // Folders Browser
                    if (selectedFolder == null) {
                        val folderGroups = localTracks.groupBy { it.folderPath ?: "Internal Storage" }
                        if (folderGroups.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No folders found. Scan device storage.", color = TextMuted)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(folderGroups.keys.toList(), key = { it }) { folderPath ->
                                    val folderTracks = folderGroups[folderPath] ?: emptyList()
                                    GlassCard(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedFolder = folderPath
                                        },
                                        cornerRadius = 16.dp
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(32.dp))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(folderPath.substringAfterLast('/'), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                                Text("${folderTracks.size} audio files • $folderPath", color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Navigating Inside Folder
                        val currentPath = selectedFolder ?: ""
                        val rawTracks = localTracks.filter { it.folderPath == currentPath }
                        val folderTracks = if (folderSortBy == "Title") {
                            rawTracks.sortedBy { it.title.lowercase() }
                        } else {
                            rawTracks.sortedBy { it.artist.lowercase() }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    IconButton(
                                        onClick = { selectedFolder = null },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyanNeon)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column {
                                        Text(
                                            text = currentPath.substringAfterLast('/'),
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${folderTracks.size} songs",
                                            color = TextMuted,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            folderSortBy = if (folderSortBy == "Title") "Artist" else "Title"
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (folderSortBy == "Title") Icons.Default.SortByAlpha else Icons.Default.Sort,
                                            contentDescription = "Sort folder songs",
                                            tint = CyanNeon,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            if (folderTracks.isNotEmpty()) {
                                                if (onPlayQueue != null) {
                                                    onPlayQueue(folderTracks, 0)
                                                } else {
                                                    onTrackClick(folderTracks.first())
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = PureBlack)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (folderTracks.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No playable songs inside this folder.", color = TextMuted)
                                }
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(folderTracks, key = { it.id }) { track ->
                                        FolderTrackListItem(
                                            track = track,
                                            onTrackClick = { onTrackClick(track) },
                                            onPlayNext = { onAddToQueueNext?.invoke(track) },
                                            onPlayLast = { onAddToQueueLast?.invoke(track) },
                                            onFavoriteClick = { onToggleFavoriteClick(track) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    if (searchOnlineResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Search YouTube Music or select trending tracks.", color = TextMuted)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(searchOnlineResults, key = { it.id }) { onlineTrack ->
                                OnlineTrackListItem(
                                    track = onlineTrack,
                                    onTrackClick = { onOnlineTrackClick(onlineTrack) },
                                    onDownloadClick = { onDownloadOnlineTrack(onlineTrack) }
                                )
                            }
                        }
                    }
                }

                3 -> {
                    val favorites = localTracks.filter { it.isFavorite }
                    if (favorites.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No favorite songs added yet.", color = TextMuted)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(favorites, key = { it.id }) { track ->
                                TrackListItem(
                                    track = track,
                                    onTrackClick = { onTrackClick(track) },
                                    onEditTagClick = { onEditTagClick(track) },
                                    onFavoriteClick = { onToggleFavoriteClick(track) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackListItem(
    track: Track,
    onTrackClick: () -> Unit,
    onEditTagClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTrackClick() },
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.artworkUri ?: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=200",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} • ${track.album}", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) CyanNeon else TextMuted
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = TextMuted)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit ID3 Metadata", color = TextPrimary) },
                        onClick = {
                            showMenu = false
                            onEditTagClick()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = CyanNeon) }
                    )
                }
            }
        }
    }
}

@Composable
fun OnlineTrackListItem(
    track: InnerTubeTrack,
    onTrackClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTrackClick() },
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            IconButton(onClick = onDownloadClick) {
                Icon(Icons.Default.Download, contentDescription = "Download Audio", tint = CyanNeon)
            }
        }
    }
}

@Composable
fun FolderTrackListItem(
    track: Track,
    onTrackClick: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLast: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTrackClick() },
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.artworkUri ?: "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=200",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) CyanNeon else TextMuted
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More Actions", tint = TextMuted)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(DarkSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Play Next", color = TextPrimary) },
                        onClick = {
                            showMenu = false
                            onPlayNext()
                        },
                        leadingIcon = { Icon(Icons.Default.QueuePlayNext, contentDescription = null, tint = CyanNeon) }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Queue Last", color = TextPrimary) },
                        onClick = {
                            showMenu = false
                            onPlayLast()
                        },
                        leadingIcon = { Icon(Icons.Default.Queue, contentDescription = null, tint = CyanNeon) }
                    )
                }
            }
        }
    }
}
