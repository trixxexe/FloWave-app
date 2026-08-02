package com.example.flowave.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.LrcLine
import com.example.flowave.data.model.Track
import com.example.flowave.data.model.UserProfile
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.repository.FloWaveRepository
import com.example.flowave.data.repository.ProfileRepository
import com.example.flowave.downloader.FloWaveDownloader
import com.example.flowave.ui.components.DynamicIslandWidget
import com.example.flowave.ui.components.MiniPlayer
import com.example.flowave.ui.components.PermissionManager
import com.example.flowave.ui.components.PermissionsDialog
import com.example.flowave.ui.components.TagEditorDialog
import com.example.flowave.ui.screens.*
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val repository = remember { FloWaveRepository(context) }
    val profileRepo = remember { ProfileRepository(context) }
    val audioEngine = remember { FloWaveAudioEngine(context) }
    val innerTubeRepo = remember { InnerTubeRepository() }
    val downloader = remember { FloWaveDownloader(context, repository) }

    val localTracks by repository.allTracks.collectAsState(initial = emptyList())
    val totalTimeMs by repository.totalListeningTimeMs.collectAsState(initial = 0L)
    val totalPlayCount by repository.totalPlayCount.collectAsState(initial = 0)
    val downloadEntries by downloader.allDownloadEntries.collectAsState(initial = emptyList())
    val userProfile by profileRepo.userProfile.collectAsState(initial = UserProfile())

    var featuredOnlineTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var searchOnlineResults by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var currentLrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Home, 1: Explore, 2: Library, 3: Downloader, 4: EQ DSP, 5: Profile
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isMiniPlayerDismissed by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }

    val playbackState by audioEngine.playbackState.collectAsState()

    // BackHandler: Single tap as redirector to previous page, Double tap on Home to exit app
    BackHandler(enabled = true) {
        if (isPlayerExpanded) {
            isPlayerExpanded = false
        } else if (selectedTab != 0) {
            selectedTab = 0
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackPressedTime < 2000L) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressedTime = currentTime
                Toast.makeText(context, "Press back again to exit FloWave", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Fetch initial trending tracks
    LaunchedEffect(Unit) {
        try {
            featuredOnlineTracks = innerTubeRepo.getFeaturedAudioStreams()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        repository.scanMediaStore()
    }

    // Fetch lyrics & update streak when track changes
    LaunchedEffect(playbackState.currentTrack) {
        val track = playbackState.currentTrack
        if (track != null) {
            isMiniPlayerDismissed = false // Reset mini player visibility on track change
            try {
                currentLrcLines = innerTubeRepo.fetchLrcLyrics(track.title, track.artist)
            } catch (e: Exception) {
                currentLrcLines = emptyList()
            }
            repository.recordPlay(track, 30000L)
            profileRepo.recordDailyListeningStreak()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Dynamic Custom Background Support
        if (!userProfile.customBgUrl.isNullOrEmpty()) {
            AsyncImage(
                model = userProfile.customBgUrl,
                contentDescription = "Custom Background",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Dark glass overlay for contrast
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            )
        } else {
            val bgBrush = when (userProfile.backgroundPreset) {
                "AMOLED_DARK" -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
                "CYBER_CYAN" -> Brush.verticalGradient(listOf(Color(0xFF031D24), Color(0xFF070B10)))
                "NEON_PURPLE" -> Brush.verticalGradient(listOf(Color(0xFF1B0326), Color(0xFF070B10)))
                else -> Brush.verticalGradient(listOf(DarkBackground, Color(0xFF121820), DarkBackground))
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgBrush)
            )
        }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!isPlayerExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        // Floating MiniPlayer above Bottom Navigation
                        if (playbackState.currentTrack != null && !isMiniPlayerDismissed) {
                            MiniPlayer(
                                playbackState = playbackState,
                                onPlayPauseClick = { audioEngine.togglePlayPause() },
                                onNextClick = { audioEngine.playNext() },
                                onFavoriteClick = {
                                    playbackState.currentTrack?.let { track ->
                                        coroutineScope.launch {
                                            repository.toggleFavorite(track)
                                            Toast.makeText(context, "Favorites updated", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onExpandClick = { isPlayerExpanded = true },
                                onCloseClick = { isMiniPlayerDismissed = true }
                            )
                        }

                        // M3 Bottom Navigation Bar
                        NavigationBar(
                            containerColor = DarkSurface.copy(alpha = 0.95f),
                            contentColor = CyanNeon,
                            tonalElevation = 8.dp
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                label = { Text("Home", fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PureBlack,
                                    selectedTextColor = CyanNeon,
                                    indicatorColor = CyanNeon
                                ),
                                modifier = Modifier.testTag("nav_home_tab")
                            )

                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.TravelExplore, contentDescription = "Explore") },
                                label = { Text("Explore", fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PureBlack,
                                    selectedTextColor = CyanNeon,
                                    indicatorColor = CyanNeon
                                ),
                                modifier = Modifier.testTag("nav_explore_tab")
                            )

                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                                label = { Text("Library", fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PureBlack,
                                    selectedTextColor = CyanNeon,
                                    indicatorColor = CyanNeon
                                ),
                                modifier = Modifier.testTag("nav_library_tab")
                            )

                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Download, contentDescription = "Downloader") },
                                label = { Text("Downloader", fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PureBlack,
                                    selectedTextColor = CyanNeon,
                                    indicatorColor = CyanNeon
                                ),
                                modifier = Modifier.testTag("nav_downloader_tab")
                            )

                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                icon = { Icon(Icons.Default.Equalizer, contentDescription = "Equalizer") },
                                label = { Text("EQ DSP", fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PureBlack,
                                    selectedTextColor = CyanNeon,
                                    indicatorColor = CyanNeon
                                ),
                                modifier = Modifier.testTag("nav_eq_tab")
                            )

                            NavigationBarItem(
                                selected = selectedTab == 5,
                                onClick = { selectedTab = 5 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                                label = { Text("Profile", fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PureBlack,
                                    selectedTextColor = CyanNeon,
                                    indicatorColor = CyanNeon
                                ),
                                modifier = Modifier.testTag("nav_profile_tab")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        tracks = localTracks,
                        featuredOnline = featuredOnlineTracks,
                        totalListeningTimeMs = totalTimeMs ?: 0L,
                        totalPlayCount = totalPlayCount,
                        onTrackClick = { track -> audioEngine.playTrack(track) },
                        onOnlineTrackClick = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Resolving stream...", Toast.LENGTH_SHORT).show()
                                    val url = innerTubeRepo.getStreamUrl(online.id)
                                    val track = Track(
                                        id = "yt_${online.id}",
                                        title = online.title,
                                        artist = online.artist,
                                        album = online.album ?: "Online Stream",
                                        durationMs = 210000L,
                                        mediaUri = url,
                                        artworkUri = online.thumbnailUrl,
                                        isOnline = true,
                                        source = "YOUTUBE"
                                    )
                                    audioEngine.playTrack(track)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error fetching stream: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onProfileClick = { selectedTab = 5 },
                        onDownloaderClick = { selectedTab = 3 }
                    )

                    1 -> ExploreScreen(
                        innerTubeRepo = innerTubeRepo,
                        audioEngine = audioEngine,
                        onDownloadTrack = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Extracting audio: ${online.title}", Toast.LENGTH_SHORT).show()
                                    val streamUrl = innerTubeRepo.getStreamUrl(online.id)
                                    downloader.downloadAudioTrack(online, streamUrl)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    2 -> LibraryScreen(
                        localTracks = localTracks,
                        searchOnlineResults = searchOnlineResults,
                        onTrackClick = { track -> audioEngine.playTrack(track) },
                        onOnlineTrackClick = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Resolving stream...", Toast.LENGTH_SHORT).show()
                                    val url = innerTubeRepo.getStreamUrl(online.id)
                                    val track = Track(
                                        id = "yt_${online.id}",
                                        title = online.title,
                                        artist = online.artist,
                                        album = online.album ?: "Online Stream",
                                        durationMs = 210000L,
                                        mediaUri = url,
                                        artworkUri = online.thumbnailUrl,
                                        isOnline = true,
                                        source = "YOUTUBE"
                                    )
                                    audioEngine.playTrack(track)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error fetching stream: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDownloadOnlineTrack = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Starting download: ${online.title}", Toast.LENGTH_SHORT).show()
                                    val streamUrl = innerTubeRepo.getStreamUrl(online.id)
                                    downloader.downloadAudioTrack(online, streamUrl)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onScanStorageClick = {
                            coroutineScope.launch {
                                repository.scanMediaStore()
                                Toast.makeText(context, "Scanned storage for audio tracks", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onSearchQueryChange = { query ->
                            if (query.isNotEmpty()) {
                                coroutineScope.launch {
                                    try {
                                        searchOnlineResults = innerTubeRepo.searchTracks(query)
                                    } catch (e: Exception) {
                                        searchOnlineResults = emptyList()
                                    }
                                }
                            }
                        },
                        onEditTagClick = { track -> editingTrack = track },
                        onToggleFavoriteClick = { track ->
                            coroutineScope.launch {
                                repository.toggleFavorite(track)
                                Toast.makeText(context, "Favorite updated", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    3 -> DownloaderScreen(
                        downloadEntries = downloadEntries,
                        searchResults = searchOnlineResults,
                        onSearchKeyword = { query ->
                            coroutineScope.launch {
                                try {
                                    searchOnlineResults = innerTubeRepo.searchTracks(query)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Search failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDownloadTrack = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Extracting audio: ${online.title}", Toast.LENGTH_SHORT).show()
                                    val streamUrl = innerTubeRepo.getStreamUrl(online.id)
                                    downloader.downloadAudioTrack(online, streamUrl)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onStartUrlDownload = { url ->
                            coroutineScope.launch {
                                val dummyTrack = InnerTubeTrack("url_dl_${System.currentTimeMillis()}", "Extracted Stream", "Direct Seal", "3:45", "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=300")
                                downloader.downloadAudioTrack(dummyTrack, url)
                            }
                        },
                        onBackClick = { selectedTab = 0 }
                    )

                    4 -> EqualizerScreen(
                        audioEngine = audioEngine,
                        onBackClick = { selectedTab = 0 }
                    )

                    5 -> ProfileScreen(
                        userProfile = userProfile,
                        totalListeningTimeMs = totalTimeMs ?: 0L,
                        totalPlayCount = totalPlayCount,
                        onBackClick = { selectedTab = 0 },
                        onSaveProfile = { name, bio ->
                            coroutineScope.launch {
                                profileRepo.updateUsername(name)
                                profileRepo.updateBio(bio)
                            }
                        },
                        onQualitySelected = { quality ->
                            coroutineScope.launch {
                                profileRepo.updateStreamingQuality(quality)
                            }
                        },
                        onBackgroundPresetSelected = { preset ->
                            coroutineScope.launch {
                                profileRepo.updateBackgroundPreset(preset)
                            }
                        },
                        onCustomBgUrlEntered = { url ->
                            coroutineScope.launch {
                                profileRepo.updateCustomBgUrl(url)
                            }
                        },
                        onAudioSettingsChanged = { norm, gapless ->
                            coroutineScope.launch {
                                profileRepo.updateAudioSettings(norm, gapless)
                                Toast.makeText(context, "Audio engine DSP settings saved", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        // Full Screen Player Overlay Slide
        AnimatedVisibility(
            visible = isPlayerExpanded,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            PlayerScreen(
                audioEngine = audioEngine,
                lrcLines = currentLrcLines,
                onCloseClick = { isPlayerExpanded = false },
                onOpenEqualizerClick = {
                    isPlayerExpanded = false
                    selectedTab = 2
                }
            )
        }

        // Runtime Storage/Media & Notification Permissions Manager
        PermissionManager(
            onPermissionsGranted = {
                coroutineScope.launch { repository.scanMediaStore() }
            }
        )

        // Tag Editor Dialog
        editingTrack?.let { track ->
            TagEditorDialog(
                track = track,
                onDismiss = { editingTrack = null },
                onSave = { updated ->
                    coroutineScope.launch {
                        repository.updateTrack(updated)
                        Toast.makeText(context, "ID3 Tags updated for ${updated.title}", Toast.LENGTH_SHORT).show()
                    }
                    editingTrack = null
                }
            )
        }
    }
}
