package com.example

import com.example.flowave.data.remote.ResolverStreamSelector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolverStreamSelectionTest {
    @Test
    fun `Invidious prefers highest bitrate audio-only stream over video or muxed formats`() {
        val response = JSONObject(
            """
            {
              "adaptiveFormats": [
                {"url":"https://media.example/video","type":"video/mp4; codecs=\"avc1\"","bitrate":"500000","itag":"18"},
                {"url":"https://media.example/opus","type":"audio/webm; codecs=\"opus\"","bitrate":"160000","itag":"251","clen":"1200000"},
                {"url":"https://media.example/aac","type":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":"128000","itag":"140"}
              ],
              "formatStreams": []
            }
            """.trimIndent()
        )

        val selected = ResolverStreamSelector.selectInvidious(response)

        assertNotNull(selected)
        assertEquals("https://media.example/opus", selected!!.url)
        assertEquals("audio/webm", selected.mimeType)
        assertTrue(selected.audioOnly)
        assertEquals(1200000L, selected.contentLength)
    }

    @Test
    fun `Invidious ignores malformed URLs and returns muxed audio capable fallback`() {
        val response = JSONObject(
            """
            {
              "adaptiveFormats": [
                {"url":"not-a-url","type":"audio/mp4","bitrate":"999999"}
              ],
              "formatStreams": [
                {"url":"https://media.example/muxed.mp4","type":"video/mp4","bitrate":"500000","itag":"18"}
              ]
            }
            """.trimIndent()
        )

        val selected = ResolverStreamSelector.selectInvidious(response)

        assertEquals("https://media.example/muxed.mp4", selected?.url)
        assertFalse(selected!!.audioOnly)
    }

    @Test
    fun `Piped selects audioStreams and rejects video-only or missing streams`() {
        val response = JSONObject(
            """
            {
              "audioStreams": [
                {"url":"https://media.example/video","mimeType":"video/mp4","videoOnly":true,"bitrate":900000},
                {"url":"https://media.example/audio","mimeType":"audio/mp4","codec":"mp4a.40.2","bitrate":128000,"contentLength":9000}
              ],
              "videoStreams": [{"url":"https://media.example/video2","mimeType":"video/mp4"}]
            }
            """.trimIndent()
        )

        val selected = ResolverStreamSelector.selectPiped(response)

        assertEquals("https://media.example/audio", selected?.url)
        assertEquals(9000L, selected?.contentLength)
        assertNull(ResolverStreamSelector.selectPiped(JSONObject("{\"audioStreams\":[]}")))
    }

    @Test
    fun `response content type separates media validation from resolver success`() {
        assertTrue(ResolverStreamSelector.isPlayableResponseContentType("audio/webm; codecs=opus"))
        assertTrue(ResolverStreamSelector.isPlayableResponseContentType("video/mp4"))
        assertTrue(ResolverStreamSelector.isPlayableResponseContentType(null))
        assertFalse(ResolverStreamSelector.isPlayableResponseContentType("application/json"))
        assertFalse(ResolverStreamSelector.isPlayableResponseContentType("text/html"))
    }
}
