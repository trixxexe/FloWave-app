package com.example

import com.example.flowave.audio.OnlinePlaybackPolicy
import com.example.flowave.audio.PlaybackIdentity
import com.example.flowave.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    @Test
    fun `online tracks always resolve through stable flowave uri`() {
        val track = Track(
            id = "yt_video123",
            title = "Song",
            artist = "Artist",
            mediaUri = "https://rr1---sn.googlevideo.com/audio?expire=1",
            isOnline = true
        )

        val canonical = PlaybackIdentity.canonical(track)
        assertEquals("video123", canonical.sourceId)
        assertEquals("flowave://youtube/video123", canonical.mediaUri)
        assertEquals("flowave://youtube/video123", PlaybackIdentity.mediaUri(canonical))
    }

    @Test
    fun `expiring urls are rejected inside safety window`() {
        val now = 1_700_000_000_000L
        assertTrue(OnlinePlaybackPolicy.isExpired("https://audio.test/file?expire=1700000200", now))
        assertFalse(OnlinePlaybackPolicy.isExpired("https://audio.test/file?expire=1700000600", now))
        assertFalse(OnlinePlaybackPolicy.isExpired("https://audio.test/file", now))
    }

    @Test
    fun `transient http failures are refreshable with bounded backoff`() {
        assertTrue(OnlinePlaybackPolicy.isRefreshableHttpStatus(403))
        assertTrue(OnlinePlaybackPolicy.isRefreshableHttpStatus(429))
        assertTrue(OnlinePlaybackPolicy.isRefreshableHttpStatus(503))
        assertEquals(1000L, OnlinePlaybackPolicy.retryDelayMs(0))
        assertEquals(8000L, OnlinePlaybackPolicy.retryDelayMs(9))
    }
}
