package com.example.flowave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.flowave.ui.theme.CyanNeon
import com.example.flowave.ui.theme.DarkBackground
import com.example.flowave.ui.theme.DarkSurface
import com.example.flowave.ui.theme.PinkNeon
import com.example.flowave.ui.theme.PurpleNeon
import com.example.flowave.ui.theme.PureBlack
import com.example.flowave.ui.theme.TextMuted
import com.example.flowave.ui.theme.TextPrimary

/**
 * Local-first home screen. Device audio is rendered without network access;
 * online recommendations are an optional section below it.
 */
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
    onScanClick: () -> Unit,
    onPlayQueue: ((List<Track>, Int) -> Unit)? = null,
    settingsJson: String = com.example.flowave.utils.SettingsSchema.getDefaultJson(),
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var sortBy by remember { mutableStateOf("Title") }

    val density = remember(settingsJson) {
        when (com.example.flowave.utils.SettingsSchema.getValue(settingsJson, "list_density")) {
            "Compact" -> 6.dp
            "Spacious" -> 16.dp
            else -> 10.dp
        }
    }
    val folders = remember(tracks) {
        tracks.map { it.folderPath ?: "Internal Storage" }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
    val folderTracks = remember(tracks, selectedFolder, searchQuery, sortBy) {
        val scoped = tracks.filter { selectedFolder == null || (it.folderPath ?: "Internal Storage") == selectedFolder }
        val searched = if (searchQuery.isBlank()) scoped else scoped.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
        }
        when (sortBy) {
            "Artist" -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            "Recent" -> searched.sortedByDescending { it.lastPlayedTimestamp }
            "Plays" -> searched.sortedByDescending { it.playCount }
            else -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }
    }
    val onlineAvailable = featuredOnline.isNotEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("FloWave", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                    Text("Your music, online or offline", fontSize = 12.sp, color = CyanNeon)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onDownloaderClick, modifier = Modifier.testTag("home_downloader_btn")) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads", tint = CyanNeon)
                    }
                    IconButton(onClick = onProfileClick, modifier = Modifier.testTag("home_profile_btn")) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = TextPrimary, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Device library", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onScanClick,
                            colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rescan", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().testTag("home_local_search"),
                        singleLine = true,
                        placeholder = { Text("Search songs, artists, albums, folders", color = TextMuted, fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanNeon) },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) { Text("×", color = TextMuted, fontSize = 20.sp) }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            focusedBorderColor = CyanNeon,
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Title", "Artist", "Recent", "Plays").forEach { option ->
                    FilterChip(
                        selected = sortBy == option,
                        onClick = { sortBy = option },
                        label = { Text(option, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyanNeon,
                            selectedLabelColor = PureBlack
                        )
                    )
                }
            }
        }

        if (selectedFolder == null && folders.size > 1) {
            item {
                Text("Folders", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders, key = { it }) { folder ->
                        GlassCard(
                            modifier = Modifier.width(190.dp).clickable { selectedFolder = folder },
                            cornerRadius = 14.dp
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(25.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(folderName(folder), color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${tracks.count { (it.folderPath ?: "Internal Storage") == folder }} songs", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedFolder != null) {
                        IconButton(onClick = { selectedFolder = null }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "All folders", tint = CyanNeon)
                        }
                    }
                    Text(
                        selectedFolder?.let(::folderName) ?: "Offline songs",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (folderTracks.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (onPlayQueue != null) onPlayQueue(folderTracks, 0) else onTrackClick(folderTracks.first())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play all", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (folderTracks.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = TextMuted, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (tracks.isEmpty()) "No device songs indexed yet. Grant audio access and tap Rescan." else "No local songs match this search.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            items(folderTracks, key = { it.id }) { track ->
                LocalTrackRow(track = track, verticalPadding = density, onClick = { onTrackClick(track) })
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    HomeStat("${totalListeningTimeMs / 60_000}m", "Listening", CyanNeon)
                    HomeStat(totalPlayCount.toString(), "Plays", PurpleNeon)
                    HomeStat(if (tracks.isEmpty()) "Offline" else "Ready", "Library", PinkNeon)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (onlineAvailable) Icons.Default.Wifi else Icons.Default.CloudOff, contentDescription = null, tint = if (onlineAvailable) CyanNeon else TextMuted)
                Spacer(Modifier.width(8.dp))
                Text(if (onlineAvailable) "Online discovery" else "Offline mode", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (!onlineAvailable) {
            item {
                Text("Your local library stays available without a connection. Open Explore when online to search and stream.", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            items(featuredOnline, key = { it.id }) { online ->
                OnlineHomeRow(online, onClick = { onOnlineTrackClick(online) })
            }
        }
    }
}

@Composable
private fun LocalTrackRow(track: Track, verticalPadding: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = verticalPadding, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = track.artworkUri,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (track.source == "DOWNLOADED") "Downloaded • ${track.album}" else track.album, color = CyanNeon, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${track.title}", tint = CyanNeon)
        }
    }
}

@Composable
private fun OnlineHomeRow(track: InnerTubeTrack, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), cornerRadius = 14.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = track.thumbnailUrl, contentDescription = track.title, contentScale = ContentScale.Crop, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist} • ${track.durationText}", color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.PlayArrow, contentDescription = "Stream ${track.title}", tint = CyanNeon)
        }
    }
}

@Composable
private fun HomeStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

private fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { "Internal Storage" }
