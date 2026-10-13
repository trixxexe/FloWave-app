package com.example.flowave

import com.example.flowave.data.remote.InnerTubeRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InnerTubeParserAndSABRTest {

    private val repository = InnerTubeRepository(null)

    @Test
    fun testDirectAudioFormatExtraction() = runBlocking {
        val formats = JSONArray().apply {
            put(JSONObject().apply {
                put("itag", 251)
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("bitrate", 160000)
                put("url", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=123&itag=251")
            })
            put(JSONObject().apply {
                put("itag", 140)
                put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("bitrate", 128000)
                put("url", "https://rr1---sn-abc.googlevideo.com/videoplayback?id=123&itag=140")
            })
        }

        val extracted = repository.parseAudioUrl(formats)
        assertNotNull(extracted)
        assertEquals("https://rr1---sn-abc.googlevideo.com/videoplayback?id=123&itag=251", extracted)
    }

    @Test
    fun testHigherBitrateOpusPreferred() = runBlocking {
        val formats = JSONArray().apply {
            put(JSONObject().apply {
                put("itag", 250)
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("bitrate", 70000)
                put("url", "https://example.com/audio-low")
            })
            put(JSONObject().apply {
                put("itag", 251)
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("bitrate", 160000)
                put("url", "https://example.com/audio-high")
            })
        }

        val extracted = repository.parseAudioUrl(formats)
        assertEquals("https://example.com/audio-high", extracted)
    }

    @Test
    fun testUrlLessOrSABRFormatsReturnNull() = runBlocking {
        // Modern YouTube responses may return formats without 'url' and without 'signatureCipher'
        // representing SABR (Server-Adaptive Bitrate) streaming.
        val formats = JSONArray().apply {
            put(JSONObject().apply {
                put("itag", 251)
                put("mimeType", "audio/webm; codecs=\"opus\"")
                put("bitrate", 160000)
                // NO 'url' and NO 'signatureCipher'
            })
            put(JSONObject().apply {
                put("itag", 140)
                put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                put("bitrate", 128000)
                // NO 'url' and NO 'signatureCipher'
            })
        }

        val extracted = repository.parseAudioUrl(formats)
        // Must return null so the resolver detects SABR and gracefully continues to the embedded yt-dlp resolver!
        assertNull(extracted)
    }

    @Test
    fun testVideoOnlyFormatsIgnored() = runBlocking {
        val formats = JSONArray().apply {
            put(JSONObject().apply {
                put("itag", 137)
                put("mimeType", "video/mp4; codecs=\"avc1.640028\"")
                put("bitrate", 4500000)
                put("url", "https://example.com/video-only")
            })
        }

        val extracted = repository.parseAudioUrl(formats)
        assertNull(extracted)
    }

    @Test
    fun testParsePlayerResponseWithSabrOnlyRejects() = runBlocking {
        val json = JSONObject().apply {
            put("playabilityStatus", JSONObject().apply { put("status", "OK") })
            put("streamingData", JSONObject().apply {
                put("serverAbrStreamingUrl", "https://rr1---sn-abc.googlevideo.com/videoplayback?sabr=1")
                put("adaptiveFormats", JSONArray().apply {
                    put(JSONObject().apply {
                        put("itag", 251)
                        put("mimeType", "audio/webm; codecs=\"opus\"")
                        // No URL
                    })
                })
            })
        }
        val extracted = com.example.flowave.data.remote.InnerTubeParser.parsePlayerResponse(json)
        assertNull(extracted)
    }

    @Test
    fun testParsePlayerResponseHlsManifestFallback() = runBlocking {
        val json = JSONObject().apply {
            put("playabilityStatus", JSONObject().apply { put("status", "OK") })
            put("streamingData", JSONObject().apply {
                put("serverAbrStreamingUrl", "https://rr1---sn-abc.googlevideo.com/videoplayback?sabr=1")
                put("hlsManifestUrl", "https://manifest.googlevideo.com/api/manifest/hls_variant/test.m3u8")
                put("adaptiveFormats", JSONArray())
            })
        }
        val extracted = com.example.flowave.data.remote.InnerTubeParser.parsePlayerResponse(json)
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_variant/test.m3u8", extracted)
    }

    @Test
    fun testPoTokenGeneratorRejectsStubTokens() {
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken(null))
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken(""))
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken("stub"))
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken("stub_token_12345678"))
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken("placeholder_token"))
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken("dummy_token_9999"))
        org.junit.Assert.assertFalse(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken("short"))
        org.junit.Assert.assertTrue(com.example.flowave.data.remote.botguard.PoTokenGenerator.isValidToken("valid_runtime_token_abcdef123456"))
    }
}
