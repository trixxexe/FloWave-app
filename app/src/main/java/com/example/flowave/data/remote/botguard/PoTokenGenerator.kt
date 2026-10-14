package com.example.flowave.data.remote.botguard

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.os.Handler
import android.os.Looper
import android.util.Base64

class PoTokenGenerator(private val context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webView: WebView? = null
    private val isInitialized = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val tokenMutex = Mutex()
    private var webPoSignalOutput: String? = null
    private var integrityToken: String? = null
    
    private val activeContinuations = mutableMapOf<String, Continuation<String?>>()

    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun initialize() {
        if (isInitialized.get()) return
        initMutex.withLock {
            if (isInitialized.get()) return
            withContext(Dispatchers.Main) {
                webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(BotGuardJsInterface(), "AndroidBotGuard")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Once HTML is loaded, kick off creation
                            startBotGuardSequence()
                        }
                    }
                    loadUrl("file:///android_asset/po_token.html")
                }
            }
        }
    }

    private fun startBotGuardSequence() {
        // Run on IO thread for network request
        Thread {
            try {
                val createReq = Request.Builder()
                    .url("https://www.youtube.com/api/jnn/v1/Create")
                    .header("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw")
                    .post("[\"O43z0dpjhgX20SCx4KAo\"]".toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = httpClient.newCall(createReq).execute()
                val body = response.body?.string() ?: throw Exception("Empty Create response")
                
                // Pass challenge data to WebView
                mainHandler.post {
                    webView?.evaluateJavascript(
                        """
                        try {
                            var data = $body;
                            runBotGuard(data).then(function (result) {
                                window.webPoSignalOutput = result.webPoSignalOutput;
                                AndroidBotGuard.onBotguardResult(JSON.stringify(result.botguardResponse));
                            }).catch(function(e) {
                                AndroidBotGuard.onError(e.toString());
                            });
                        } catch(e) { AndroidBotGuard.onError(e.toString()); }
                        """.trimIndent(), null
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    inner class BotGuardJsInterface {
        @JavascriptInterface
        fun onBotguardResult(botguardResponseStr: String) {
            Thread {
                try {
                    val generateReq = Request.Builder()
                        .url("https://www.youtube.com/api/jnn/v1/GenerateIT")
                        .header("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw")
                        .post("[\"O43z0dpjhgX20SCx4KAo\", $botguardResponseStr]".toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = httpClient.newCall(generateReq).execute()
                    val body = response.body?.string() ?: throw Exception("Empty GenerateIT response")
                    
                    val jsonArray = JSONArray(body)
                    val integrityTokenRaw = jsonArray.getString(0)
                    
                    // Decode base64 to byte array string representation for JS
                    val decodedBytes = Base64.decode(integrityTokenRaw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    val u8ArrayStr = "new Uint8Array([" + decodedBytes.joinToString(",") { it.toUByte().toString() } + "])"
                    
                    mainHandler.post {
                        webView?.evaluateJavascript("window.integrityToken = $u8ArrayStr;", null)
                        isInitialized.set(true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        @JavascriptInterface
        fun onTokenGenerated(identifier: String, poTokenU8String: String) {
            try {
                if (poTokenU8String.isBlank()) {
                    activeContinuations.remove(identifier)?.resume(null)
                    return
                }
                val bytes = poTokenU8String.split(",").mapNotNull { it.trim().toShortOrNull()?.toByte() }.toByteArray()
                if (bytes.isEmpty()) {
                    activeContinuations.remove(identifier)?.resume(null)
                    return
                }
                val finalPoToken = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                if (isValidToken(finalPoToken)) {
                    activeContinuations.remove(identifier)?.resume(finalPoToken)
                } else {
                    activeContinuations.remove(identifier)?.resume(null)
                }
            } catch (e: Exception) {
                activeContinuations.remove(identifier)?.resume(null)
            }
        }

        @JavascriptInterface
        fun onError(errorMsg: String) {
            android.util.Log.e("PoTokenGenerator", "WebView Error: $errorMsg")
            synchronized(activeContinuations) {
                val keys = activeContinuations.keys.toList()
                for (k in keys) {
                    activeContinuations.remove(k)?.resume(null)
                }
            }
        }
    }

    suspend fun generateToken(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            initialize()
            // Immediately return null if BotGuard is not initialized yet to avoid blocking stream resolution
            return@withContext null
        }

        tokenMutex.withLock {
            try {
                val token = kotlinx.coroutines.withTimeoutOrNull(800L) {
                    suspendCancellableCoroutine<String?> { cont ->
                        activeContinuations[videoId] = cont
                        cont.invokeOnCancellation { activeContinuations.remove(videoId) }
                        
                        mainHandler.post {
                            val jsString = videoId.replace("\"", "\\\"")
                            val u8IdStr = "new Uint8Array([" + videoId.toByteArray().joinToString(",") { it.toUByte().toString() } + "])"
                            
                            webView?.evaluateJavascript(
                                """
                                try {
                                    if (!window.webPoSignalOutput || !window.integrityToken) {
                                        AndroidBotGuard.onError("BotGuard not initialized");
                                    } else {
                                        var poTokenU8 = obtainPoToken(window.webPoSignalOutput, window.integrityToken, $u8IdStr);
                                        AndroidBotGuard.onTokenGenerated("$jsString", poTokenU8.join(","));
                                    }
                                } catch(e) {
                                    AndroidBotGuard.onError(e.toString());
                                }
                                """.trimIndent(), null
                            )
                        }
                    }
                }
                if (isValidToken(token)) token else null
            } catch (e: Exception) {
                null
            }
        }
    }

    companion object {
        /**
         * Validates that a poToken is a real, non-stub token before attaching to requests.
         */
        fun isValidToken(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val trimmed = token.trim()
            val lower = trimmed.lowercase()
            if (lower.contains("stub") || lower.contains("placeholder") || lower.contains("dummy") || lower.contains("fake") || lower.contains("test")) {
                return false
            }
            if (trimmed.length < 16) return false
            return true
        }
    }
}
