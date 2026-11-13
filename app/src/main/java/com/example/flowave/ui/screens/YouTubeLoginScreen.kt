package com.example.flowave.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.ytmusic.CookieNormalizer
import com.example.flowave.data.ytmusic.YtMusicAuthManager
import com.example.flowave.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

private fun hasSessionTokens(cookies: String?): Boolean {
    if (cookies.isNullOrBlank()) return false
    val hasSapisid = cookies.contains("SAPISID=") ||
        cookies.contains("__Secure-3PAPISID=") ||
        cookies.contains("__Secure-1PAPISID=") ||
        cookies.contains("APISID=")
    val hasSid = cookies.contains("SID=") ||
        cookies.contains("__Secure-3PSID=") ||
        cookies.contains("__Secure-1PSID=")
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

private data class ExtractedYtConfig(
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val sessionIndex: String = "0"
)

private suspend fun extractYtConfigFromWebView(webView: WebView?): ExtractedYtConfig {
    if (webView == null) return ExtractedYtConfig()
    return withTimeoutOrNull(1800L) {
        suspendCancellableCoroutine { cont ->
            val script = """
                (function() {
                    try {
                        var cfg = (window.ytcfg && window.ytcfg.data_) ? window.ytcfg.data_ : ((window.yt && window.yt.config_) ? window.yt.config_ : {});
                        var vd = (window.ytcfg && window.ytcfg.get) ? window.ytcfg.get('VISITOR_DATA') : (cfg.VISITOR_DATA || '');
                        var ds = (window.ytcfg && window.ytcfg.get) ? window.ytcfg.get('DATASYNC_ID') : (cfg.DATASYNC_ID || '');
                        var si = (window.ytcfg && window.ytcfg.get) ? window.ytcfg.get('SESSION_INDEX') : (cfg.SESSION_INDEX || '0');
                        return JSON.stringify({
                            visitorData: vd || '',
                            dataSyncId: ds || '',
                            sessionIndex: String(si || '0')
                        });
                    } catch (e) {
                        return '{}';
                    }
                })();
            """.trimIndent()
            webView.post {
                webView.evaluateJavascript(script) { raw ->
                    if (!cont.isActive) return@evaluateJavascript
                    try {
                        val unquoted = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\\\", "\\")
                            .orEmpty()
                        val json = JSONObject(unquoted.ifBlank { "{}" })
                        val vd = json.optString("visitorData").takeIf { it.isNotBlank() && it != "null" }
                        val rawDs = json.optString("dataSyncId").takeIf { it.isNotBlank() && it != "null" }
                        val cleanDs = rawDs?.let { ds ->
                            val primary = ds.substringBefore("||")
                            primary.ifBlank { ds.substringAfter("||") }.takeIf { it.isNotBlank() }
                        }
                        val si = json.optString("sessionIndex", "0").ifBlank { "0" }
                        cont.resume(ExtractedYtConfig(visitorData = vd, dataSyncId = cleanDs, sessionIndex = si))
                    } catch (_: Exception) {
                        cont.resume(ExtractedYtConfig())
                    }
                }
            }
        }
    } ?: ExtractedYtConfig()
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

    suspend fun finalizeConnection(rawCookies: String?, webView: WebView?) {
        if (isVerifying || connectedName != null) return
        isVerifying = true
        val cleanCookies = runCatching { CookieNormalizer.normalize(rawCookies) }.getOrDefault(rawCookies.orEmpty())
        val ytConfig = extractYtConfigFromWebView(webView)
        authManager.connect(
            rawCookieHeader = cleanCookies,
            accountName = "Google Account",
            channelHandle = null,
            photoUrl = null,
            visitorData = ytConfig.visitorData,
            dataSyncId = ytConfig.dataSyncId,
            authUserIndex = ytConfig.sessionIndex.toIntOrNull() ?: 0
        )
        withTimeoutOrNull(4000L) { authManager.awaitLoadedConnection() }
        val info = runCatching { innerTubeRepo.fetchAccountInfo() }.getOrNull()
        val displayName = info?.accountName?.takeIf { it.isNotBlank() } ?: "YouTube Music Account"
        if (info != null) {
            authManager.updateAccountIdentity(
                accountName = info.accountName,
                channelHandle = info.channelHandle,
                photoUrl = info.photoUrl,
                visitorData = ytConfig.visitorData,
                dataSyncId = ytConfig.dataSyncId
            )
        }
        connectedName = displayName
        isVerifying = false
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
                    finalizeConnection(cookies, activeWebView)
                    break
                }
            }
            delay(1000L)
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
                    onClick = onBack,
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
                        text = "Ad-free streaming, playlists & liked songs",
                        color = TextMuted,
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
                            val cleanUa = if (!defaultUa.isNullOrBlank()) {
                                defaultUa.replace("; wv", "")
                                    .replace("Version/4.0 ", "")
                                    .replace("Version/4.0", "")
                                    .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                            } else {
                                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                            }
                            settings.userAgentString = cleanUa

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (isVerifying || connectedName != null) return
                                    if (url?.contains("music.youtube.com") == true) {
                                        CookieManager.getInstance().flush()
                                        val cookies = readYouTubeCookies()
                                        if (hasSessionTokens(cookies)) {
                                            coroutineScope.launch {
                                                finalizeConnection(cookies, view)
                                            }
                                        }
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    loadProgressVisible = false
                                    if (isVerifying || connectedName != null) return
                                    CookieManager.getInstance().flush()
                                    val cookies = readYouTubeCookies()
                                    if (url?.contains("music.youtube.com") == true && hasSessionTokens(cookies)) {
                                        coroutineScope.launch {
                                            finalizeConnection(cookies, view)
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
                                onClick = onConnected,
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
                                    finalizeConnection(normalized, activeWebView)
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
