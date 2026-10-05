package com.example.flowave

import com.example.flowave.downloader.SealStyleDownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpOutputParsingTest {

    @Test
    fun testParseGetUrlStdout() {
        val stdout = """
            [youtube] Extracting URL: https://www.youtube.com/watch?v=1fJQCPMd8pc
            [youtube] 1fJQCPMd8pc: Downloading webpage
            [youtube] 1fJQCPMd8pc: Downloading ios player API JSON
            https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1728000000&ei=xyz&ip=1.2.3.4&id=1fJQCPMd8pc&itag=251
        """.trimIndent()

        val resolved = stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }

        assertNotNull(resolved)
        assertTrue(resolved!!.contains("googlevideo.com"))
        assertTrue(resolved.contains("1fJQCPMd8pc"))
    }

    @Test
    fun testParseDurationText() {
        val repo = com.example.flowave.data.remote.InnerTubeRepository(null)
        assertEquals(225000L, repo.parseDurationText("3:45"))
        assertEquals(3750000L, repo.parseDurationText("1:02:30"))
        assertEquals(45000L, repo.parseDurationText("45"))
    }
}
