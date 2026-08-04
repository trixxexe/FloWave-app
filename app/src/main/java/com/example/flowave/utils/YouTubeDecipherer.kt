package com.example.flowave.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder

object YouTubeDecipherer {
    private const val TAG = "YouTubeDecipherer"
    
    @Volatile
    private var cachedOps: List<DecipherOp>? = null
    
    @Volatile
    private var lastJsUrl: String? = null

    @Volatile
    private var cachedOpsTimestampMs: Long = 0L

    private const val OPS_CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours

    enum class OpType {
        REVERSE, SLICE, SWAP
    }

    data class DecipherOp(
        val type: OpType,
        val arg: Int
    )

    /**
     * Deciphers a YouTube signature cipher string and returns the fully working direct stream URL.
     */
    suspend fun decipher(cipherText: String, okHttpClient: OkHttpClient): String = withContext(Dispatchers.IO) {
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

        val ops = getDecipherOps(okHttpClient)
        val decipheredSignature = if (ops.isNotEmpty()) {
            applyOps(decodedSignature, ops)
        } else {
            // Fallback to raw signature if parsing base.js fails
            decodedSignature
        }

        val separator = if (decodedUrl.contains("?")) "&" else "?"
        "$decodedUrl$separator$sp=$decipheredSignature"
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
                android.util.Log.d(TAG, "Using cached decipher ops (${(now - cachedOpsTimestampMs) / 60000}min old)")
                return ops
            }
            cachedOps = null
            android.util.Log.d(TAG, "Decipher ops cache expired. Refetching base.js...")
        }

        try {
            val jsUrl = fetchPlayerJsUrl(client) ?: return emptyList()
            android.util.Log.d(TAG, "Fetched player JS URL: $jsUrl")
            
            val request = Request.Builder()
                .url(jsUrl)
                .header("User-Agent", com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP)
                .build()
            
            client.newCall(request).execute().use { response ->
                val jsContent = response.body?.string() ?: ""
                if (response.isSuccessful && jsContent.isNotEmpty()) {
                    val ops = parseDecipherOpsFromJs(jsContent)
                    if (ops.isNotEmpty()) {
                        cachedOps = ops
                        cachedOpsTimestampMs = System.currentTimeMillis()
                        lastJsUrl = jsUrl
                        android.util.Log.d(TAG, "Successfully parsed and cached ${ops.size} decipher operations.")
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
                .url("https://www.youtube.com/embed/dQw4w9WgXcQ")
                .header("User-Agent", com.example.flowave.utils.FloWaveConstants.USER_AGENT_DESKTOP)
                .build()
            
            client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: ""
                if (response.isSuccessful && html.isNotEmpty()) {
                    val regex = Regex("\"jsUrl\"\\s*:\\s*\"([^\"]+base\\.js)\"|src=\"([^\"]+base\\.js)\"|href=\"([^\"]+base\\.js)\"")
                    val match = regex.find(html)
                    var path = match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
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
            // Find the main decipher function definition
            val decipherFuncRegex = Regex("(?s)([a-zA-Z0-9$]+)\\s*=\\s*function\\s*\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\s*\\(\\s*\"\"\\s*\\);?\\s*(.*?)\\s*;?\\s*return\\s+a\\.join\\s*\\(\\s*\"\"\\s*\\)\\s*\\}")
            val funcMatch = decipherFuncRegex.find(js) ?: return emptyList()
            
            val funcBody = funcMatch.groupValues[2]
            
            // Extract helper object name
            val helperObjRegex = Regex("([a-zA-Z0-9$]+)\\.[a-zA-Z0-9$]+\\s*\\(\\s*a\\s*,")
            val helperMatch = helperObjRegex.find(funcBody) ?: return emptyList()
            val helperObjName = helperMatch.groupValues[1]
            
            // Extract the helper object's block of definitions
            val helperDefRegex = Regex("(?s)(?:var|const|let)?\\s*${Regex.escape(helperObjName)}\\s*=\\s*\\{(.*?)\\}\\s*;")
            val helperDefMatch = helperDefRegex.find(js) ?: return emptyList()
            val helperBlock = helperDefMatch.groupValues[1]
            
            // Parse the helper functions and map their names to an operation type
            val methodRegex = Regex("(?s)([a-zA-Z0-9$]+)\\s*:\\s*function\\s*\\(\\s*a\\s*(?:,\\s*b\\s*)?\\)\\s*\\{\\s*(.*?)\\s*\\}")
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
            
            // Parse statements inside funcBody
            val statementRegex = Regex("${Regex.escape(helperObjName)}\\.([a-zA-Z0-9$]+)\\s*\\(\\s*a\\s*(?:,\\s*(\\d+)\\s*)?\\)")
            for (s in statementRegex.findAll(funcBody)) {
                val methodName = s.groupValues[1]
                val argStr = s.groupValues[2]
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
