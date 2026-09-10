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
import com.example.flowave.downloader.FloWaveDownloadService
import com.example.flowave.ui.components.DynamicIslandWidget
import com.example.flowave.ui.components.MiniPlayer
import com.example.flowave.ui.components.PermissionManager
import com.example.flowave.ui.components.TagEditorDialog
import com.example.flowave.ui.screens.*
import com.example.flowave.ui.theme.*
import com.example.flowave.utils.SettingsSchema
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private suspend fun playOnlineTrack(
    online: InnerTubeTrack,
    context: Context,
    audioEngine: FloWaveAudioEngine,
) {
    try {
        Toast.makeText(context, "Resolving stream...", Toast.LENGTH_SHORT).show()
        audioEngine.playOnlineTrack(online)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to resolve stream: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val repository = remember { FloWaveRepository(context) }
    val profileRepo = remember { ProfileRepository(context) }
    val audioEngine = remember { FloWaveAudioEngine.getInstance(context) }
    val innerTubeRepo = remember { InnerTubeRepository.getInstance(context) }
    val downloader = remember { FloWaveDownloader(context, repository) }

    val localTracks by repository.allTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val offlineTracks = remember(localTracks) { localTracks.filterNot { it.isOnline } }
    val recentlyPlayedTracks by repository.recentlyPlayedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by repository.allPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val totalTimeMs by repository.totalListeningTimeMs.collectAsStateWithLifecycle(initialValue = 0L)
    val totalPlayCount by repository.totalPlayCount.collectAsStateWithLifecycle(initialValue = 0)
    val downloadEntries by downloader.allDownloadEntries.collectAsStateWithLifecycle(initialValue = emptyList())
    val userProfile by profileRepo.userProfile.collectAsStateWithLifecycle(initialValue = UserProfile())
    val settingsJson by profileRepo.settingsJson.collectAsStateWithLifecycle(initialValue = SettingsSchema.getDefaultJson())

    var featuredOnlineTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var searchOnlineResults by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var downloaderSearchResults by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isDownloaderSearching by remember { mutableStateOf(false) }
    var currentLrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }

    var showCrashReportDialog by remember { mutableStateOf(false) }
    var crashReportContent by remember { mutableStateOf("") }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Home, 1: Explore, 2: Library, 3: Downloader, 4: EQ DSP, 5: Profile
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isMiniPlayerDismissed by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }
    var activeTrackForStats by remember { mutableStateOf<Track?>(null) }
    var lastPositionMs by remember { mutableLongStateOf(0L) }
    var accumulatedTimeMs by remember { mutableLongStateOf(0L) }

    val playbackState by audioEngine.playbackState.collectAsStateWithLifecycle()

    val importAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val imported = repository.importAudioUris(uris)
                Toast.makeText(context, "Imported ${imported.size} audio file${if (imported.size == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val imported = repository.importAudioTree(uri)
                Toast.makeText(context, "Imported ${imported.size} audio file${if (imported.size == 1) "" else "s"} from folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        // Index local media immediately. Home must remain useful when the
        // device is offline or the online resolver is unavailable.
        launch {
            runCatching {
                repository.removeUnavailableImportedTracks()
                repository.scanMediaStore()
            }
                .onFailure { android.util.Log.w("MainScreen", "Local media scan failed", it) }
        }
        launch {
            runCatching {
                withTimeoutOrNull(10_000L) {
                    innerTubeRepo.ensureKeysUpdated()
                    innerTubeRepo.getFeaturedAudioStreams()
                } ?: emptyList()
            }.onSuccess { featuredOnlineTracks = it }
                .onFailure { android.util.Log.d("MainScreen", "Online discovery unavailable", it) }
        }

        // Check for previous crashes
        if (com.example.flowave.utils.FloWaveCrashHandler.hasCrashReport(context)) {
            val report = com.example.flowave.utils.FloWaveCrashHandler.getCrashReport(context)
            if (!report.isNullOrBlank()) {
                crashReportContent = report
                showCrashReportDialog = true
            }
        }
    }

    val currentTrack = playbackState.currentTrack
    val currentPosition = playbackState.currentPositionMs
    val isPlaying = playbackState.isPlaying

    // Track active listening duration
    LaunchedEffect(currentTrack) {
        val prevTrack = activeTrackForStats
        if (prevTrack != null && accumulatedTimeMs > 0L) {
            val statsToRecord = accumulatedTimeMs
            coroutineScope.launch {
                repository.recordPlay(prevTrack, statsToRecord)
            }
        }
        activeTrackForStats = currentTrack
        accumulatedTimeMs = 0L
        lastPositionMs = currentPosition
        
        if (currentTrack != null) {
            isMiniPlayerDismissed = false // Reset mini player visibility on track change
            try {
                currentLrcLines = innerTubeRepo.fetchLrcLyrics(currentTrack.title, currentTrack.artist)
            } catch (e: Exception) {
                currentLrcLines = emptyList()
            }
            profileRepo.recordDailyListeningStreak()
        }
    }

    LaunchedEffect(currentPosition, isPlaying) {
        val track = activeTrackForStats
        if (track != null && isPlaying) {
            val delta = currentPosition - lastPositionMs
            if (delta in 1..5000) {
                accumulatedTimeMs += delta
            }
        }
        lastPositionMs = currentPosition
    }

    DisposableEffect(Unit) {
        onDispose {
            val finalTrack = activeTrackForStats
            val finalTime = accumulatedTimeMs
            if (finalTrack != null && finalTime > 0L) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    repository.recordPlay(finalTrack, finalTime)
                }
            }
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
                                    audioEngine.toggleCurrentFavorite()
                                    Toast.makeText(context, "Favorites updated", Toast.LENGTH_SHORT).show()
                                },
                                onExpandClick = { isPlayerExpanded = true },
                                onCloseClick = { isMiniPlayerDismissed = true },
                                onRetryClick = { audioEngine.retryCurrentTrack() },
                                onSeek = { pos -> audioEngine.seekTo(pos) }
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
                        tracks = offlineTracks,
                        featuredOnline = featuredOnlineTracks,
                        totalListeningTimeMs = totalTimeMs ?: 0L,
                        totalPlayCount = totalPlayCount,
                        onTrackClick = { track -> audioEngine.playTrack(track) },
                        onOnlineTrackClick = { online ->
                            coroutineScope.launch {
                                playOnlineTrack(online, context, audioEngine)
                            }
                        },
                        onProfileClick = { selectedTab = 5 },
                        onDownloaderClick = { selectedTab = 3 },
                        onScanClick = {
                            coroutineScope.launch {
                                repository.scanMediaStore()
                                Toast.makeText(context, "Device library refreshed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPlayQueue = { queue, index -> audioEngine.setQueueAndPlay(queue, index) },
                        settingsJson = settingsJson
                    )

                    1 -> ExploreScreen(
                        innerTubeRepo = innerTubeRepo,
                        audioEngine = audioEngine,
                        onDownloadTrack = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Extracting audio: ${online.title}", Toast.LENGTH_SHORT).show()
                                    downloader.downloadAudioTrack(online, "")
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )

                    2 -> LibraryScreen(
                        localTracks = offlineTracks,
                        searchOnlineResults = searchOnlineResults,
                        onTrackClick = { track -> audioEngine.playTrack(track) },
                        onOnlineTrackClick = { online ->
                            coroutineScope.launch {
                                playOnlineTrack(online, context, audioEngine)
                            }
                        },
                        onDownloadOnlineTrack = { online ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(context, "Starting download: ${online.title}", Toast.LENGTH_SHORT).show()
                                    downloader.downloadAudioTrack(online, "")
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
                        onImportFilesClick = {
                            importAudioLauncher.launch(arrayOf("audio/*"))
                        },
                        onImportFolderClick = {
                            importFolderLauncher.launch(null)
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
                        onPlayQueue = { queue, idx -> audioEngine.setQueueAndPlay(queue, idx) },
                        onAddToQueueNext = { track -> audioEngine.addToQueueNext(track) },
                        onAddToQueueLast = { track -> audioEngine.addToQueueLast(track) },
                        onToggleFavoriteClick = { track ->
                            coroutineScope.launch {
                                repository.toggleFavorite(track)
                                Toast.makeText(context, "Favorite updated", Toast.LENGTH_SHORT).show()
                            }
                        },
                        recentlyPlayedTracks = recentlyPlayedTracks,
                        playlists = playlists,
                        onCreatePlaylist = { name ->
                            coroutineScope.launch { repository.createPlaylist(name) }
                        },
                        onPlayPlaylist = { playlist ->
                            coroutineScope.launch {
                                val tracks = repository.getTracksForPlaylist(playlist.id).first()
                                if (tracks.isNotEmpty()) audioEngine.setQueueAndPlay(tracks, 0)
                                else Toast.makeText(context, "This playlist is empty", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    3 -> DownloaderScreen(
                        downloadEntries = downloadEntries,
                        searchResults = downloaderSearchResults,
                        onSearchKeyword = { keyword ->
                            if (keyword.isNotBlank()) {
                                coroutineScope.launch {
                                    isDownloaderSearching = true
                                    downloaderSearchResults = try {
                                        innerTubeRepo.searchTracks(keyword.trim())
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainScreen", "Downloader search failed: ${e.message}")
                                        emptyList()
                                    } finally {
                                        isDownloaderSearching = false
                                    }
                                }
                            } else {
                                downloaderSearchResults = emptyList()
                            }
                        },
                        onDownloadTrack = { track ->
                            coroutineScope.launch { downloader.startDownload(track) }
                        },
                        onStartUrlDownload = { url ->
                            val intent = android.content.Intent(context, FloWaveDownloadService::class.java)
                            intent.putExtra(FloWaveDownloadService.EXTRA_URL, url)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
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
                        },
                        settingsJson = settingsJson,
                        onSettingUpdated = { key, value ->
                            coroutineScope.launch {
                                profileRepo.updateSetting(key, value)
                            }
                        },
                        onExportSettings = {
                            profileRepo.exportSettings()
                        },
                        onImportSettings = { jsonStr ->
                            profileRepo.importSettings(jsonStr)
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
                    selectedTab = 4
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

        // App Recovery Diagnostics Dialog
        if (showCrashReportDialog) {
            AlertDialog(
                onDismissRequest = {
                    com.example.flowave.utils.FloWaveCrashHandler.clearCrashReport(context)
                    showCrashReportDialog = false
                },
                title = {
                    Text("App Recovery Diagnostics", color = PinkNeon, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                },
                text = {
                    Column {
                        Text("It looks like FloWave closed unexpectedly during your last session. Our recovery module captured a diagnosis log to assist debugging:", color = TextPrimary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(Color.Black.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                                item {
                                    Text(
                                        text = crashReportContent,
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            com.example.flowave.utils.FloWaveCrashHandler.clearCrashReport(context)
                            showCrashReportDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                    ) {
                        Text("Acknowledge & Clear", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, crashReportContent)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "FloWave Crash Report")
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share Crash Report via"))
                        }
                    ) {
                        Text("Share Diagnostic", color = CyanNeon)
                    }
                },
                containerColor = DarkSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            )
        }
    }
}
