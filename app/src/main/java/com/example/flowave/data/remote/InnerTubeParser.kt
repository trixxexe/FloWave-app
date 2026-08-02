package com.example.flowave.data.remote

import com.example.flowave.data.model.InnerTubeTrack
import org.json.JSONArray
import org.json.JSONObject

object InnerTubeParser {

    fun parseInnerTubeSearchJson(json: JSONObject): List<InnerTubeTrack> {
        val tracks = mutableListOf<InnerTubeTrack>()
        try {
            val contents = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val section = contents.optJSONObject(i)?.optJSONObject("musicShelfRenderer")
                        ?: contents.optJSONObject(i)?.optJSONObject("itemSectionRenderer")
                        ?: continue
                    val items = section.optJSONArray("contents") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                            ?: items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer")
                            ?: items.optJSONObject(j)?.optJSONObject("videoRenderer")
                            ?: items.optJSONObject(j)?.optJSONObject("compactVideoRenderer")
                            ?: continue

                        val videoId = extractVideoId(item)
                        if (videoId.isEmpty()) continue

                        var title: String? = null
                        var artist: String? = null
                        var albumName = "YouTube Music"

                        // Try direct keys first
                        title = extractText(item, "title")
                        artist = extractText(item, "subtitle") ?: extractText(item, "longBylineText")

                        // Try flexColumns for musicResponsiveListItemRenderer
                        val flexColumns = item.optJSONArray("flexColumns")
                        if (flexColumns != null && flexColumns.length() > 0) {
                            // Column 0 is always Title
                            val col0 = flexColumns.optJSONObject(0)
                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            val textObj0 = col0?.optJSONObject("text")
                            if (textObj0 != null) {
                                val t = extractTextFromObj(textObj0)
                                if (!t.isNullOrBlank()) {
                                    title = t
                                }
                            }

                            // Column 1 is always subtitle / artist / album info
                            if (flexColumns.length() > 1) {
                                val col1 = flexColumns.optJSONObject(1)
                                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                val textObj1 = col1?.optJSONObject("text")
                                if (textObj1 != null) {
                                    val runs = textObj1.optJSONArray("runs")
                                    if (runs != null && runs.length() > 0) {
                                        val artistName = runs.optJSONObject(0)?.optString("text")
                                        if (!artistName.isNullOrBlank()) {
                                            artist = artistName
                                        }
                                        for (r in 1 until runs.length()) {
                                            val runText = runs.optJSONObject(r)?.optString("text")
                                            if (!runText.isNullOrBlank() && runText != "•" && !runText.contains(":") && runText.length > 1) {
                                                albumName = runText
                                            }
                                        }
                                    } else {
                                        val simpleText = extractTextFromObj(textObj1)
                                        if (!simpleText.isNullOrBlank()) {
                                            artist = simpleText
                                        }
                                    }
                                }
                            }
                        }

                        val finalTitle = title ?: "Unknown Title"
                        val finalArtist = artist ?: "YouTube Artist"
                        val rawThumbnail = extractThumbnail(item) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        val highResThumbnail = getHighResThumbnail(rawThumbnail, videoId)

                        tracks.add(
                            InnerTubeTrack(
                                id = videoId,
                                title = finalTitle,
                                artist = finalArtist,
                                durationText = "3:30",
                                thumbnailUrl = highResThumbnail,
                                album = albumName
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeParser", "Failed to parse InnerTube search JSON", e)
        }
        return tracks
    }

    fun parseYtInitialData(json: JSONObject): List<InnerTubeTrack> {
        val tracks = mutableListOf<InnerTubeTrack>()
        try {
            val contents = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnSearchResultRenderer")
                ?.optJSONObject("primaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (contents != null) {
                for (i in 0 until contents.length()) {
                    val itemSection = contents.optJSONObject(i)?.optJSONObject("itemSectionRenderer") ?: continue
                    val sectionContents = itemSection.optJSONArray("contents") ?: continue
                    for (j in 0 until sectionContents.length()) {
                        val item = sectionContents.optJSONObject(j) ?: continue
                        val videoRenderer = item.optJSONObject("videoRenderer") ?: continue

                        val videoId = videoRenderer.optString("videoId")
                        if (videoId.isEmpty()) continue

                        val title = extractText(videoRenderer, "title") ?: "Unknown Song"
                        val artist = extractText(videoRenderer, "ownerText") 
                            ?: extractText(videoRenderer, "longBylineText") 
                            ?: "YouTube Music"
                        val durationText = videoRenderer.optJSONObject("lengthText")?.optString("simpleText") ?: "3:30"
                        
                        val thumbnailObj = videoRenderer.optJSONObject("thumbnail")
                        val thumbnails = thumbnailObj?.optJSONArray("thumbnails")
                        val thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                        tracks.add(
                            InnerTubeTrack(
                                id = videoId,
                                title = title,
                                artist = artist,
                                durationText = durationText,
                                thumbnailUrl = thumbnailUrl,
                                album = "YouTube Music"
                            )
                        )
                    }
                }
            }
            if (tracks.isEmpty()) {
                android.util.Log.d("InnerTubeParser", "Standard HTML JSON path returned zero results. Activating deep recursive fallback...")
                val videoRenderers = mutableListOf<JSONObject>()
                findVideoRenderersRecursively(json, videoRenderers)
                for (videoRenderer in videoRenderers) {
                    val videoId = videoRenderer.optString("videoId")
                    if (videoId.isEmpty()) continue

                    val title = extractText(videoRenderer, "title") ?: "Unknown Song"
                    val artist = extractText(videoRenderer, "ownerText") 
                        ?: extractText(videoRenderer, "longBylineText") 
                        ?: "YouTube Music"
                    val durationText = videoRenderer.optJSONObject("lengthText")?.optString("simpleText") ?: "3:30"
                    
                    val thumbnailObj = videoRenderer.optJSONObject("thumbnail")
                    val thumbnails = thumbnailObj?.optJSONArray("thumbnails")
                    val thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
                        ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                    tracks.add(
                        InnerTubeTrack(
                            id = videoId,
                            title = title,
                            artist = artist,
                            durationText = durationText,
                            thumbnailUrl = thumbnailUrl,
                            album = "YouTube Music"
                        )
                    )
                }
                android.util.Log.d("InnerTubeParser", "Deep recursive fallback extracted ${tracks.size} tracks successfully!")
            }
        } catch (e: Exception) {
            android.util.Log.e("InnerTubeParser", "Failed to parse ytInitialData HTML scraped JSON", e)
        }
        return tracks
    }

    private fun findVideoRenderersRecursively(json: Any, results: MutableList<JSONObject>) {
        if (results.size >= 50) return
        when (json) {
            is JSONObject -> {
                if (json.has("videoRenderer")) {
                    json.optJSONObject("videoRenderer")?.let { results.add(it) }
                }
                for (key in json.keys()) {
                    val value = json.opt(key) ?: continue
                    findVideoRenderersRecursively(value, results)
                }
            }
            is JSONArray -> {
                for (i in 0 until json.length()) {
                    val value = json.opt(i) ?: continue
                    findVideoRenderersRecursively(value, results)
                }
            }
        }
    }

    fun extractVideoId(item: JSONObject): String {
        val direct = item.optString("videoId")
        if (direct.isNotEmpty()) return direct

        val playlistItem = item.optJSONObject("playlistItemData")?.optString("videoId")
        if (playlistItem != null && playlistItem.isNotEmpty()) return playlistItem

        val doubleTap = item.optJSONObject("doubleTapCommand")?.optJSONObject("watchEndpoint")?.optString("videoId")
        if (doubleTap != null && doubleTap.isNotEmpty()) return doubleTap

        val overlay = item.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
        if (overlay != null && overlay.isNotEmpty()) return overlay

        val navEndpoint = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
        if (navEndpoint != null && navEndpoint.isNotEmpty()) return navEndpoint

        val onTap = item.optJSONObject("onTap")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
        if (onTap != null && onTap.isNotEmpty()) return onTap

        val titleRuns = item.optJSONObject("title")?.optJSONArray("runs")
        if (titleRuns != null && titleRuns.length() > 0) {
            val titleNav = titleRuns.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
            if (titleNav != null && titleNav.isNotEmpty()) return titleNav
        }

        return ""
    }

    fun extractText(item: JSONObject, key: String): String? {
        val obj = item.optJSONObject(key) ?: return item.optString(key).takeIf { it.isNotEmpty() }
        return extractTextFromObj(obj)
    }

    fun extractTextFromObj(obj: JSONObject): String? {
        val runs = obj.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                val runObj = runs.optJSONObject(i)
                if (runObj != null) {
                    val text = runObj.optString("text")
                    if (text.isNotEmpty()) {
                        sb.append(text)
                    }
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }

        val simpleText = obj.optString("simpleText")
        if (simpleText.isNotEmpty()) return simpleText

        val textDirect = obj.optString("text")
        if (textDirect.isNotEmpty()) return textDirect

        val accLabel = obj.optJSONObject("accessibility")
            ?.optJSONObject("accessibilityData")
            ?.optString("label")
        if (!accLabel.isNullOrEmpty()) return accLabel

        return null
    }

    fun extractThumbnail(item: JSONObject): String? {
        val thumbnails = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: item.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
    }

    fun getHighResThumbnail(url: String, videoId: String): String {
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return if (url.contains("=w")) {
                url.substringBefore("=w") + "=w600-h600-l90-rj"
            } else if (url.contains("-w")) {
                url.substringBefore("-w") + "-w600-h600-l90-rj"
            } else {
                url
            }
        }
        return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
    }
}
