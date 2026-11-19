package com.example.flowave.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.net.URLDecoder
import java.net.URLEncoder

object YouTubeDecipherer {
    private const val TAG = "YouTubeDecipherer"
    private const val DEFAULT_VIDEO_ID = "dQw4w9WgXcQ"

    @Volatile
    private var newPipeInitialized: Boolean = false

    @Volatile
    private var cachedOps: List<DecipherOp>? = null

    @Volatile
    private var lastJsUrl: String? = null

    @Volatile
    private var cachedOpsTimestampMs: Long = 0L

    @Volatile
    private var cachedSignatureTimestamp: Int? = null

    private const val OPS_CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours

    private class NewPipeDownloaderImpl(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
            val httpMethod = request.httpMethod()
            val url = request.url()
            val headers = request.headers()
            val dataToSend = request.dataToSend()

            val requestBuilder = Request.Builder()
                .url(url)
                .method(httpMethod, dataToSend?.toRequestBody(null))
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)

            headers.forEach { (headerName, headerValueList) ->
                if (headerValueList.size > 1) {
                    requestBuilder.removeHeader(headerName)
                    headerValueList.forEach { headerValue ->
                        requestBuilder.addHeader(headerName, headerValue)
                    }
                } else if (headerValueList.size == 1) {
                    requestBuilder.header(headerName, headerValueList[0])
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val responseBodyToReturn = response.body?.string()
            val latestUrl = response.request.url.toString()
            return org.schabi.newpipe.extractor.downloader.Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBodyToReturn,
                latestUrl
            )
        }
    }

    @Synchronized
    fun ensureNewPipeInitialized(okHttpClient: OkHttpClient? = null) {
        if (!newPipeInitialized) {
            runCatching {
                val client = okHttpClient ?: OkHttpClient()
                NewPipe.init(NewPipeDownloaderImpl(client))
                newPipeInitialized = true
            }
        }
    }

    /**
     * Returns dynamically fetched or calculated signatureTimestamp for YouTube player requests.
     * In YouTube, signatureTimestamp represents days since Unix epoch (minus 1 for safety margin).
     */
    fun getSignatureTimestamp(): Int {
        cachedSignatureTimestamp?.let { return it }
        return ((System.currentTimeMillis() / 86_400_000L) - 1L).toInt()
    }

    suspend fun ensureTimestampLoaded(
        okHttpClient: OkHttpClient? = null,
        videoId: String = DEFAULT_VIDEO_ID
    ): Int = withContext(Dispatchers.IO) {
        cachedSignatureTimestamp?.let { return@withContext it }
        val client = okHttpClient ?: OkHttpClient()
        ensureNewPipeInitialized(client)

        val newPipeSts = runCatching {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId.ifBlank { DEFAULT_VIDEO_ID })
        }.getOrNull()
        if (newPipeSts != null && newPipeSts > 18000) {
            cachedSignatureTimestamp = newPipeSts
            return@withContext newPipeSts
        }

        runCatching { getDecipherOps(client) }
        getSignatureTimestamp()
    }

    /**
     * Deobfuscates the YouTube `&n=` throttling parameter using NewPipeExtractor's JS engine
     * (matching ArchiveTune / Metrolist / OuterTune's NewPipeUtils).
     */
    fun deobfuscateThrottlingParameter(
        url: String,
        videoId: String = DEFAULT_VIDEO_ID,
        okHttpClient: OkHttpClient? = null
    ): String {
        if (url.isBlank() || (!url.contains("&n=") && !url.contains("?n="))) {
            return url
        }
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".example.com") || host == "example.com") {
            return url
        }
        ensureNewPipeInitialized(okHttpClient)
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId.ifBlank { DEFAULT_VIDEO_ID },
                url
            )
        }.getOrDefault(url)
    }

    enum class OpType {
        REVERSE, SLICE, SWAP
    }

    data class DecipherOp(
        val type: OpType,
        val arg: Int
    )

    /**
     * Deciphers a YouTube signature cipher string and deobfuscates the `&n=` throttling parameter
     * to return a fully working direct stream URL.
     */
    suspend fun decipher(
        cipherText: String,
        okHttpClient: OkHttpClient? = null,
        videoId: String = DEFAULT_VIDEO_ID
    ): String = withContext(Dispatchers.IO) {
        val client = okHttpClient ?: OkHttpClient()
        val params = parseQueryString(cipherText)
        val s = params["s"] ?: return@withContext ""
        val url = params["url"] ?: return@withContext ""
        val sp = params["sp"] ?: "sig"

        val decodedUrl = try {
            URLDecoder.decode(url, "UTF-8")
        } catch (e: Exception) {
            url
        }

        val decodedSignature = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

        ensureNewPipeInitialized(client)
        val targetVideoId = videoId.ifBlank { DEFAULT_VIDEO_ID }

        // 1. Primary: NewPipeExtractor YoutubeJavaScriptPlayerManager (exact mechanism from Metrolist / ArchiveTune)
        val newPipeSig = runCatching {
            YoutubeJavaScriptPlayerManager.deobfuscateSignature(targetVideoId, decodedSignature)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val decipheredSignature = if (newPipeSig != null) {
            newPipeSig
        } else {
            // 2. Secondary fallback: local JS regex parser
            val ops = getDecipherOps(client)
            if (ops.isNotEmpty()) {
                applyOps(decodedSignature, ops)
            } else {
                // If deciphering completely failed on a real googlevideo URL, reject rather than returning an invalid signature
                if (decodedUrl.contains("googlevideo.com")) {
                    return@withContext ""
                }
                decodedSignature
            }
        }

        val encodedSig = try {
            URLEncoder.encode(decipheredSignature, "UTF-8")
        } catch (_: Exception) {
            decipheredSignature
        }
        val separator = if (decodedUrl.contains("?")) "&" else "?"
        val signedUrl = "$decodedUrl$separator$sp=$encodedSig"

        deobfuscateThrottlingParameter(signedUrl, targetVideoId, client)
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                result[key] = value
            }
        }
        return result
    }

    private suspend fun getDecipherOps(client: OkHttpClient): List<DecipherOp> {
        val now = System.currentTimeMillis()
        cachedOps?.let { ops ->
            if (now - cachedOpsTimestampMs < OPS_CACHE_TTL_MS) {
                return ops
            }
            cachedOps = null
        }

        try {
            val jsUrl = fetchPlayerJsUrl(client) ?: return emptyList()
            val request = Request.Builder()
                .url(jsUrl)
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                .build()

            client.newCall(request).execute().use { response ->
                val jsContent = response.body?.string() ?: ""
                if (response.isSuccessful && jsContent.isNotEmpty()) {
                    val stsMatch = Regex("""(?:signatureTimestamp|sts)\s*[:=]\s*(\d{5})""").find(jsContent)
                    stsMatch?.groupValues?.get(1)?.toIntOrNull()?.let {
                        cachedSignatureTimestamp = it
                    }
                    val ops = parseDecipherOpsFromJs(jsContent)
                    if (ops.isNotEmpty()) {
                        cachedOps = ops
                        cachedOpsTimestampMs = System.currentTimeMillis()
                        lastJsUrl = jsUrl
                        return ops
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error parsing decipher operations: ${e.message}", e)
        }
        return emptyList()
    }

    private fun fetchPlayerJsUrl(client: OkHttpClient): String? {
        try {
            val request = Request.Builder()
                .url("https://www.youtube.com/iframe_api")
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val versionMatch = Regex("""\\?/s\\?/player\\?/([a-zA-Z0-9_-]{8,})\\?/""").find(body)
                val playerHash = versionMatch?.groupValues?.getOrNull(1)
                if (!playerHash.isNullOrBlank()) {
                    return "https://www.youtube.com/s/player/$playerHash/player_ias.vflset/en_US/base.js"
                }
            }
        } catch (_: Exception) {
        }

        try {
            val request = Request.Builder()
                .url("https://www.youtube.com/embed/dQw4w9WgXcQ")
                .header("User-Agent", FloWaveConstants.USER_AGENT_DESKTOP)
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: ""
                if (response.isSuccessful && html.isNotEmpty()) {
                    val stsMatch = Regex("""(?:"sts"|signatureTimestamp)\s*:\s*(\d{5})""").find(html)
                    stsMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                        cachedSignatureTimestamp = it
                    }

                    val regex = Regex("\"jsUrl\"\\s*:\\s*\"([^\"]+base\\.js)\"|src=\"([^\"]+base\\.js)\"|href=\"([^\"]+base\\.js)\"")
                    val match = regex.find(html)
                    val path = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                        ?: match?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
                        ?: match?.groupValues?.get(3)

                    if (!path.isNullOrBlank()) {
                        if (path.startsWith("//")) {
                            return "https:$path"
                        } else if (path.startsWith("/")) {
                            return "https://www.youtube.com$path"
                        }
                        return path
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch player JS URL: ${e.message}")
        }
        return "https://www.youtube.com/s/player/89669538/player_ias.vflset/en_US/base.js"
    }

    fun parseDecipherOpsFromJs(js: String): List<DecipherOp> {
        val ops = mutableListOf<DecipherOp>()
        try {
            val decipherFuncRegex = Regex("(?s)([a-zA-Z0-9$]+)\\s*=\\s*function\\s*\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\s*\\(\\s*\"\"\\s*\\);?\\s*(.*?)\\s*;?\\s*return\\s+a\\.join\\s*\\(\\s*\"\"\\s*\\)\\s*\\}")
            val funcMatch = decipherFuncRegex.find(js) ?: return emptyList()

            val funcBody = funcMatch.groupValues[2]

            val helperObjRegex = Regex("([a-zA-Z0-9$]+)(?:\\.[a-zA-Z0-9$]+|\\[\"[a-zA-Z0-9$]+\"\\])\\s*\\(\\s*a\\s*,")
            val helperMatch = helperObjRegex.find(funcBody) ?: return emptyList()
            val helperObjName = helperMatch.groupValues[1]

            val helperDefRegex = Regex("(?s)(?:var|const|let)?\\s*${Regex.escape(helperObjName)}\\s*=\\s*\\{(.*?)\\}\\s*;")
            val helperDefMatch = helperDefRegex.find(js) ?: return emptyList()
            val helperBlock = helperDefMatch.groupValues[1]

            val methodRegex = Regex("(?s)\"?([a-zA-Z0-9$]+)\"?\\s*:\\s*function\\s*\\(\\s*a\\s*(?:,\\s*b\\s*)?\\)\\s*\\{\\s*(.*?)\\s*\\}")
            val methodMap = mutableMapOf<String, OpType>()

            for (m in methodRegex.findAll(helperBlock)) {
                val name = m.groupValues[1]
                val body = m.groupValues[2]

                val type = when {
                    body.contains("reverse") -> OpType.REVERSE
                    body.contains("splice") || body.contains("slice") -> OpType.SLICE
                    body.contains("var c") || body.contains("a[0]") || body.contains("a[b%a.length]") -> OpType.SWAP
                    else -> null
                }
                if (type != null) {
                    methodMap[name] = type
                }
            }

            val statementRegex = Regex("${Regex.escape(helperObjName)}(?:\\.([a-zA-Z0-9$]+)|\\[\"([a-zA-Z0-9$]+)\"\\])\\s*\\(\\s*a\\s*(?:,\\s*(\\d+)\\s*)?\\)")
            for (s in statementRegex.findAll(funcBody)) {
                val methodName = s.groupValues[1].ifEmpty { s.groupValues[2] }
                val argStr = s.groupValues[3]
                val arg = argStr.toIntOrNull() ?: 0
                val type = methodMap[methodName] ?: continue
                ops.add(DecipherOp(type, arg))
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Regex parsing of player JS failed: ${e.message}", e)
        }
        return ops
    }

    private fun applyOps(s: String, ops: List<DecipherOp>): String {
        val chars = s.toCharArray()
        var list = chars.toMutableList()
        for (op in ops) {
            when (op.type) {
                OpType.REVERSE -> {
                    list.reverse()
                }
                OpType.SLICE -> {
                    if (op.arg in 1..list.size) {
                        list = list.subList(op.arg, list.size)
                    }
                }
                OpType.SWAP -> {
                    if (list.isNotEmpty()) {
                        val index = op.arg % list.size
                        val temp = list[0]
                        list[0] = list[index]
                        list[index] = temp
                    }
                }
            }
        }
        return list.joinToString("")
    }
}

