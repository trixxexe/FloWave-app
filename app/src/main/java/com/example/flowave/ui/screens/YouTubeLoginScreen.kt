package com.example.flowave.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.ytmusic.CookieNormalizer
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.ui.theme.*
import com.example.flowave.utils.FloWaveConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com"

private fun hasSessionTokens(cookies: String?): Boolean {
    if (cookies.isNullOrBlank()) return false
    val hasSapisid = cookies.contains("SAPISID=") ||
        cookies.contains("__Secure-3PAPISID=") ||
        cookies.contains("__Secure-1PAPISID=") ||
        cookies.contains("APISID=")
    val hasSid = cookies.contains("SID=") ||
        cookies.contains("__Secure-3PSID=") ||
        cookies.contains("__Secure-1PSID=") ||
        cookies.contains("LOGIN_INFO=")
    return hasSapisid && hasSid
}

private fun isAuthenticatedYouTubePage(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val lower = url.lowercase()
    if (lower.contains("accounts.google.") || lower.contains("accounts.youtube.com") || lower.contains("ServiceLogin".lowercase())) {
        return false
    }
    return lower.contains("music.youtube.com") ||
        lower.contains("www.youtube.com") ||
        lower.contains("m.youtube.com") ||
        lower.startsWith("https://youtube.com")
}

private fun readYouTubeCookies(): String? = try {
    val cm = CookieManager.getInstance()
    cm.flush()
    // Strictly read from YouTube domains only (never accounts.google.com) so we only
    // capture cookies after Google's SetSID redirect has established the .youtube.com session.
    val domains = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://m.youtube.com",
        "https://youtube.com"
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
} catch (_: Exception) {
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
    var isVerifying by remember { mutableStateOf(false) }
    var connectedName by remember { mutableStateOf<String?>(null) }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    var showManualCookieDialog by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }
    var manualCookieError by remember { mutableStateOf<String?>(null) }

    var capturedVisitorData by remember { mutableStateOf<String?>(null) }
    var capturedDataSyncId by remember { mutableStateOf<String?>(null) }
    var capturedSessionIndex by remember { mutableIntStateOf(0) }

    fun connectAndVerifySession(rawCookies: String?) {
        val cookies = rawCookies ?: readYouTubeCookies()
        if (!hasSessionTokens(cookies)) return
        val cleanCookies = runCatching { CookieNormalizer.normalize(cookies) }.getOrDefault(cookies.orEmpty())

        // 1. Connect immediately in memory and schedule non-cancellable DataStore write on authManager.scope
        val connected = authManager.connectImmediate(
            rawCookieHeader = cleanCookies,
            accountName = authManager.connection.value.accountName.ifBlank { "YouTube Music Account" },
            channelHandle = authManager.connection.value.channelHandle,
            photoUrl = authManager.connection.value.photoUrl,
            visitorData = capturedVisitorData,
            dataSyncId = capturedDataSyncId,
            authUserIndex = capturedSessionIndex
        )
        if (!connected) return

        if (connectedName == null) {
            connectedName = authManager.connection.value.accountName.ifBlank { "YouTube Music Account" }
        }

        // 2. Fetch account profile in authManager.scope so navigating back never cancels profile resolution
        if (!isVerifying) {
            isVerifying = true
            authManager.scope.launch {
                val info = runCatching { innerTubeRepo.fetchAccountInfo() }.getOrNull()
                if (info != null && info.accountName.isNotBlank()) {
                    authManager.updateAccountIdentity(
                        accountName = info.accountName,
                        channelHandle = info.channelHandle,
                        photoUrl = info.photoUrl,
                        visitorData = capturedVisitorData,
                        dataSyncId = capturedDataSyncId
                    )
                    connectedName = info.accountName
                }
                isVerifying = false
            }
        }
    }

    // Ensure pressing Back or leaving composition always saves any session cookies obtained in WebView
    DisposableEffect(Unit) {
        onDispose {
            val cookies = readYouTubeCookies()
            if (hasSessionTokens(cookies)) {
                connectAndVerifySession(cookies)
            }
        }
    }

    // Background watcher that detects session cookies once Google's SetSID redirect lands on YouTube Music
    LaunchedEffect(activeWebView) {
        while (connectedName == null) {
            val currentUrl = activeWebView?.url.orEmpty()
            if (isAuthenticatedYouTubePage(currentUrl)) {
                val cookies = readYouTubeCookies()
                if (hasSessionTokens(cookies)) {
                    connectAndVerifySession(cookies)
                    break
                }
            }
            delay(800L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Minimalist AMOLED Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val cookies = readYouTubeCookies()
                        if (hasSessionTokens(cookies)) {
                            connectAndVerifySession(cookies)
                            onConnected()
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, GlassBorderWhite, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YouTube Music Sign In",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (connectedName != null) {
                            "Connected • $connectedName"
                        } else {
                            "Ad-free streaming, playlists & liked songs"
                        },
                        color = if (connectedName != null) CyanNeon else TextMuted,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = { activeWebView?.reload() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, GlassBorderWhite, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Reload",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { showManualCookieDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, GlassBorderWhite, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Paste Cookies",
                        tint = CyanNeon,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (loadProgressVisible || isVerifying) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = CyanNeon,
                    trackColor = DarkSurface
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
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false

                            // Strip WebView markers so Google Sign-In permits standard login
                            val defaultUa = settings.userAgentString
                            val cleanMobileUa = if (!defaultUa.isNullOrBlank()) {
                                defaultUa.replace("; wv", "")
                                    .replace("Version/4.0 ", "")
                                    .replace("Version/4.0", "")
                                    .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                            } else {
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                            }
                            settings.userAgentString = cleanMobileUa

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onRetrieveVisitorData(newVisitorData: String?) {
                                        if (!newVisitorData.isNullOrBlank() && newVisitorData != "null") {
                                            capturedVisitorData = newVisitorData
                                            val cookies = readYouTubeCookies()
                                            if (hasSessionTokens(cookies)) {
                                                connectAndVerifySession(cookies)
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onRetrieveDataSyncId(newDataSyncId: String?) {
                                        if (!newDataSyncId.isNullOrBlank() && newDataSyncId != "null") {
                                            capturedDataSyncId = newDataSyncId
                                            val cookies = readYouTubeCookies()
                                            if (hasSessionTokens(cookies)) {
                                                connectAndVerifySession(cookies)
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onRetrieveSessionIndex(newSessionIndex: String?) {
                                        newSessionIndex?.toIntOrNull()?.let { idx ->
                                            capturedSessionIndex = idx
                                        }
                                    }
                                },
                                "Android"
                            )

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val targetUri = request?.url ?: return false
                                    val scheme = targetUri.scheme?.lowercase().orEmpty()

                                    // Block external intent:// or market:// app links and stay inside WebView
                                    if (scheme != "http" && scheme != "https") {
                                        view?.loadUrl("https://music.youtube.com")
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    loadProgressVisible = false
                                    CookieManager.getInstance().flush()

                                    if (isAuthenticatedYouTubePage(url)) {
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                try {
                                                    var vd = (window.yt && window.yt.config_ && window.yt.config_.VISITOR_DATA) ||
                                                             (window.ytcfg && window.ytcfg.get && window.ytcfg.get('VISITOR_DATA')) ||
                                                             (window.ytcfg && window.ytcfg.data_ && window.ytcfg.data_.VISITOR_DATA) || '';
                                                    var ds = (window.yt && window.yt.config_ && window.yt.config_.DATASYNC_ID) ||
                                                             (window.ytcfg && window.ytcfg.get && window.ytcfg.get('DATASYNC_ID')) ||
                                                             (window.ytcfg && window.ytcfg.data_ && window.ytcfg.data_.DATASYNC_ID) || '';
                                                    var si = (window.yt && window.yt.config_ && window.yt.config_.SESSION_INDEX) ||
                                                             (window.ytcfg && window.ytcfg.get && window.ytcfg.get('SESSION_INDEX')) || '0';
                                                    if (vd) window.Android.onRetrieveVisitorData(String(vd));
                                                    if (ds) window.Android.onRetrieveDataSyncId(String(ds));
                                                    if (si) window.Android.onRetrieveSessionIndex(String(si));
                                                } catch (e) {}
                                            })();
                                            """.trimIndent(),
                                            null
                                        )

                                        val cookies = readYouTubeCookies()
                                        if (hasSessionTokens(cookies)) {
                                            connectAndVerifySession(cookies)
                                        }
                                    }
                                }
                            }
                            loadUrl(LOGIN_URL)
                        }
                    }
                )

                // Minimalist Success Overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = connectedName != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = DarkSurface,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .border(1.dp, GlassBorderWhite, RoundedCornerShape(24.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = CyanNeon.copy(alpha = 0.16f),
                                modifier = Modifier.size(60.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = CyanNeon,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Account Connected",
                                color = TextPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = connectedName.orEmpty(),
                                color = TextMuted,
                                fontSize = 14.sp
                            )

                            Button(
                                onClick = {
                                    val cookies = readYouTubeCookies()
                                    if (hasSessionTokens(cookies)) {
                                        connectAndVerifySession(cookies)
                                    }
                                    onConnected()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyanNeon,
                                    contentColor = PureBlack
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Start Listening",
                                    fontWeight = FontWeight.SemiBold,
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
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text("Paste YouTube Cookies", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Paste your Cookie header, JSON export, or Netscape cookies.txt from music.youtube.com.",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = manualCookieText,
                            onValueChange = {
                                manualCookieText = it
                                manualCookieError = null
                            },
                            placeholder = { Text("SAPISID=...; SID=...", color = TextMuted) },
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CyanNeon,
                                unfocusedBorderColor = GlassBorderWhite
                            )
                        )
                        if (manualCookieError != null) {
                            Text(manualCookieError.orEmpty(), color = ErrorCrimson, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            try {
                                val normalized = CookieNormalizer.normalize(manualCookieText)
                                showManualCookieDialog = false
                                coroutineScope.launch {
                                    connectAndVerifySession(normalized)
                                }
                            } catch (e: Exception) {
                                manualCookieError = e.message ?: "Invalid cookie format."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanNeon, contentColor = PureBlack),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Connect", fontWeight = FontWeight.SemiBold)
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
