package com.example.flowave.downloader

import java.net.URI

enum class DownloadMediaKind { AUDIO, VIDEO }

data class DownloadOptions(
    val kind: DownloadMediaKind = DownloadMediaKind.AUDIO,
    val audioFormat: String = "m4a",
    val audioQuality: String = "0",
    val formatSelector: String? = null
)

data class DownloadMediaInfo(
    val id: String?,
    val webpageUrl: String,
    val title: String,
    val creator: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?
)

object DownloadInput {
    fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun sourceFor(value: String): String = value.trim().let { if (isHttpUrl(it)) it else "ytsearch1:$it" }

    fun safeFileName(value: String, fallback: String = "flowave-download"): String {
        val cleaned = value.replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), " ")
            .replace(Regex("\\s+"), " ").trim().trim('.')
        return cleaned.take(180).ifBlank { fallback }
    }
}
