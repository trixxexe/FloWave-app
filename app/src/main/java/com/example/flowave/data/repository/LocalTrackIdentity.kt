package com.example.flowave.data.repository

import java.security.MessageDigest

/** Pure local-media rules shared by import code and unit tests. */
object LocalTrackIdentity {
    private val supportedExtensions = setOf(
        "mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "3gp", "mid", "midi"
    )

    fun isSupportedAudioName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in supportedExtensions

    fun stableId(uri: String): String = "imported_" + sha256(uri).take(32)

    fun fallbackTitle(displayName: String?, fallback: String = "Imported Track"): String =
        displayName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    fun cleanMetadata(value: String?, fallback: String): String = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
        ?: fallback

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
