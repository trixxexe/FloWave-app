package com.example.flowave.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URI
import java.util.concurrent.TimeUnit

enum class ResolverType { PIPED, INVIDIOUS }

data class ResolverCandidate(
    val type: ResolverType,
    val host: String,
    val source: String,
    val lastSuccessMs: Long = 0L,
    val lastFailureMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val failureClass: String? = null,
    val cooldownUntilMs: Long = 0L,
    val recentLatencyMs: Long = Long.MAX_VALUE,
    val validationMs: Long = 0L
) {
    val key: String get() = "${type.name}|$host"

    fun endpoint(): String = when (type) {
        ResolverType.PIPED -> "https://$host/streams/"
        ResolverType.INVIDIOUS -> "https://$host/api/v1/latest_version?id="
    }
}

/** Thread-safe, persistence-friendly health registry for third-party resolvers. */
class ResolverPool {
    private val candidates = LinkedHashMap<String, ResolverCandidate>()

    @Synchronized
    fun upsert(candidate: ResolverCandidate) {
        val normalized = candidate.copy(host = normalizeHost(candidate.host) ?: return)
        val old = candidates[normalized.key]
        candidates[normalized.key] = if (old == null) normalized else normalized.copy(
            lastSuccessMs = maxOf(old.lastSuccessMs, normalized.lastSuccessMs),
            lastFailureMs = maxOf(old.lastFailureMs, normalized.lastFailureMs),
            consecutiveFailures = maxOf(old.consecutiveFailures, normalized.consecutiveFailures),
            failureClass = normalized.failureClass ?: old.failureClass,
            cooldownUntilMs = maxOf(old.cooldownUntilMs, normalized.cooldownUntilMs),
            recentLatencyMs = minOf(old.recentLatencyMs, normalized.recentLatencyMs),
            validationMs = maxOf(old.validationMs, normalized.validationMs)
        )
    }

    @Synchronized
    fun seed(type: ResolverType, endpoints: List<String>, source: String, nowMs: Long) {
        endpoints.forEach { endpoint ->
            normalizeHost(endpoint)?.let { host ->
                upsert(ResolverCandidate(type = type, host = host, source = source, validationMs = nowMs))
            }
        }
    }

    @Synchronized
    fun ranked(type: ResolverType, nowMs: Long): List<ResolverCandidate> = candidates.values
        .filter { it.type == type && it.cooldownUntilMs <= nowMs }
        .sortedWith(
            compareByDescending<ResolverCandidate> { it.lastSuccessMs > 0L }
                .thenBy { it.consecutiveFailures }
                .thenBy { it.recentLatencyMs }
                .thenByDescending { it.validationMs }
        )

    @Synchronized
    fun all(): List<ResolverCandidate> = candidates.values.toList()

    @Synchronized
    fun markSuccess(key: String, latencyMs: Long, nowMs: Long) {
        val candidate = candidates[key] ?: return
        candidates[key] = candidate.copy(
            lastSuccessMs = nowMs,
            consecutiveFailures = 0,
            failureClass = null,
            cooldownUntilMs = 0L,
            recentLatencyMs = latencyMs.coerceAtLeast(0L)
        )
    }

    @Synchronized
    fun markFailure(key: String, failureClass: String, cooldownMs: Long, nowMs: Long) {
        val candidate = candidates[key] ?: return
        candidates[key] = candidate.copy(
            lastFailureMs = nowMs,
            consecutiveFailures = candidate.consecutiveFailures + 1,
            failureClass = failureClass,
            cooldownUntilMs = nowMs + cooldownMs
        )
    }

    @Synchronized
    fun serialize(): String = candidates.values.joinToString("\n") { candidate ->
        listOf(
            candidate.type.name,
            candidate.host,
            candidate.source,
            candidate.lastSuccessMs,
            candidate.lastFailureMs,
            candidate.consecutiveFailures,
            candidate.failureClass.orEmpty(),
            candidate.cooldownUntilMs,
            candidate.recentLatencyMs,
            candidate.validationMs
        ).joinToString("|") { it.toString().replace("|", "") }
    }

    @Synchronized
    fun restore(serialized: String) {
        serialized.lineSequence().forEach { line ->
            val fields = line.split("|")
            if (fields.size != 10) return@forEach
            runCatching {
                upsert(ResolverCandidate(
                    type = ResolverType.valueOf(fields[0]),
                    host = fields[1],
                    source = fields[2],
                    lastSuccessMs = fields[3].toLong(),
                    lastFailureMs = fields[4].toLong(),
                    consecutiveFailures = fields[5].toInt(),
                    failureClass = fields[6].ifBlank { null },
                    cooldownUntilMs = fields[7].toLong(),
                    recentLatencyMs = fields[8].toLong(),
                    validationMs = fields[9].toLong()
                ))
            }
        }
    }

    companion object {
        fun normalizeHost(value: String): String? {
            val raw = value.trim().removeSuffix("/")
            val uri = runCatching { URI(if (raw.contains("://")) raw else "https://$raw") }.getOrNull()
            val host = uri?.host?.lowercase()?.removeSuffix(".") ?: return null
            if (uri.scheme != "https" || uri.userInfo != null || host.isBlank() || host.contains("@")) return null
            return host
        }
    }
}

class ResolverPoolPersistence(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("flowave_resolver_pool", Context.MODE_PRIVATE)

    fun load(): String = preferences.getString("pool", "").orEmpty()

    fun save(serialized: String) {
        preferences.edit().putString("pool", serialized).apply()
    }
}

object InvidiousRegistryParser {
    fun parse(json: String, source: String = "invidious_registry"): List<ResolverCandidate> {
        val result = mutableListOf<ResolverCandidate>()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        for (index in 0 until array.length()) {
            val item = array.optJSONArray(index) ?: continue
            val host = item.optString(0)
            val metadata = item.optJSONObject(1) ?: continue
            val uri = metadata.optString("uri")
            val monitor = metadata.optJSONObject("monitor") ?: continue
            val ssl = monitor.optJSONObject("ssl") ?: continue
            if (metadata.optString("type") != "https" ||
                !monitor.optBoolean("published", false) ||
                monitor.optBoolean("down", true) ||
                monitor.optInt("last_status", 0) !in 200..299 ||
                monitor.optDouble("uptime", 0.0) < 90.0 ||
                !ssl.optBoolean("valid", false)
            ) continue
            val normalized = ResolverPool.normalizeHost(uri) ?: ResolverPool.normalizeHost(host) ?: continue
            result += ResolverCandidate(ResolverType.INVIDIOUS, normalized, source)
        }
        return result.distinctBy { it.key }
    }
}

class InvidiousInstanceDiscovery(private val baseClient: OkHttpClient) {
    private val discoveryClient = baseClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun discover(): List<ResolverCandidate> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.invidious.io/instances.json")
            .header("User-Agent", "FloWave/2.0 (Android)")
            .build()
        discoveryClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.source()?.let { source ->
                source.readUtf8(512 * 1024L)
            } ?: return@withContext emptyList()
            InvidiousRegistryParser.parse(body)
        }
    }

    suspend fun validate(candidate: ResolverCandidate): Long? = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val request = Request.Builder()
            .url("https://${candidate.host}/api/v1/stats")
            .get()
            .header("User-Agent", "FloWave/2.0 (Android)")
            .build()
        discoveryClient.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code in 300..399) {
                (System.nanoTime() - startedAt) / 1_000_000L
            } else {
                null
            }
        }
    }
}
