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
        // ANDROID_TESTSUITE should be primary (bypasses bot challenges without web PO token)
        assertEquals("ANDROID_TESTSUITE", chain[0].clientName)
        assertEquals("IOS", chain[1].clientName)
        assertEquals("ANDROID_MUSIC", chain[2].clientName)
        assertEquals("WEB_REMIX", chain[3].clientName)
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
        val testsuite = InnerTubeClients.ANDROID_TESTSUITE
        assertTrue(testsuite.userAgent.contains("com.google.android.youtube"))

        val ios = InnerTubeClients.IOS
        assertTrue(ios.userAgent.contains("com.google.ios.youtube") || ios.userAgent.contains("iPhone"))

        val web = InnerTubeClients.WEB_REMIX
        assertTrue(web.userAgent.contains("Mozilla") || web.userAgent.contains("Windows"))
    }
}
