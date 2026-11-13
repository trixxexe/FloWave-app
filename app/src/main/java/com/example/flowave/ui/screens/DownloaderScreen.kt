package com.example.flowave.ui.screens

import android.widget.Toast
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
import com.example.flowave.data.model.DownloadStatus
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.ui.components.LiquidGlassDownloadProgressWidget
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun DownloaderScreen(
    downloadEntries: List<DownloadEntry>,
    searchResults: List<InnerTubeTrack>,
    isSearching: Boolean = false,
    searchError: String? = null,
    onSearchKeyword: (String) -> Unit,
    onDownloadTrack: (InnerTubeTrack) -> Unit,
    onStartUrlDownload: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var keywordQuery by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var searchMode by remember { mutableIntStateOf(0) } // 0: Keyword, 1: Direct URL

    LaunchedEffect(keywordQuery, searchMode) {
        if (searchMode == 0 && keywordQuery.trim().length >= 2) {
            delay(350)
            onSearchKeyword(keywordQuery.trim())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        // Minimalist Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                        .border(1.dp, GlassBorder, CircleShape)
                        .testTag("downloader_back_btn")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Downloads",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${downloadEntries.count { it.status == DownloadStatus.DONE }} saved offline",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            }
        }

        // Mode Switcher Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0 to "Song Search", 1 to "Direct Link").forEach { (idx, label) ->
                val isSelected = searchMode == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) CyanNeon else DarkSurface)
                        .border(1.dp, if (isSelected) CyanNeon else GlassBorder, RoundedCornerShape(12.dp))
                        .clickable { searchMode = idx }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) PureBlack else TextSecondary,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (searchMode == 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = keywordQuery,
                    onValueChange = { keywordQuery = it },
                    placeholder = { Text("Search song or artist to save offline...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (keywordQuery.isNotEmpty()) {
                            IconButton(onClick = { keywordQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
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
                        .testTag("downloader_keyword_input")
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (keywordQuery.isNotBlank()) {
                            onSearchKeyword(keywordQuery.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp)
                ) {
                    Text("Find", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://music.youtube.com/watch?v=...", color = TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
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
                        .fillMaxWidth()
                        .testTag("downloader_url_input")
                )

                Button(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onStartUrlDownload(urlInput.trim())
                            Toast.makeText(context, "Download queued", Toast.LENGTH_SHORT).show()
                            urlInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("downloader_start_btn")
                ) {
                    Text("Extract & Download Audio", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (isSearching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanNeon, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Searching...", color = TextMuted, fontSize = 12.sp)
            }
        }
        searchError?.let { message ->
            Text(message, color = ErrorCrimson, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (searchMode == 0 && searchResults.isNotEmpty()) {
                item {
                    Text(
                        text = "Search Results (${searchResults.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(searchResults, key = { "dl_res_${it.id}" }) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurface)
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PureBlack)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
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
                        IconButton(
                            onClick = {
                                onDownloadTrack(track)
                                Toast.makeText(context, "Downloading ${track.title}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(CyanNeon)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Download",
                                tint = PureBlack,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(10.dp)) }
            }

            item {
                Text(
                    text = "Download Queue & History",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (downloadEntries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No downloads yet", color = TextMuted, fontSize = 13.sp)
                    }
                }
            } else {
                items(downloadEntries, key = { "dl_entry_${it.id}" }) { entry ->
                    LiquidGlassDownloadProgressWidget(entry = entry)
                }
            }
        }
    }
}
