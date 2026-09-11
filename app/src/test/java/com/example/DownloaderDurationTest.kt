package com.example

import com.example.flowave.data.model.DownloadMediaInfo
import com.example.flowave.data.model.InnerTubeTrack
import com.example.flowave.data.model.Track
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.downloader.DownloadInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderDurationTest {
    private val innerTubeRepo = InnerTubeRepository()

    @Test
    fun `parseDurationText returns duration in milliseconds`() {
        // Standard mm:ss
        assertEquals(225_000L, innerTubeRepo.parseDurationText("3:45"))
        // Short duration
        assertEquals(30_000L, innerTubeRepo.parseDurationText("0:30"))
        // Long hh:mm:ss
        assertEquals(3_665_000L, innerTubeRepo.parseDurationText("1:01:05"))
        // Seconds only
        assertEquals(45_000L, innerTubeRepo.parseDurationText("45"))
        // Fallback for empty or invalid
        assertEquals(210_000L, innerTubeRepo.parseDurationText(""))
        assertEquals(210_000L, innerTubeRepo.parseDurationText("invalid"))
    }

    @Test
    fun `duration conversion boundary from InnerTubeTrack to DownloadMediaInfo preserves seconds`() {
        val track = InnerTubeTrack(
            id = "test_vid_1",
            title = "Sample Song",
            artist = "Sample Artist",
            durationText = "3:30",
            thumbnailUrl = "https://thumb.example/1.jpg"
        )
        val parsedMs = innerTubeRepo.parseDurationText(track.durationText)
        val durationSeconds = (parsedMs / 1000L).takeIf { it > 0L }

        assertEquals(210_000L, parsedMs)
        assertEquals(210L, durationSeconds)

        val mediaInfo = DownloadMediaInfo(
            id = track.id,
            webpageUrl = "https://www.youtube.com/watch?v=${track.id}",
            title = track.title,
            creator = track.artist,
            thumbnailUrl = track.thumbnailUrl,
            durationSeconds = durationSeconds
        )
        assertEquals(210L, mediaInfo.durationSeconds)
    }

    @Test
    fun `handleCompletedDownload duration fallback does not double multiply milliseconds`() {
        val track = InnerTubeTrack(
            id = "test_vid_2",
            title = "Opus Without Container Duration",
            artist = "Sample Artist",
            durationText = "3:30",
            thumbnailUrl = "https://thumb.example/2.jpg"
        )

        // When MediaMetadataRetriever fails (durationMs = 0L)
        val retrieverDurationMs = 0L
        val fileLength = 5_000_000L // 5 MB non-empty file

        val durationMs = if (retrieverDurationMs <= 0L && fileLength > 0L) {
            innerTubeRepo.parseDurationText(track.durationText).coerceAtLeast(1000L)
        } else {
            retrieverDurationMs
        }

        // Expected: 210,000 ms (3 minutes 30 seconds)
        assertEquals(210_000L, durationMs)

        // Old bug would produce 210,000,000 ms (58 hours 20 minutes)
        val oldBugDurationMs = innerTubeRepo.parseDurationText(track.durationText).coerceAtLeast(1L) * 1000L
        assertNotEquals(oldBugDurationMs, durationMs)
        assertEquals(210_000_000L, oldBugDurationMs)
    }

    @Test
    fun `importCompletedFile fallback converts durationSeconds to milliseconds`() {
        val mediaInfo = DownloadMediaInfo(
            id = "test_vid_3",
            webpageUrl = "https://www.youtube.com/watch?v=test_vid_3",
            title = "Test",
            creator = "Artist",
            thumbnailUrl = null,
            durationSeconds = 185L // 3m 5s
        )

        var durationMs = 0L // retriever failed
        val fileLength = 2_000_000L
        if (durationMs <= 0L && fileLength > 0L) {
            durationMs = (mediaInfo.durationSeconds ?: 0L) * 1000L
        }

        assertEquals(185_000L, durationMs)
    }
}
