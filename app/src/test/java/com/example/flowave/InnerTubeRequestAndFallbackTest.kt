package com.example.flowave

import com.example.flowave.data.remote.InnerTubeClients
import com.example.flowave.utils.YouTubeDecipherer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeRequestAndFallbackTest {

    @Test
    fun testInnerTubeClientsFallbackChainOrdering() {
        val chain = InnerTubeClients.FALLBACK_CHAIN
        assertEquals(4, chain.size)
        // Prioritized chain: IOS -> VISIONOS -> ANDROID_VR -> TVHTML5_SIMPLY_EMBEDDED_PLAYER
        assertEquals("IOS", chain[0].clientName)
        assertEquals("VISIONOS", chain[1].clientName)
        assertEquals("ANDROID_VR", chain[2].clientName)
        assertEquals("TVHTML5_SIMPLY_EMBEDDED_PLAYER", chain[3].clientName)
    }

    @Test
    fun testDynamicSignatureTimestamp() {
        val sts = YouTubeDecipherer.getSignatureTimestamp()
        // Day number in 2026 is > 20000 and <= 25000
        assertTrue("Signature timestamp should be a realistic day number (> 20000): $sts", sts > 20000)
    }

    @Test
    fun testCpnGeneration() {
        val charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val cpn = (1..16).map { charset.random() }.joinToString("")
        assertEquals(16, cpn.length)
        assertTrue(cpn.all { it in charset })
    }

    @Test
    fun testClientAwareUserAgents() {
        val ios = InnerTubeClients.IOS
        assertTrue(ios.userAgent.contains("com.google.ios.youtube") || ios.userAgent.contains("iPhone"))

        val visionos = InnerTubeClients.VISIONOS
        assertTrue(visionos.userAgent.contains("Mozilla") || visionos.userAgent.contains("Safari"))

        val vr = InnerTubeClients.ANDROID_VR
        assertTrue(vr.userAgent.contains("youtube.vr.oculus"))

        val tv = InnerTubeClients.TVHTML5_SIMPLY_EMBEDDED_PLAYER
        assertTrue(tv.userAgent.contains("PlayStation") || tv.userAgent.contains("Mozilla"))

        val web = InnerTubeClients.WEB_REMIX
        assertTrue(web.userAgent.contains("Mozilla") || web.userAgent.contains("Windows"))
    }

    @Test
    fun testFallbackRecursionPrevention() = kotlinx.coroutines.runBlocking {
        val engine = com.example.flowave.downloader.SealStyleDownloadEngine(null)
        val inspectResult = engine.inspect("test", allowFallbackToNative = false)
        assertTrue("Inspect with allowFallbackToNative=false should fail cleanly without recursion", inspectResult.isFailure)
        val resolveResult = engine.resolveAudioUrl("https://www.youtube.com/watch?v=12345", allowFallbackToNative = false)
        assertTrue("Resolve with allowFallbackToNative=false should fail cleanly without recursion", resolveResult.isFailure)
    }
}
