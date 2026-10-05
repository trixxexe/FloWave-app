package com.example.flowave

import com.example.flowave.audio.OnlinePlaybackPolicy
import com.example.flowave.audio.PlaybackIdentity
import com.example.flowave.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class PlaybackIdentityAndPolicyTest {

    @Test
    fun testPlaybackIdentityCanonical() {
        val track = Track(
            id = "yt_jdb8fzY-oVA",
            title = "Sample Track",
            artist = "Sample Artist",
            album = "Sample Album",
            durationMs = 210000L,
            mediaUri = "flowave://youtube/jdb8fzY-oVA",
            isOnline = true,
            source = "YOUTUBE",
            sourceId = "jdb8fzY-oVA"
        )

        assertEquals("jdb8fzY-oVA", PlaybackIdentity.sourceId(track))
        assertEquals("flowave://youtube/jdb8fzY-oVA", PlaybackIdentity.mediaUri(track))
    }

    @Test
    fun testPlaybackIdentitySourceIdFromRawPrefix() {
        val track = Track(
            id = "yt_1fJQCPMd8pc",
            title = "Track Without SourceId",
            artist = "Artist",
            album = "Album",
            durationMs = 180000L,
            mediaUri = "",
            isOnline = true,
            source = "YOUTUBE"
        )

        assertEquals("1fJQCPMd8pc", PlaybackIdentity.sourceId(track))
        val canonical = PlaybackIdentity.canonical(track)
        assertEquals("flowave://youtube/1fJQCPMd8pc", canonical.mediaUri)
    }

    @Test
    fun testOnlinePlaybackPolicyIsExpired() {
        val nowSec = System.currentTimeMillis() / 1000L
        val expiredUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=${nowSec + 60}&id=123"
        val validUrl = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=${nowSec + 3600}&id=123"

        // Within 5 min safety window -> considered expired
        assertTrue(OnlinePlaybackPolicy.isExpired(expiredUrl))
        // 1 hour away -> not expired
        assertFalse(OnlinePlaybackPolicy.isExpired(validUrl))
    }

    @Test
    fun testPlayableResponseContentTypeValidation() {
        assertTrue(OnlinePlaybackPolicy.isPlayableResponseContentType("audio/webm; codecs=\"opus\""))
        assertTrue(OnlinePlaybackPolicy.isPlayableResponseContentType("audio/mp4"))
        assertTrue(OnlinePlaybackPolicy.isPlayableResponseContentType("video/mp4"))
        assertTrue(OnlinePlaybackPolicy.isPlayableResponseContentType("application/octet-stream"))
        assertTrue(OnlinePlaybackPolicy.isPlayableResponseContentType(null))
        assertTrue(OnlinePlaybackPolicy.isPlayableResponseContentType(""))

        // HTML error pages, JSON bot challenge responses must be rejected!
        assertFalse(OnlinePlaybackPolicy.isPlayableResponseContentType("text/html; charset=utf-8"))
        assertFalse(OnlinePlaybackPolicy.isPlayableResponseContentType("application/json"))
    }

    @Test
    fun testHttpStatusClassification() {
        assertEquals("rate_limited", OnlinePlaybackPolicy.classifyHttpStatus(429))
        assertEquals("http_client_failure", OnlinePlaybackPolicy.classifyHttpStatus(403))
        assertEquals("http_server_failure", OnlinePlaybackPolicy.classifyHttpStatus(502))
    }

    @Test
    fun testResolverFailureClassification() {
        assertEquals("dns_unavailable", OnlinePlaybackPolicy.classifyResolverFailure(UnknownHostException("pipedapi.nosebs.ru")))
        assertEquals("timeout", OnlinePlaybackPolicy.classifyResolverFailure(SocketTimeoutException("Read timed out")))
    }
}
