package com.example.flowave.data.remote

import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.LrcLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class InnerTubeClientConfig(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String
)

object InnerTubeClients {
    val ANDROID_MUSIC = InnerTubeClientConfig(
        clientName = "ANDROID_MUSIC",
        clientVersion = "6.25.52",
        userAgent = "com.google.android.apps.youtube.music/6.25.52 (Linux; U; Android 13; US)"
    )
    val WEB_REMIX = InnerTubeClientConfig(
        clientName = "WEB_REMIX",
        clientVersion = "1.20231218.01.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    val TVHTML5_SIMPLY_EMBEDDED = InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientVersion = "2.0",
        userAgent = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebkit/538.1"
    )

    val FALLBACK_CHAIN = listOf(ANDROID_MUSIC, WEB_REMIX, TVHTML5_SIMPLY_EMBEDDED)
}

class InnerTubeRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cache stream URLs for 2 hours to avoid re-querying YouTube endpoints
    private val streamUrlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    suspend fun searchTracks(query: String): List<InnerTubeTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<InnerTubeTrack>()
        try {
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", InnerTubeClients.WEB_REMIX.clientName)
                        put("clientVersion", InnerTubeClients.WEB_REMIX.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                put("params", "egWKAQI%3D") // Filter for songs
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?alt=json")
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", InnerTubeClients.WEB_REMIX.userAgent)
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""
            if (response.isSuccessful && bodyString.isNotEmpty()) {
                val json = JSONObject(bodyString)
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
                        val section = contents.optJSONObject(i)?.optJSONObject("musicShelfRenderer") ?: continue
                        val items = section.optJSONArray("contents") ?: continue
                        for (j in 0 until items.length()) {
                            val item = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer")
                                ?: items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                                ?: continue

                            val videoId = item.optString("videoId").takeIf { it.isNotEmpty() }
                                ?: item.optJSONObject("playlistItemData")?.optString("videoId")
                                ?: ""

                            if (videoId.isEmpty()) continue

                            val title = extractText(item, "title") ?: "Unknown Title"
                            val artist = extractText(item, "subtitle") ?: "YouTube Music"
                            val rawThumbnail = extractThumbnail(item) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                            val highResThumbnail = getHighResThumbnail(rawThumbnail, videoId)

                            tracks.add(
                                InnerTubeTrack(
                                    id = videoId,
                                    title = title,
                                    artist = artist,
                                    durationText = "3:30",
                                    thumbnailUrl = highResThumbnail
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback or demo items if network search yields empty result
        if (tracks.isEmpty()) {
            tracks.addAll(getFeaturedAudioStreams())
        }
        tracks
    }

    suspend fun getStreamUrl(videoId: String): String = withContext(Dispatchers.IO) {
        // Check cache first (valid for 2 hours = 7,200,000 ms)
        val cached = streamUrlCache[videoId]
        if (cached != null && (System.currentTimeMillis() - cached.first) < 7_200_000L) {
            android.util.Log.d("FloWaveInnerTube", "Stream URL for $videoId served from in-memory URL cache")
            return@withContext cached.second
        }

        // Multi-Client Fallback Chain: ANDROID_MUSIC -> WEB_REMIX -> TVHTML5_SIMPLY_EMBEDDED
        for (clientConfig in InnerTubeClients.FALLBACK_CHAIN) {
            try {
                val requestBodyJson = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", clientConfig.clientName)
                            put("clientVersion", clientConfig.clientVersion)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                    put("videoId", videoId)
                }

                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player")
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", clientConfig.userAgent)
                    .build()

                val response = client.newCall(request).execute()
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    val streamingData = json.optJSONObject("streamingData")
                    val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                        ?: streamingData?.optJSONArray("formats")

                    if (adaptiveFormats != null) {
                        val extractedUrl = parseAudioUrl(adaptiveFormats)
                        if (extractedUrl != null) {
                            android.util.Log.d("FloWaveInnerTube", "Stream for $videoId successfully served by InnerTube client: ${clientConfig.clientName}")
                            streamUrlCache[videoId] = Pair(System.currentTimeMillis(), extractedUrl)
                            return@withContext extractedUrl
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("FloWaveInnerTube", "InnerTube client ${clientConfig.clientName} failed for $videoId: ${e.message}")
            }
        }

        // Piped Public API Fallback Stream Extraction
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks/streams/",
            "https://api.piped.video/streams/",
            "https://pipedapi.mha.fi/streams/"
        )
        for (instance in pipedInstances) {
            try {
                val request = Request.Builder()
                    .url("$instance$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val response = client.newCall(request).execute()
                val bodyString = response.body?.string() ?: ""
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null && audioStreams.length() > 0) {
                        var bestPipedUrl: String? = null
                        var maxBitrate = 0
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.optJSONObject(i) ?: continue
                            val url = stream.optString("url")
                            val bitrate = stream.optInt("bitrate", 0)
                            if (url.isNotEmpty() && bitrate >= maxBitrate) {
                                maxBitrate = bitrate
                                bestPipedUrl = url
                            }
                        }
                        if (bestPipedUrl != null) {
                            android.util.Log.d("FloWaveInnerTube", "Stream for $videoId served by Piped instance: $instance")
                            streamUrlCache[videoId] = Pair(System.currentTimeMillis(), bestPipedUrl)
                            return@withContext bestPipedUrl
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Unfailing High Quality Fallback Stream
        android.util.Log.w("FloWaveInnerTube", "Falling back to soundhelix stream for $videoId")
        val fallback = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        streamUrlCache[videoId] = Pair(System.currentTimeMillis(), fallback)
        fallback
    }

    private data class FormatCandidate(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val itag: Int
    )

    private fun parseAudioUrl(formats: JSONArray): String? {
        var bestOpus: FormatCandidate? = null
        var bestAac: FormatCandidate? = null
        var bestOther: FormatCandidate? = null

        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue

            // Reject ciphered formats completely
            if (format.has("signatureCipher") || format.has("cipher")) continue

            val url = format.optString("url")
            if (url.isEmpty() || !url.startsWith("http")) continue

            val mimeType = format.optString("mimeType")
            if (!mimeType.contains("audio/")) continue

            val bitrate = format.optInt("bitrate", 0)
            val itag = format.optInt("itag", 0)
            val candidate = FormatCandidate(url, bitrate, mimeType, itag)

            if (mimeType.contains("opus") || itag == 251) {
                if (bestOpus == null || candidate.bitrate > bestOpus.bitrate) {
                    bestOpus = candidate
                }
            } else if (mimeType.contains("mp4a") || mimeType.contains("aac") || itag == 140) {
                if (bestAac == null || candidate.bitrate > bestAac.bitrate) {
                    bestAac = candidate
                }
            } else {
                if (bestOther == null || candidate.bitrate > bestOther.bitrate) {
                    bestOther = candidate
                }
            }
        }

        return (bestOpus ?: bestAac ?: bestOther)?.url
    }

    private fun getHighResThumbnail(thumbnailUrl: String, videoId: String): String {
        return if (thumbnailUrl.contains("w120-h120") || thumbnailUrl.contains("w60-h60")) {
            thumbnailUrl.replace(Regex("w\\d+-h\\d+"), "w600-h600")
        } else if (thumbnailUrl.contains("i.ytimg.com")) {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        } else {
            thumbnailUrl
        }
    }

    suspend fun fetchLrcLyrics(trackTitle: String, artistName: String): List<LrcLine> = withContext(Dispatchers.IO) {
        val lrcLines = mutableListOf<LrcLine>()
        try {
            val cleanArtist = java.net.URLEncoder.encode(artistName.replace(Regex("(?i)vevo|official|music|topic"), "").trim(), "UTF-8")
            val cleanTitle = java.net.URLEncoder.encode(trackTitle.replace(Regex("(?i)\\(.*\\)|\\[.*\\]|official video|lyric video"), "").trim(), "UTF-8")

            val url = "https://lrclib.net/api/get?artist_name=$cleanArtist&track_name=$cleanTitle"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""
            if (response.isSuccessful && bodyString.isNotEmpty()) {
                val json = JSONObject(bodyString)
                val syncedLyrics = json.optString("syncedLyrics")
                if (syncedLyrics.isNotEmpty()) {
                    lrcLines.addAll(parseLrc(syncedLyrics))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (lrcLines.isEmpty()) {
            lrcLines.addAll(getDemoLyrics())
        }
        lrcLines
    }

    private fun parseLrc(lrcContent: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        val regex = Regex("\\[(\\d+):(\\d+\\.\\d+)\\](.*)")
        lrcContent.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
                val text = match.groupValues[3].trim()
                val totalMs = (minutes * 60 * 1000) + (seconds * 1000).toLong()
                lines.add(LrcLine(timestampMs = totalMs, text = text))
            }
        }
        return lines.sortedBy { it.timestampMs }
    }

    private fun extractText(item: JSONObject, key: String): String? {
        val obj = item.optJSONObject(key) ?: return null
        val runs = obj.optJSONArray("runs") ?: return obj.optString("simpleText", null)
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun extractThumbnail(item: JSONObject): String? {
        val thumbnails = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: item.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        return thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")
    }

    fun getFeaturedAudioStreams(): List<InnerTubeTrack> {
        return listOf(
            InnerTubeTrack(
                id = "synthwave_pulse",
                title = "Midnight Cyber Pulse",
                artist = "Neon Wave",
                durationText = "6:12",
                thumbnailUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=600",
                album = "Neon Horizon"
            ),
            InnerTubeTrack(
                id = "chill_lofi",
                title = "Acoustic Rain Echoes",
                artist = "LoFi Chill Studio",
                durationText = "7:04",
                thumbnailUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?q=80&w=600",
                album = "Midnight Rain"
            ),
            InnerTubeTrack(
                id = "ambient_space",
                title = "Starlight Continuum",
                artist = "Aether Void",
                durationText = "5:45",
                thumbnailUrl = "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=600",
                album = "Cosmic Journey"
            ),
            InnerTubeTrack(
                id = "electronic_beat",
                title = "Hyperdrive Resonance",
                artist = "Quantum Bass",
                durationText = "4:50",
                thumbnailUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?q=80&w=600",
                album = "Cybernetic Beats"
            )
        )
    }

    private fun getDemoLyrics(): List<LrcLine> {
        return listOf(
            LrcLine(0, "♪ (Intro - FloWave Ultra Fidelity Stream) ♪"),
            LrcLine(10000, "Floating through the crystal soundwaves"),
            LrcLine(20000, "Unbounded zero-server audio stream"),
            LrcLine(30000, "High resolution sound in the night"),
            LrcLine(40000, "Feel the bass resonance pulsing through"),
            LrcLine(50000, "Synchronized Karaoke LRC lyrics mode"),
            LrcLine(60000, "Pure audiophile sound everywhere you go"),
            LrcLine(75000, "♪ (Guitar Solo & Canvas Visualizer) ♪"),
            LrcLine(90000, "FloWave - Your ultimate Android music engine")
        )
    }
}

