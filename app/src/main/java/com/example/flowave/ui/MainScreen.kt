package com.example.flowave.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.flowave.audio.FloWaveAudioEngine
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.LrcLine
import com.example.flowave.data.model.Track
import com.example.flowave.data.model.UserProfile
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.repository.FloWaveRepository
import com.example.flowave.data.repository.ProfileRepository
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.diagnostics.FloWaveLogger
import com.example.flowave.downloader.FloWaveDownloadService
import com.example.flowave.downloader.FloWaveDownloader
import com.example.flowave.ui.components.MiniPlayer
import com.example.flowave.ui.components.PermissionManager
import com.example.flowave.ui.components.TagEditorDialog
import com.example.flowave.ui.screens.*
import com.example.flowave.ui.theme.*
import com.example.flowave.utils.SettingsSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private suspend fun playOnlineTrack(
    online: InnerTubeTrack,
    context: Context,
    audioEngine: FloWaveAudioEngine,
) {
    try {
        audioEngine.playOnlineTrack(online)
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Failed to resolve stream: ${e.localizedMessage ?: e.message}",
            Toast.LENGTH_LONG
        ).show()
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
    val authManager = remember { YtMusicAuthManager.getInstance(context) }
    val connection by authManager.connection.collectAsStateWithLifecycle()

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

    // 0: Home, 1: Explore, 2: Library, 3: Downloads, 4: Settings
    var selectedTab by remember { mutableIntStateOf(0) }
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

    // Refresh account identity and personalized Home feed whenever YouTube Music login state or cookies change
    LaunchedEffect(connection.isConnected, connection.cookies) {
        coroutineScope.launch {
            if (connection.isConnected) {
                val info = runCatching { innerTubeRepo.fetchAccountInfo() }.getOrNull()
                if (info != null && info.accountName.isNotBlank()) {
                    authManager.updateAccountIdentity(
                        accountName = info.accountName,
                        channelHandle = info.channelHandle,
                        photoUrl = info.photoUrl
                    )
                }
            }
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
                Toast.makeText(
                    context,
                    "Imported ${imported.size} audio file${if (imported.size == 1) "" else "s"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val imported = repository.importAudioTree(uri)
                Toast.makeText(
                    context,
                    "Imported ${imported.size} audio file${if (imported.size == 1) "" else "s"} from folder",
                    Toast.LENGTH_SHORT
                ).show()
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

    BackHandler(enabled = true) {
        if (showLoginScreen) {
            authManager.tryConnectFromCookieManager()
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

    LaunchedEffect(Unit) {
        launch {
            runCatching {
                repository.removeUnavailableImportedTracks()
                repository.scanMediaStore()
            }
        }
        launch {
            runCatching {
                withTimeoutOrNull(10_000L) {
                    innerTubeRepo.ensureKeysUpdated()
                    innerTubeRepo.fetchHomeFeed()
                } ?: emptyList()
            }.onSuccess {
                if (it.isNotEmpty()) featuredOnlineTracks = it
            }
        }

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
            isMiniPlayerDismissed = false
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
            } catch (_: Exception) {
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
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        if (!userProfile.customBgUrl.isNullOrEmpty()) {
            AsyncImage(
                model = userProfile.customBgUrl,
                contentDescription = "Custom Background",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PureBlack.copy(alpha = 0.85f))
            )
        }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!isPlayerExpanded && !showLoginScreen && !showEqualizerScreen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PureBlack)
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (playbackState.currentTrack != null && !isMiniPlayerDismissed) {
                            MiniPlayer(
                                playbackState = playbackState,
                                onPlayPauseClick = { audioEngine.togglePlayPause() },
                                onNextClick = { audioEngine.playNext() },
                                onFavoriteClick = {
                                    audioEngine.toggleCurrentFavorite()
                                },
                                onExpandClick = { isPlayerExpanded = true },
                                onCloseClick = { isMiniPlayerDismissed = true },
                                onRetryClick = { audioEngine.retryCurrentTrack() },
                                onSeek = { pos -> audioEngine.seekTo(pos) },
                                showProgress = SettingsSchema.getBoolean(settingsJson, "show_mini_player_progress")
                            )
                        }

                        // Minimalist Hairline Divider + AMOLED Bottom Navigation Bar
                        HorizontalDivider(color = GlassBorder, thickness = 1.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(PureBlack)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val navTabs = listOf(
                                Triple(0, Icons.Default.Home, "Home"),
                                Triple(1, Icons.Default.Search, "Explore"),
                                Triple(2, Icons.Default.LibraryMusic, "Library"),
                                Triple(3, Icons.Default.Download, "Downloads"),
                                Triple(4, Icons.Default.Person, "Settings")
                            )

                            navTabs.forEach { (index, icon, label) ->
                                val isSelected = selectedTab == index
                                val interaction = remember { MutableInteractionSource() }
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(interactionSource = interaction, indication = null) {
                                            selectedTab = index
                                        }
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = label,
                                        tint = if (isSelected) CyanNeon else TextMuted,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) CyanNeon else TextMuted
                                    )
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
                        onConnected = {
                            showLoginScreen = false
                            coroutineScope.launch {
                                featuredOnlineTracks = innerTubeRepo.fetchHomeFeed()
                            }
                        }
                    )
                } else if (showEqualizerScreen) {
                    EqualizerScreen(
                        audioEngine = audioEngine,
                        onBackClick = { showEqualizerScreen = false }
                    )
                } else {
                    when (selectedTab) {
                        0 -> HomeScreen(
                            tracks = localTracks,
                            featuredOnline = featuredOnlineTracks,
                            totalListeningTimeMs = totalTimeMs ?: 0L,
                            totalPlayCount = totalPlayCount,
                            onTrackClick = { track -> audioEngine.playTrack(track) },
                            onOnlineTrackClick = { online ->
                                coroutineScope.launch {
                                    playOnlineTrack(online, context, audioEngine)
                                }
                            },
                            onProfileClick = { selectedTab = 4 },
                            onDownloaderClick = { selectedTab = 3 },
                            onScanClick = {
                                coroutineScope.launch {
                                    repository.scanMediaStore()
                                    Toast.makeText(context, "Device library refreshed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onExploreOnlineClick = { selectedTab = 1 },
                            onPlayQueue = { queue, index -> audioEngine.setQueueAndPlay(queue, index) },
                            onOpenLogin = { showLoginScreen = true },
                            settingsJson = settingsJson
                        )

                        1 -> ExploreScreen(
                            innerTubeRepo = innerTubeRepo,
                            audioEngine = audioEngine,
                            onDownloadTrack = { online ->
                                coroutineScope.launch {
                                    runCatching { downloader.startDownload(online) }
                                        .onFailure { err ->
                                            Toast.makeText(context, err.message ?: "Download failed", Toast.LENGTH_SHORT).show()
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
                                    playOnlineTrack(online, context, audioEngine)
                                }
                            },
                            onDownloadOnlineTrack = { online ->
                                Toast.makeText(context, "Downloading ${online.title}", Toast.LENGTH_SHORT).show()
                                coroutineScope.launch {
                                    runCatching { downloader.startDownload(online) }
                                        .onFailure { err ->
                                            Toast.makeText(context, err.message ?: "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                }
                            },
                            onScanStorageClick = {
                                coroutineScope.launch {
                                    repository.scanMediaStore()
                                    Toast.makeText(context, "Scanned device storage", Toast.LENGTH_SHORT).show()
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
                                        searchOnlineResults = runCatching {
                                            innerTubeRepo.searchTracks(query)
                                        }.getOrDefault(emptyList())
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
                                    else Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onPlayOnlineQueue = { tracks, idx ->
                                audioEngine.playOnlineQueue(tracks, idx)
                            },
                            onOpenLogin = { showLoginScreen = true }
                        )

                        3 -> DownloaderScreen(
                            downloadEntries = downloadEntries,
                            searchResults = downloaderSearchResults,
                            isSearching = isDownloaderSearching,
                            searchError = downloaderSearchError,
                            onSearchKeyword = { query ->
                                downloaderSearchJob?.cancel()
                                downloaderSearchJob = coroutineScope.launch {
                                    isDownloaderSearching = true
                                    downloaderSearchError = null
                                    try {
                                        downloaderSearchResults = innerTubeRepo.searchTracks(query)
                                    } catch (e: Exception) {
                                        downloaderSearchError = e.localizedMessage ?: "Search failed"
                                    } finally {
                                        isDownloaderSearching = false
                                    }
                                }
                            },
                            onDownloadTrack = { online ->
                                coroutineScope.launch {
                                    runCatching { downloader.startDownload(online) }
                                        .onFailure { err ->
                                            Toast.makeText(context, err.message ?: "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                }
                            },
                            onStartUrlDownload = { url ->
                                val serviceIntent = Intent(context, FloWaveDownloadService::class.java).apply {
                                    putExtra(FloWaveDownloadService.EXTRA_URL, url)
                                }
                                ContextCompat.startForegroundService(context, serviceIntent)
                            },
                            onBackClick = { selectedTab = 0 }
                        )

                        4 -> ProfileScreen(
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
                                    Toast.makeText(context, "Audio DSP settings saved", Toast.LENGTH_SHORT).show()
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

        PermissionManager(
            onPermissionsGranted = {
                coroutineScope.launch { repository.scanMediaStore() }
            }
        )

        editingTrack?.let { track ->
            TagEditorDialog(
                track = track,
                onDismiss = { editingTrack = null },
                onSave = { updated ->
                    coroutineScope.launch {
                        repository.updateTrack(updated)
                        Toast.makeText(context, "Tags updated for ${updated.title}", Toast.LENGTH_SHORT).show()
                    }
                    editingTrack = null
                }
            )
        }

        if (showCrashReportDialog) {
            AlertDialog(
                onDismissRequest = {
                    com.example.flowave.utils.FloWaveCrashHandler.clearCrashReport(context)
                    showCrashReportDialog = false
                },
                title = {
                    Text("Recovery Diagnostics", color = ErrorCrimson, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            "FloWave captured a diagnostic log from your previous session:",
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .background(PureBlack, RoundedCornerShape(8.dp))
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
                        Text("Clear", fontWeight = FontWeight.Bold)
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
                            context.startActivity(android.content.Intent.createChooser(intent, "Share Crash Report"))
                        }
                    ) {
                        Text("Share", color = CyanNeon)
                    }
                },
                containerColor = DarkSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
