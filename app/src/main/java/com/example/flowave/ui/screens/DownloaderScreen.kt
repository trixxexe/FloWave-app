package com.example.flowave.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.data.model.DownloadEntry
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.ui.components.GlassCard
import com.example.flowave.ui.components.LiquidGlassDownloadProgressWidget
import com.example.flowave.ui.components.LiquidGlassStatWidget
import com.example.flowave.ui.theme.*

@Composable
fun DownloaderScreen(
    downloadEntries: List<DownloadEntry>,
    searchResults: List<InnerTubeTrack>,
    onSearchKeyword: (String) -> Unit,
    onDownloadTrack: (InnerTubeTrack) -> Unit,
    onStartUrlDownload: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var keywordQuery by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var searchMode by remember { mutableIntStateOf(0) } // 0: Keyword Search, 1: Direct Link

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        // Top Bar Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.testTag("downloader_back_btn")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.Download, contentDescription = null, tint = CyanNeon)
            Spacer(modifier = Modifier.width(8.dp))
            Text("FloWave High-Res Audio Downloader", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stat Card Summary
        LiquidGlassStatWidget(
            title = "Downloaded Tracks",
            value = "${downloadEntries.count { it.status == com.example.flowave.data.model.DownloadStatus.DONE }} Saved",
            subtitle = "Offline Room Database & Local Music Index",
            icon = Icons.Default.FolderZip,
            accentColor = CyanNeon
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Search Mode Selector Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = searchMode == 0,
                onClick = { searchMode = 0 },
                label = { Text("Keyword Song Search", fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanNeon,
                    selectedLabelColor = PureBlack
                ),
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = searchMode == 1,
                onClick = { searchMode = 1 },
                label = { Text("Direct Link Extractor", fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanNeon,
                    selectedLabelColor = PureBlack
                ),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (searchMode == 0) {
            // Keyword Song Search Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Search Songs to Download", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Type any song title or artist (e.g., 'Arz Kiya Hai', 'Coldplay')", color = TextMuted, fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = keywordQuery,
                            onValueChange = {
                                keywordQuery = it
                                if (it.length >= 2) {
                                    onSearchKeyword(it)
                                }
                            },
                            placeholder = { Text("e.g. Arz Kiya Hai, Arijit Singh", color = TextMuted) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanNeon) },
                            trailingIcon = {
                                if (keywordQuery.isNotEmpty()) {
                                    IconButton(onClick = { keywordQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("downloader_keyword_input")
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (keywordQuery.isNotBlank()) {
                                    onSearchKeyword(keywordQuery.trim())
                                    Toast.makeText(context, "Searching songs...", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Enter a song title or artist name", Toast.LENGTH_SHORT).show()
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
            }
        } else {
            // Direct Link Extractor Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Direct URL Audio Extractor", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Paste any YouTube or stream link to extract high-res audio", color = TextMuted, fontSize = 12.sp)

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://music.youtube.com/watch?v=...", color = TextMuted) },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = CyanNeon) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("downloader_url_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (urlInput.isNotBlank()) {
                                onStartUrlDownload(urlInput.trim())
                                Toast.makeText(context, "Extracting audio stream...", Toast.LENGTH_SHORT).show()
                                urlInput = ""
                            } else {
                                Toast.makeText(context, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("downloader_start_btn")
                    ) {
                        Text("Extract & Download Audio", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Results Section (if search mode == 0 and search results exist)
        if (searchMode == 0 && searchResults.isNotEmpty()) {
            Text("Search Results (${searchResults.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchResults, key = { it.id }) { track ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = track.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DarkSurface)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = track.title,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${track.artist} • ${track.durationText}",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    onDownloadTrack(track)
                                    Toast.makeText(context, "Downloading: ${track.title}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CyanNeon)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = PureBlack)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Active & Past Downloads Section
        Text("Downloads & Room Database Index", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        Spacer(modifier = Modifier.height(8.dp))

        if (downloadEntries.isEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No active or completed downloads in Room database.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloadEntries, key = { it.id }) { entry ->
                    LiquidGlassDownloadProgressWidget(entry = entry)
                }
            }
        }
    }
}
