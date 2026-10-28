package com.example.flowave.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.example.flowave.diagnostics.FloWaveLogger
import com.example.flowave.downloader.FloWaveDownloader
import com.example.flowave.downloader.FloWaveDownloadService
import com.example.flowave.ui.components.DynamicIslandWidget
import com.example.flowave.ui.components.GlassCard
import com.example.flowave.ui.components.MiniPlayer
import com.example.flowave.ui.components.PermissionManager
import com.example.flowave.ui.components.TagEditorDialog
import com.example.flowave.ui.screens.*
import com.example.flowave.ui.theme.*
import com.example.flowave.utils.SettingsSchema
import kotlinx.coroutines.Job
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
    val diagnostics = remember { FloWaveLogger.getInstance(context) }

    val localTracks by repository.allTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val offlineTracks = remember(localTracks) { localTracks.filterNot { it.isOnline } }
    val recentlyPlayedTracks by repository.recentlyPlayedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by repository.allPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val totalTimeMs by repository.totalListeningTimeMs.collectAsStateWithLifecycle(initialValue = 0L)
    val totalPlayCount by repository.totalPlayCount.collectAsStateWithLifecycle(initialValue = 0)
    val downloadEntries by downloader.allDownloadEntries.collectAsStateWithLifecycle(initialValue = emptyList())
    val userProfile by profileRepo.userProfile.collectAsStateWithLifecycle(initialValue = UserProfile())
    val settingsJsonNullable by profileRepo.settingsJson.collectAsStateWithLifecycle(initialValue = null)
    val settingsJson = settingsJsonNullable ?: SettingsSchema.getDefaultJson()
    val diagnosticEntries by diagnostics.entries.collectAsStateWithLifecycle(initialValue = emptyList())

    var featuredOnlineTracks by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var searchOnlineResults by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var downloaderSearchResults by remember { mutableStateOf<List<InnerTubeTrack>>(emptyList()) }
    var isDownloaderSearching by remember { mutableStateOf(false) }
    var downloaderSearchError by remember { mutableStateOf<String?>(null) }
    var downloaderSearchJob by remember { mutableStateOf<Job?>(null) }
    var currentLrcLines by remember { mutableStateOf<List<LrcLine>>(emptyList()) }

    var showCrashReportDialog by remember { mutableStateOf(false) }
    var crashReportContent by remember { mutableStateOf("") }
    val hasCompletedOnboarding = remember(settingsJsonNullable) {
        val currentJson = settingsJsonNullable
        if (currentJson == null) {
            true // Prevent showing dialog while DataStore is loading
        } else {
            SettingsSchema.getBoolean(currentJson, "has_completed_onboarding")
        }
    }
    var showOnboardingDialog by remember { mutableStateOf(false) }
    var onboardingStep by remember { mutableIntStateOf(0) }

    LaunchedEffect(hasCompletedOnboarding) {
        if (!hasCompletedOnboarding) {
            showOnboardingDialog = true
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Home, 1: Explore, 2: Library, 3: Profile
    var showLoginScreen by remember { mutableStateOf(false) }
    var showEqualizerScreen by remember { mutableStateOf(false) }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var isMiniPlayerDismissed by remember { mutableStateOf(false) }
    var editingTrack by remember { mutableStateOf<Track?>(null) }
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }
    var activeTrackForStats by remember { mutableStateOf<Track?>(null) }
    var lastPositionMs by remember { mutableLongStateOf(0L) }
    var accumulatedTimeMs by remember { mutableLongStateOf(0L) }
    var pendingDiagnosticExport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                featuredOnlineTracks = innerTubeRepo.fetchHomeFeed()
            } catch (_: Exception) {
                featuredOnlineTracks = innerTubeRepo.getTrendingTracks()
            }
        }
    }

    val playbackState by audioEngine.playbackState.collectAsStateWithLifecycle()

    LaunchedEffect(selectedTab) {
        diagnostics.info("navigation", "tab_selected", context = mapOf("tab" to selectedTab))
    }

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
    val diagnosticExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingDiagnosticExport
        pendingDiagnosticExport = null
        if (uri != null && text != null) {
            coroutineScope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open export destination")
                }.onSuccess {
                    Toast.makeText(context, "Diagnostics exported", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Diagnostics export failed", Toast.LENGTH_SHORT).show()
                    diagnostics.warn("diagnostics", "export_failed", throwable = it)
                }
            }
        }
    }

    // BackHandler: Single tap as redirector to previous page, Double tap on Home to exit app
    BackHandler(enabled = true) {
        if (showLoginScreen) {
            showLoginScreen = false
        } else if (showEqualizerScreen) {
            showEqualizerScreen = false
        } else if (isPlayerExpanded) {
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
                    innerTubeRepo.getTrendingTracks()
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
    val themeMode = SettingsSchema.getValue(settingsJson, "theme_mode")

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
                var lines: List<LrcLine> = emptyList()
                if (!currentTrack.isOnline) {
                    val rawUri = currentTrack.mediaUri.removePrefix("file://")
                    val audioFile = java.io.File(rawUri)
                    if (audioFile.exists()) {
                        val sidecar1 = java.io.File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                        val sidecar2 = java.io.File(audioFile.parentFile, "${audioFile.name}.lrc")
                        if (sidecar1.exists()) {
                            lines = innerTubeRepo.parseLocalLrc(sidecar1)
                        } else if (sidecar2.exists()) {
                            lines = innerTubeRepo.parseLocalLrc(sidecar2)
                        }
                    }
                }
                if (lines.isEmpty()) {
                    lines = innerTubeRepo.fetchLrcLyrics(currentTrack.title, currentTrack.artist)
                }
                currentLrcLines = lines
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
            val bgBrush = when {
                themeMode == "AMOLED_DARK" -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
                userProfile.backgroundPreset == "AMOLED_DARK" -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
                userProfile.backgroundPreset == "CYBER_CYAN" -> Brush.verticalGradient(listOf(Color(0xFF031D24), Color(0xFF070B10)))
                userProfile.backgroundPreset == "NEON_PURPLE" -> Brush.verticalGradient(listOf(Color(0xFF1B0326), Color(0xFF070B10)))
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
            topBar = {
                if (!isPlayerExpanded && !showLoginScreen && !showEqualizerScreen && selectedTab != 3) {
                    Surface(
                        color = DarkSurface.copy(alpha = 0.95f),
                        contentColor = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Logo branding
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(SquircleShape(10.dp))
                                    .clickable { selectedTab = 0 }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(SquircleShape(10.dp))
                                        .background(Brush.linearGradient(listOf(CyanNeon, PurpleNeon))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("~", color = PureBlack, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("FloWave", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }

                            // Right: App Tour & Profile Button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        onboardingStep = 0
                                        showOnboardingDialog = true
                                    },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(SquircleShape(12.dp))
                                        .background(Color(0x22FFFFFF))
                                ) {
                                    Icon(
                                        Icons.Default.HelpOutline,
                                        contentDescription = "App Tour",
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { selectedTab = 3 },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(SquircleShape(12.dp))
                                        .background(if (selectedTab == 3) CyanNeon.copy(alpha = 0.3f) else Color(0x22FFFFFF))
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = "Profile & YouTube Account",
                                        tint = if (selectedTab == 3) CyanNeon else TextPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (!isPlayerExpanded && !showLoginScreen && !showEqualizerScreen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally
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
                                onSeek = { pos -> audioEngine.seekTo(pos) },
                                showProgress = SettingsSchema.getBoolean(settingsJson, "show_mini_player_progress")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // Floating iOS Liquid Glass Capsule Dock
                        val dockShape = remember { SquircleShape(28.dp) }
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .clip(dockShape)
                                .background(
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color(0x331E1E2E),
                                            Color(0x22121218)
                                        )
                                    )
                                )
                                .border(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color(0x55FFFFFF),
                                            Color(0x10FFFFFF)
                                        )
                                    ),
                                    shape = dockShape
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val navTabs = listOf(
                                    Triple(0, Icons.Default.Home, "Home"),
                                    Triple(1, Icons.Default.TravelExplore, "Explore"),
                                    Triple(2, Icons.Default.LibraryMusic, "Library"),
                                    Triple(3, Icons.Default.Person, "Profile")
                                )

                                navTabs.forEach { (index, icon, label) ->
                                    val isSelected = selectedTab == index
                                    val interaction = remember { MutableInteractionSource() }
                                    Column(
                                        modifier = Modifier
                                            .expressivePressScale(interaction)
                                            .clip(SquircleShape(16.dp))
                                            .clickable(interactionSource = interaction, indication = null) {
                                                selectedTab = index
                                            }
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = label,
                                            tint = if (isSelected) CyanNeon else TextMuted,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) CyanNeon else TextMuted
                                        )
                                    }
                                }
                            }
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
                if (showLoginScreen) {
                    YouTubeLoginScreen(
                        onBack = { showLoginScreen = false },
                        onConnected = { showLoginScreen = false }
                    )
                } else if (showEqualizerScreen) {
                    EqualizerScreen(
                        audioEngine = audioEngine,
                        onBackClick = { showEqualizerScreen = false }
                    )
                } else {
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
                            onProfileClick = { selectedTab = 3 },
                            onDownloaderClick = { selectedTab = 1 },
                            onScanClick = {
                                coroutineScope.launch {
                                    repository.scanMediaStore()
                                    Toast.makeText(context, "Device library refreshed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onExploreOnlineClick = { selectedTab = 1 },
                            onPlayQueue = { queue, index -> audioEngine.setQueueAndPlay(queue, index) },
                            settingsJson = settingsJson
                        )

                        1 -> ExploreScreen(
                            innerTubeRepo = innerTubeRepo,
                            audioEngine = audioEngine,
                            onDownloadTrack = { online ->
                                coroutineScope.launch {
                                    playOnlineTrack(online, context, audioEngine)
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
                                    playOnlineTrack(online, context, audioEngine)
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
                            },
                            onOpenLogin = { showLoginScreen = true }
                        )

                        3 -> ProfileScreen(
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
                            },
                            diagnosticEntries = diagnosticEntries,
                            onExportDiagnostics = {
                                coroutineScope.launch {
                                    pendingDiagnosticExport = diagnostics.exportText()
                                    diagnosticExportLauncher.launch("flowave-diagnostics-${System.currentTimeMillis()}.txt")
                                }
                            },
                            onClearUnsavedDiagnostics = { diagnostics.clearUnsaved() },
                            onClearAllDiagnostics = { diagnostics.clearAll() },
                            onProtectDiagnostic = { id, protect -> diagnostics.protect(id, protect) },
                            onOpenLogin = { showLoginScreen = true },
                            onOpenEqualizer = { showEqualizerScreen = true }
                        )
                    }
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
                settingsJson = settingsJson,
                onCloseClick = { isPlayerExpanded = false },
                onOpenEqualizerClick = {
                    isPlayerExpanded = false
                    showEqualizerScreen = true
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

        // Interactive Onboarding Tour Dialog (6 Comprehensive Slides with 3 Configuration Steps)
        if (showOnboardingDialog) {
            AlertDialog(
                onDismissRequest = {
                    coroutineScope.launch { profileRepo.updateSetting("has_completed_onboarding", true) }
                    showOnboardingDialog = false
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(CyanNeon, PurpleNeon))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("~", color = PureBlack, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }
                        Text(
                            text = when (onboardingStep) {
                                0 -> "Welcome to FloWave"
                                1 -> "1. Storage & Permissions"
                                2 -> "2. Audio Hygiene Filters"
                                3 -> "3. Streaming & Fidelity"
                                4 -> "4. Studio DSP & Equalizer"
                                else -> "Engineered by Ritam"
                            },
                            color = CyanNeon,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when (onboardingStep) {
                            0 -> {
                                Text(
                                    "FloWave is an audiophile-grade dual-engine music workstation combining bit-perfect offline local audio playback and limitless YouTube Music streaming.",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(DarkBackground)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Offline Master (Cloud-Off)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("Zero placeholders. Hi-Res FLAC/MP3, Folders, Albums, Tag Editor, Smart Playlists.", color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(DarkBackground)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Cloud, contentDescription = null, tint = PurpleNeon, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text("Online Stream (Cloud)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("InnerTube client extraction, live chart rankings, YouTube Music streams & audio downloader.", color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                            1 -> {
                                Text(
                                    "FloWave requires audio file access to index your music library, and notification permission to display interactive lockscreen controls.",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = CyanNeon, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Audio Storage Access", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("Scans bit-perfect FLAC, MP3, WAV, and AAC", color = TextMuted, fontSize = 10.sp)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Notifications, contentDescription = null, tint = PurpleNeon, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text("Playback Notification & Lockscreen", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("Scrub seekbar and skip tracks from system shade", color = TextMuted, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "🔒 Local files never leave your device. Zero telemetry or external tracking.",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            2 -> {
                                Text(
                                    "Ensure your music library stays clean and free from voice notes, notification chimes, and game sound effects.",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        val filterShort = SettingsSchema.getBoolean(settingsJson, "filter_short_audio")
                                        val excludeFolders = SettingsSchema.getBoolean(settingsJson, "exclude_system_folders")

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Filter short clips (< 30s)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("Excludes WhatsApp voice notes and sound FX", color = TextMuted, fontSize = 10.sp)
                                            }
                                            Switch(
                                                checked = filterShort,
                                                onCheckedChange = { coroutineScope.launch { profileRepo.updateSetting("filter_short_audio", it) } }
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Exclude chat & system folders", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("Ignores WhatsApp, Telegram, Ringtones, Alarms", color = TextMuted, fontSize = 10.sp)
                                            }
                                            Switch(
                                                checked = excludeFolders,
                                                onCheckedChange = { coroutineScope.launch { profileRepo.updateSetting("exclude_system_folders", it) } }
                                            )
                                        }
                                    }
                                }
                            }
                            3 -> {
                                Text(
                                    "Configure online streaming audio fidelity and caching preferences for YouTube Music InnerTube extraction.",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                val streamingQuality = SettingsSchema.getValue(settingsJson, "streaming_quality")
                                val autoCache = SettingsSchema.getBoolean(settingsJson, "auto_cache_stream")
                                val fadeSleep = SettingsSchema.getBoolean(settingsJson, "fade_out_sleep_timer")

                                Text("Default Streaming Bitrate:", color = CyanNeon, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("High", "Medium", "DataSaver").forEach { q ->
                                        FilterChip(
                                            selected = streamingQuality == q,
                                            onClick = { coroutineScope.launch { profileRepo.updateSetting("streaming_quality", q) } },
                                            label = { Text(if (q == "DataSaver") "Saver" else q, fontSize = 10.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = CyanNeon,
                                                selectedLabelColor = PureBlack
                                            )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Auto-cache streamed tracks", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("Saves mobile data on repeat plays", color = TextMuted, fontSize = 10.sp)
                                            }
                                            Switch(
                                                checked = autoCache,
                                                onCheckedChange = { coroutineScope.launch { profileRepo.updateSetting("auto_cache_stream", it) } }
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Fade-out sleep timer", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("15-second smooth exponential fade", color = TextMuted, fontSize = 10.sp)
                                            }
                                            Switch(
                                                checked = fadeSleep,
                                                onCheckedChange = { coroutineScope.launch { profileRepo.updateSetting("fade_out_sleep_timer", it) } }
                                            )
                                        }
                                    }
                                }
                            }
                            4 -> {
                                Text(
                                    "Experience studio-grade audio processing powered by Android's hardware audio effects engine.",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("DSP Suite Features:", color = CyanNeon, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("• 10-Band Graphic Hardware Equalizer (32Hz to 16kHz)", color = TextPrimary, fontSize = 11.sp)
                                        Text("• Sub-Bass Exciter & 3D Spatial Virtualizer", color = TextPrimary, fontSize = 11.sp)
                                        Text("• 'Ritam Audiophile Signature' acoustic profile", color = TextPrimary, fontSize = 11.sp)
                                        Text("• Sidecar .lrc synchronized karaoke lyrics reader", color = TextPrimary, fontSize = 11.sp)
                                        Text("• Bit-perfect 24-bit/96kHz direct audio pipeline", color = TextPrimary, fontSize = 11.sp)
                                    }
                                }
                            }
                            else -> {
                                Text(
                                    "FloWave is designed and crafted by Ritam (@ritam.zip & @ritam.localhost).",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Ritam's Developer Guarantee:", color = PinkNeon, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("• 100% Free & Open-Source Core (GPL-3.0)", color = TextPrimary, fontSize = 11.sp)
                                        Text("• Zero tracking, zero telemetry, zero analytics", color = TextPrimary, fontSize = 11.sp)
                                        Text("• Direct feedback & feature roadmap via Profile > Developer", color = TextPrimary, fontSize = 11.sp)
                                        Text("• M3U/M3U8 playlist export and backup support", color = TextPrimary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        // Progress indicator dots (6 dots)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(6) { index ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(if (index == onboardingStep) 8.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(if (index == onboardingStep) CyanNeon else TextMuted.copy(alpha = 0.35f))
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (onboardingStep < 5) {
                                onboardingStep++
                            } else {
                                coroutineScope.launch {
                                    profileRepo.updateSetting("has_completed_onboarding", true)
                                    repository.scanMediaStore()
                                }
                                showOnboardingDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack)
                    ) {
                        Text(
                            text = if (onboardingStep < 5) "Next" else "Launch FloWave",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    if (onboardingStep > 0) {
                        TextButton(onClick = { onboardingStep-- }) {
                            Text("Back", color = TextMuted)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    profileRepo.updateSetting("has_completed_onboarding", true)
                                }
                                showOnboardingDialog = false
                            }
                        ) {
                            Text("Skip", color = TextMuted)
                        }
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
