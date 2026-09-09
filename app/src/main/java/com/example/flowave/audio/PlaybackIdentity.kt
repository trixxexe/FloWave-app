package com.example.flowave.audio

import com.example.flowave.data.model.Track

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
}
