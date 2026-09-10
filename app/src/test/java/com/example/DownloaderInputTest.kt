package com.example

import com.example.flowave.downloader.DownloadInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderInputTest {
    @Test
    fun routes_urls_and_keywords_without_confusing_search_input_for_a_url() {
        assertTrue(DownloadInput.isHttpUrl("https://www.youtube.com/watch?v=abc"))
        assertFalse(DownloadInput.isHttpUrl("https://"))
        assertEquals("ytsearch1:ambient focus", DownloadInput.sourceFor("ambient focus"))
        assertEquals("https://example.test/video", DownloadInput.sourceFor("https://example.test/video"))
    }

    @Test
    fun sanitizes_path_separators_and_control_characters() {
        assertEquals("A B C", DownloadInput.safeFileName("A:/B\\C"))
        assertEquals("flowave-download", DownloadInput.safeFileName("..."))
    }
}
