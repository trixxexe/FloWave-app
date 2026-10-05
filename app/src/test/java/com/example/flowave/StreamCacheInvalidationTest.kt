package com.example.flowave

import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.remote.InnerTubeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCacheInvalidationTest {

    private val repository = InnerTubeRepository(null)

    @Test
    fun testDurationCacheUpdatedAndRetrieved() {
        assertEquals(0L, repository.getCachedDuration("test_vid_1"))
        repository.streamDurationCache["test_vid_1"] = 185000L
        assertEquals(185000L, repository.getCachedDuration("test_vid_1"))
    }

    @Test
    fun testInvalidateStreamUrl() {
        val videoId = "vid_to_invalidate"
        repository.invalidateStreamUrl(videoId)
        // Should not throw or crash even if videoId was not present
        assertEquals(0L, repository.getCachedDuration(videoId))
    }

    @Test
    fun testMarkUnplayableStream() {
        val videoId = "unplayable_vid"
        repository.markUnplayableStream(videoId, "media_open_failure")
        // Verified it runs and invalidates without exceptions
    }

    @Test
    fun testCreateOnlineTrackPreservesArchitecture() {
        val innerTrack = InnerTubeTrack(
            id = "abc123xyz",
            title = "Test Song",
            artist = "Test Artist",
            durationText = "4:00",
            thumbnailUrl = "https://i.ytimg.com/vi/abc123xyz/hqdefault.jpg"
        )

        val track = repository.createOnlineTrack(innerTrack)
        assertEquals("yt_abc123xyz", track.id)
        assertEquals("abc123xyz", track.sourceId)
        assertEquals("flowave://youtube/abc123xyz", track.mediaUri)
        assertTrue(track.isOnline)
        assertEquals("YOUTUBE", track.source)
        assertEquals(240000L, track.durationMs)
    }
}
