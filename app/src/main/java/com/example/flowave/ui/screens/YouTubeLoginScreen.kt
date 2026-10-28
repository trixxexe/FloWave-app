package com.example.flowave.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.ytmusic.CookieNormalizer
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.ui.components.LiquidGlassCard
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F&hl=en"

private fun hasSessionTokens(cookies: String?): Boolean {
    if (cookies.isNullOrBlank()) return false
    val hasSapisid = cookies.contains("SAPISID=") || cookies.contains("__Secure-3PAPISID=") || cookies.contains("APISID=")
    val hasSid = cookies.contains("SID=") || cookies.contains("__Secure-3PSID=")
    return hasSapisid && hasSid
}

private fun readYouTubeCookies(): String? = try {
    val cm = CookieManager.getInstance()
    cm.flush()
    val domains = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://accounts.google.com",
        "https://youtube.com",
    )
    val cookieMap = mutableMapOf<String, String>()
    for (domain in domains) {
        val raw = cm.getCookie(domain) ?: continue
        for (part in raw.split(';')) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    cookieMap[name] = value
                }
            }
        }
    }
    if (cookieMap.isEmpty()) null else cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
} catch (error: Exception) {
    null
}

@Composable
fun YouTubeLoginScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authManager = remember { YtMusicAuthManager.getInstance(context) }
    val innerTubeRepo = remember { InnerTubeRepository.getInstance(context) }

    var loadProgressVisible by remember { mutableStateOf(true) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var connectedName by remember { mutableStateOf<String?>(null) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var showManualCookieDialog by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }
    var manualCookieError by remember { mutableStateOf<String?>(null) }

    // Pre-login cookie and storage wipe so prior sessions don't get auto-captured
    LaunchedEffect(Unit) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        }
    }

    // Polling loop watching for successful login redirect to music.youtube.com
    LaunchedEffect(connectedName, isVerifying, activeWebView) {
        if (connectedName != null || isVerifying) return@LaunchedEffect
        while (true) {
            val currentUrl = activeWebView?.url
            val isAtTarget = currentUrl?.contains("music.youtube.com") == true
            if (isAtTarget) {
                val cookies = readYouTubeCookies()
                if (hasSessionTokens(cookies)) {
                    if (!isVerifying && connectedName == null) {
                        isVerifying = true
                        coroutineScope.launch {
                            val cleanCookies = runCatching { CookieNormalizer.normalize(cookies) }.getOrDefault(cookies.orEmpty())
                            authManager.connect(cleanCookies, "Google Account", null, null)
                            withTimeoutOrNull(4000L) { authManager.awaitLoadedConnection() }
                            val info = runCatching { innerTubeRepo.fetchAccountInfo() }.getOrNull()
                            val displayName = info?.accountName?.takeIf { it.isNotBlank() } ?: "Google Account"
                            if (info != null) {
                                authManager.updateAccountIdentity(info.accountName, info.channelHandle, info.photoUrl)
                            }
                            connectedName = displayName
                            isVerifying = false
                        }
                    }
                    break
                }
            }
            delay(1200L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // iOS Liquid Glass Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(SquircleShape(12.dp))
                        .background(Color(0x22FFFFFF))
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sign In to YouTube Music",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Access your personal playlists, likes & library",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = { showManualCookieDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(SquircleShape(12.dp))
                        .background(Color(0x22FFFFFF))
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Paste Cookies",
                        tint = CyanNeon
                    )
                }
            }

            if (loadProgressVisible || isVerifying) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanNeon,
                    trackColor = Color(0x1A00E5FF)
                )
            }

            // In-App WebView
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).also {
                            activeWebView = it
                        }.apply {
                            @SuppressLint("SetJavaScriptEnabled")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            clearCache(true)
                            clearHistory()
                            clearFormData()

                            // User-Agent cloaking: strip embedded WebView markers ("; wv" and "Version/4.0")
                            // so Google's sign-in does not reject the WebView
                            val defaultUa = settings.userAgentString
                            val cleanUa = if (!defaultUa.isNullOrBlank()) {
                                defaultUa.replace("; wv", "")
                                    .replace("Version/4.0 ", "")
                                    .replace("Version/4.0", "")
                                    .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                            } else {
                                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            }
                            settings.userAgentString = cleanUa

                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (isVerifying || connectedName != null) return
                                    CookieManager.getInstance().flush()
                                    val cookies = readYouTubeCookies()
                                    if (hasSessionTokens(cookies)) {
                                        checkAndConnect(cookies)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    loadProgressVisible = false
                                    CookieManager.getInstance().flush()
                                    val cookies = readYouTubeCookies()
                                    if (hasSessionTokens(cookies)) {
                                        checkAndConnect(cookies)
                                    }
                                }

                                private fun checkAndConnect(cookies: String?) {
                                    if (isVerifying || connectedName != null) return
                                    isVerifying = true
                                    coroutineScope.launch {
                                        val cleanCookies = runCatching { CookieNormalizer.normalize(cookies) }.getOrDefault(cookies.orEmpty())
                                        authManager.connect(cleanCookies, "Google Account", null, null)
                                        withTimeoutOrNull(4000L) { authManager.awaitLoadedConnection() }
                                        val info = runCatching { innerTubeRepo.fetchAccountInfo() }.getOrNull()
                                        val displayName = info?.accountName?.takeIf { it.isNotBlank() } ?: "Google Account"
                                        if (info != null) {
                                            authManager.updateAccountIdentity(info.accountName, info.channelHandle, info.photoUrl)
                                        }
                                        connectedName = displayName
                                        isVerifying = false
                                    }
                                }
                            }
                            loadUrl(LOGIN_URL)
                        }
                    }
                )

                if (webViewError != null) {
                    LiquidGlassCard(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = webViewError.orEmpty(),
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                }

                // Success Overlay Dialog (iOS Liquid Glass)
                AnimatedVisibility(
                    visible = connectedName != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    LiquidGlassCard(
                        cornerRadius = 28.dp,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = CyanNeon,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = PureBlack,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Successfully Connected!",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "Signed in as ${connectedName.orEmpty()}",
                                color = TextMuted,
                                fontSize = 14.sp
                            )

                            Button(
                                onClick = onConnected,
                                colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                                shape = SquircleShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Continue to Music",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Manual Cookie Paste Dialog
        if (showManualCookieDialog) {
            AlertDialog(
                onDismissRequest = { showManualCookieDialog = false },
                containerColor = DarkSurface,
                shape = SquircleShape(24.dp),
                title = {
                    Text("Paste YouTube Cookies", color = TextPrimary, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Export cookies from music.youtube.com while logged in, then paste them here.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = manualCookieText,
                            onValueChange = {
                                manualCookieText = it
                                manualCookieError = null
                            },
                            placeholder = { Text("Cookie header, JSON, or cookies.txt", color = TextMuted) },
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                            shape = SquircleShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CyanNeon,
                                unfocusedBorderColor = Color(0x33FFFFFF)
                            )
                        )
                        if (manualCookieError != null) {
                            Text(manualCookieError.orEmpty(), color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            try {
                                val normalized = CookieNormalizer.normalize(manualCookieText)
                                showManualCookieDialog = false
                                isVerifying = true
                                coroutineScope.launch {
                                    authManager.connect(normalized, "Google Account", null, null)
                                    withTimeoutOrNull(4000L) { authManager.awaitLoadedConnection() }
                                    val info = runCatching { innerTubeRepo.fetchAccountInfo() }.getOrNull()
                                    val displayName = info?.accountName?.takeIf { it.isNotBlank() } ?: "Google Account"
                                    if (info != null) {
                                        authManager.updateAccountIdentity(info.accountName, info.channelHandle, info.photoUrl)
                                    }
                                    connectedName = displayName
                                    isVerifying = false
                                }
                            } catch (e: Exception) {
                                manualCookieError = e.message ?: "Invalid cookie format."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                        shape = SquircleShape(12.dp)
                    ) {
                        Text("Connect", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualCookieDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                }
            )
        }
    }
}
