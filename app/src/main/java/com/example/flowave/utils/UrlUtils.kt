package com.example.flowave.utils

object UrlUtils {
    fun extractVideoId(url: String): String? {
        val cleanUrl = url.trim()
        val patterns = listOf(
            Regex("youtube\\.com/watch\\?v=([^&]+)"),
            Regex("youtu\\.be/([^?&]+)"),
            Regex("music\\.youtube\\.com/watch\\?v=([^&]+)"),
            Regex("youtube\\.com/shorts/([^?&]+)"),
            Regex("youtube\\.com/embed/([^?&]+)"),
            Regex("watch\\?v=([^&]+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(cleanUrl)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        // Fallback: If it's a 11-character alphanumeric string, treat it as a video ID directly
        if (cleanUrl.length == 11 && cleanUrl.matches(Regex("[a-zA-Z0-9_-]{11}"))) {
            return cleanUrl
        }
        return null
    }
}
