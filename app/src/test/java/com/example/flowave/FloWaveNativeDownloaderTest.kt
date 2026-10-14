package com.example.flowave

import com.example.flowave.audio.FloWaveCacheManager
import com.example.flowave.downloader.DownloadOptions
import com.example.flowave.downloader.FloWaveNativeDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FloWaveNativeDownloaderTest {

    private val context = RuntimeEnvironment.getApplication()
    private val downloader = FloWaveNativeDownloader(context)

    @Test
    fun testDetermineExtensionM4a() {
        val m4aUrl = "https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1728000000&itag=140&mime=audio%2Fmp4"
        val ext = downloader.determineExtension(m4aUrl, DownloadOptions())
        assertEquals("m4a", ext)
    }

    @Test
    fun testDetermineExtensionOpus() {
        val opusUrl = "https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1728000000&itag=251&mime=audio%2Fwebm"
        val ext = downloader.determineExtension(opusUrl, DownloadOptions())
        assertEquals("opus", ext)
    }

    @Test
    fun testDetermineExtensionCustomOption() {
        val genericUrl = "https://rr2---sn-4g5ednkk.googlevideo.com/videoplayback?expire=1728000000"
        val ext = downloader.determineExtension(genericUrl, DownloadOptions(audioFormat = "mp3"))
        assertEquals("mp3", ext)
    }

    @Test
    fun testDetermineExtensionFromMimeType() {
        val genericUrl = "https://example.com/stream"
        assertEquals("m4a", downloader.determineExtension(genericUrl, DownloadOptions(), "audio/mp4"))
        assertEquals("opus", downloader.determineExtension(genericUrl, DownloadOptions(), "audio/webm; codecs=\"opus\""))
        assertEquals("opus", downloader.determineExtension(genericUrl, DownloadOptions(), "audio/webm"))
        assertEquals("webm", downloader.determineExtension(genericUrl, DownloadOptions(), "video/webm"))
    }

    @Test
    fun testFormatSpeed() {
        assertEquals("3.5 MB/s", downloader.formatSpeed(3.5f * 1024 * 1024))
        assertEquals("512 KB/s", downloader.formatSpeed(512f * 1024))
        assertEquals("800 B/s", downloader.formatSpeed(800f))
    }

    @Test
    fun testFormatEta() {
        assertEquals("00:00", downloader.formatEta(0L))
        assertEquals("00:45", downloader.formatEta(45L))
        assertEquals("01:30", downloader.formatEta(90L))
        assertEquals("10:00", downloader.formatEta(600L))
    }

    @Test
    fun testDefaultUserAgentIsAndroidVr() {
        assertEquals(FloWaveCacheManager.ANDROID_VR_USER_AGENT, FloWaveNativeDownloader.DEFAULT_USER_AGENT)
        assertTrue(FloWaveNativeDownloader.DEFAULT_USER_AGENT.contains("com.google.android.apps.youtube.vr.oculus"))
    }
}
