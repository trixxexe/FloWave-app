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
    
    private val activeContinuations = mutableMapOf<String, Continuation<String>>()

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
            val bytes = poTokenU8String.split(",").map { it.toShort().toByte() }.toByteArray()
            val finalPoToken = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            activeContinuations.remove(identifier)?.resume(finalPoToken)
        }

        @JavascriptInterface
        fun onError(errorMsg: String) {
            android.util.Log.e("PoTokenGenerator", "WebView Error: $errorMsg")
        }
    }

    suspend fun generateToken(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) initialize()
        
        // Wait max 10 seconds for initialization if still in progress
        var retries = 0
        while (!isInitialized.get() && retries < 50) {
            kotlinx.coroutines.delay(200)
            retries++
        }
        if (!isInitialized.get()) return@withContext null

        tokenMutex.withLock {
            try {
                suspendCancellableCoroutine<String> { cont ->
                    activeContinuations[videoId] = cont
                    
                    mainHandler.post {
                        val jsString = videoId.replace("\"", "\\\"")
                        val u8IdStr = "new Uint8Array([" + videoId.toByteArray().joinToString(",") { it.toUByte().toString() } + "])"
                        
                        webView?.evaluateJavascript(
                            """
                            try {
                                var poTokenU8 = obtainPoToken(window.webPoSignalOutput, window.integrityToken, $u8IdStr);
                                AndroidBotGuard.onTokenGenerated("$jsString", poTokenU8.join(","));
                            } catch(e) {
                                AndroidBotGuard.onError(e.toString());
                            }
                            """.trimIndent(), null
                        )
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
