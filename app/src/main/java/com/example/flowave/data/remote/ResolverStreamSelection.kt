package com.example.flowave.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Metadata for a fresh media URL selected from a resolver API response. */
data class SelectedResolverStream(
    val url: String,
    val mimeType: String,
    val container: String,
    val codec: String,
    val bitrate: Long,
    val contentLength: Long,
    val itag: String,
    val audioOnly: Boolean
)

/** Pure, deterministic stream selection shared by Piped and Invidious paths. */
object ResolverStreamSelector {
    fun selectInvidious(video: JSONObject): SelectedResolverStream? {
        val adaptive = video.optJSONArray("adaptiveFormats")
        val muxed = video.optJSONArray("formatStreams")
        val audioOnly = collect(adaptive).filter { it.audioOnly }
        return (audioOnly + collect(muxed).filterNot { it.audioOnly })
            .filter { it.audioCapable }
            .maxWithOrNull(compareByDescending<StreamCandidate> { it.audioOnly }.thenByDescending { it.bitrate })
            ?.stream
    }

    fun selectPiped(video: JSONObject): SelectedResolverStream? =
        collect(video.optJSONArray("audioStreams"))
            .filter { it.audioOnly && it.audioCapable }
            .maxByOrNull { it.bitrate }
            ?.stream

    fun isPlayableResponseContentType(contentType: String?): Boolean {
        val normalized = contentType.orEmpty().substringBefore(';').trim().lowercase()
        return normalized.isBlank() ||
            normalized.startsWith("audio/") ||
            normalized.startsWith("video/") ||
            normalized == "application/octet-stream"
    }

    private data class StreamCandidate(
        val stream: SelectedResolverStream,
        val audioCapable: Boolean,
        val audioOnly: Boolean,
        val bitrate: Long
    )

    private fun collect(array: JSONArray?): List<StreamCandidate> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                val parsed = runCatching { URI(url) }.getOrNull()
                if (url.isBlank() || parsed?.scheme?.equals("https", true) != true || parsed.host.isNullOrBlank()) continue

                val mimeType = firstNonBlank(item.optString("mimeType"), item.optString("type"))
                    .substringBefore(';').trim().lowercase()
                val codec = firstNonBlank(item.optString("codec"), item.optString("encoding"))
                val videoOnly = item.optBoolean("videoOnly", false)
                val audioOnly = mimeType.startsWith("audio/") ||
                    (!videoOnly && mimeType.isBlank() && item.has("audioQuality"))
                val audioCapable = audioOnly || (!videoOnly && mimeType.startsWith("video/"))
                if (!audioCapable) continue

                val bitrate = numeric(item.opt("bitrate"))
                add(StreamCandidate(
                    stream = SelectedResolverStream(
                        url = url,
                        mimeType = mimeType,
                        container = firstNonBlank(item.optString("container"), item.optString("format")),
                        codec = codec,
                        bitrate = bitrate,
                        contentLength = numeric(firstNonBlank(item.optString("contentLength"), item.optString("clen"), item.optString("size"))),
                        itag = item.optString("itag"),
                        audioOnly = audioOnly
                    ),
                    audioCapable = audioCapable,
                    audioOnly = audioOnly,
                    bitrate = bitrate
                ))
            }
        }
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun numeric(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        else -> Regex("\\d+").find(value?.toString().orEmpty())?.value?.toLongOrNull() ?: 0L
    }
}
