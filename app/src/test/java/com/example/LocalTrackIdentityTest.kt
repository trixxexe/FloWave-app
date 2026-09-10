package com.example

import com.example.flowave.data.repository.LocalTrackIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTrackIdentityTest {
    @Test
    fun supportedAudioNamesAreFilteredWithoutRejectingUppercaseExtensions() {
        assertTrue(LocalTrackIdentity.isSupportedAudioName("Album/Track.FLAC"))
        assertTrue(LocalTrackIdentity.isSupportedAudioName("track.m4a"))
        assertFalse(LocalTrackIdentity.isSupportedAudioName("cover.jpg"))
    }

    @Test
    fun uriIdentityIsStableAndCollisionResistantForDifferentUris() {
        val first = LocalTrackIdentity.stableId("content://provider/audio/one")
        assertEquals(first, LocalTrackIdentity.stableId("content://provider/audio/one"))
        assertNotEquals(first, LocalTrackIdentity.stableId("content://provider/audio/two"))
        assertTrue(first.startsWith("imported_"))
    }

    @Test
    fun metadataUsesFilenameAndUnknownFallbacks() {
        assertEquals("Track Name", LocalTrackIdentity.fallbackTitle("Track Name.mp3"))
        assertEquals("Imported Track", LocalTrackIdentity.fallbackTitle(null))
        assertEquals("Unknown Artist", LocalTrackIdentity.cleanMetadata("<unknown>", "Unknown Artist"))
        assertEquals("Tagged Artist", LocalTrackIdentity.cleanMetadata(" Tagged Artist ", "Unknown Artist"))
    }
}
