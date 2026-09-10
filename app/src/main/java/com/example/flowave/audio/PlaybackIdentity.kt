package com.example.flowave.audio

import com.example.flowave.data.model.Track
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Stable identity rules shared by UI, persistence and the Media3 data source. */
object PlaybackIdentity {
    private const val ONLINE_PREFIX = "yt_"

    fun sourceId(track: Track): String? = track.sourceId?.takeIf { it.isNotBlank() }
        ?: track.id.removePrefix(ONLINE_PREFIX).takeIf { track.isOnline && it.isNotBlank() }

    fun canonical(track: Track): Track {
        if (!track.isOnline) return track
        val id = sourceId(track) ?: return track
        return track.copy(sourceId = id, mediaUri = "flowave://youtube/$id")
    }

    fun mediaUri(track: Track): String = if (track.isOnline) {
        sourceId(track)?.let { "flowave://youtube/$it" } ?: track.mediaUri
    } else track.mediaUri
}

object OnlinePlaybackPolicy {
    private const val EXPIRY_SAFETY_WINDOW_MS = 5 * 60 * 1000L

    fun isExpired(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = Regex("(?:[?&])expire=(\\d+)").find(url)?.groupValues?.getOrNull(1)
            ?.toLongOrNull()?.times(1000L) ?: return false
        return expiresAt <= nowMs + EXPIRY_SAFETY_WINDOW_MS
    }

    fun retryDelayMs(attempt: Int): Long =
        (1000L * (1L shl attempt.coerceIn(0, 3))).coerceAtMost(8000L)

    fun isRefreshableHttpStatus(status: Int?): Boolean = status == null || status in setOf(
        403, 404, 410, 416, 429, 500, 502, 503, 504
    )

    fun isCancellation(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is kotlinx.coroutines.CancellationException || cause is InterruptedException) return true
            cause = cause.cause
        }
        return false
    }

    fun classifyResolverFailure(error: Throwable): String {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is kotlinx.coroutines.CancellationException, is InterruptedException -> return "cancelled"
                is UnknownHostException -> return "dns_unavailable"
                is SSLException -> return "tls_failure"
                is SocketTimeoutException -> return "timeout"
                is ConnectException -> return "connection_refused"
            }
            cause = cause.cause
        }
        return "resolver_error"
    }

    fun classifyHttpStatus(status: Int): String = when {
        status == 429 -> "rate_limited"
        status in 400..499 -> "http_client_failure"
        status >= 500 -> "http_server_failure"
        else -> "http_failure"
    }

    fun hostCooldownMs(failureClass: String): Long = when (failureClass) {
        "extraction_failure" -> 0L
        "dns_unavailable", "connection_refused", "tls_failure" -> 15 * 60 * 1000L
        "rate_limited" -> 5 * 60 * 1000L
        "timeout", "http_server_failure" -> 2 * 60 * 1000L
        else -> 60 * 1000L
    }
}
