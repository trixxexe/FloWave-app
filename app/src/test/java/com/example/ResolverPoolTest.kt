package com.example

import com.example.flowave.data.remote.InvidiousRegistryParser
import com.example.flowave.data.remote.ResolverCandidate
import com.example.flowave.data.remote.ResolverPool
import com.example.flowave.data.remote.ResolverType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolverPoolTest {
    @Test
    fun `normalization rejects non HTTPS and deduplicates hosts`() {
        assertEquals("example.com", ResolverPool.normalizeHost("HTTPS://Example.com/"))
        assertEquals(null, ResolverPool.normalizeHost("http://example.com"))
        assertEquals(null, ResolverPool.normalizeHost("not a host"))

        val pool = ResolverPool()
        pool.upsert(ResolverCandidate(ResolverType.PIPED, "https://Example.com/", "curated"))
        pool.upsert(ResolverCandidate(ResolverType.PIPED, "example.com", "discovery"))
        assertEquals(1, pool.all().size)
    }

    @Test
    fun `ranking prefers successful low latency candidates and cooldown hides failures`() {
        val pool = ResolverPool()
        pool.upsert(ResolverCandidate(ResolverType.PIPED, "slow.example", "curated"))
        pool.upsert(ResolverCandidate(ResolverType.PIPED, "fast.example", "curated"))
        pool.markSuccess("PIPED|slow.example", 500, 10)
        pool.markSuccess("PIPED|fast.example", 50, 10)
        assertEquals("fast.example", pool.ranked(ResolverType.PIPED, 10).first().host)

        pool.markFailure("PIPED|fast.example", "dns_unavailable", 1000, 20)
        assertEquals("slow.example", pool.ranked(ResolverType.PIPED, 20).first().host)
        assertFalse(pool.ranked(ResolverType.PIPED, 1019).any { it.host == "fast.example" })
        assertTrue(pool.ranked(ResolverType.PIPED, 1020).any { it.host == "fast.example" })
    }

    @Test
    fun `pool serialization preserves health state`() {
        val pool = ResolverPool()
        pool.upsert(ResolverCandidate(ResolverType.INVIDIOUS, "example.com", "curated"))
        pool.markFailure("INVIDIOUS|example.com", "timeout", 5000, 100)
        val restored = ResolverPool()
        restored.restore(pool.serialize())
        val candidate = restored.all().single()
        assertEquals("timeout", candidate.failureClass)
        assertEquals(1, candidate.consecutiveFailures)
        assertEquals(5100, candidate.cooldownUntilMs)
    }

    @Test
    fun `official registry parser filters unhealthy entries and deduplicates`() {
        val json = """
            [
              ["good.example", {"type":"https","uri":"https://Good.Example/","monitor":{"published":true,"down":false,"last_status":200,"uptime":99.0,"ssl":{"valid":true}}}],
              ["good.example", {"type":"https","uri":"https://good.example","monitor":{"published":true,"down":false,"last_status":200,"uptime":99.0,"ssl":{"valid":true}}}],
              ["bad.example", {"type":"https","uri":"https://bad.example","monitor":{"published":true,"down":true,"last_status":503,"uptime":50.0,"ssl":{"valid":false}}}],
              ["plain.example", {"type":"http","uri":"http://plain.example","monitor":{"published":true,"down":false,"last_status":200,"uptime":99.0,"ssl":{"valid":true}}}]
            ]
        """.trimIndent()
        val root = JSONArray(json)
        assertEquals(4, root.length())
        val firstTuple = root.optJSONArray(0)
        assertTrue(firstTuple != null)
        val firstMetadata = firstTuple!!.optJSONObject(1)
        assertTrue(firstMetadata != null)
        assertEquals("https", firstMetadata!!.optString("type"))
        val firstMonitor = firstMetadata.optJSONObject("monitor")
        assertTrue(firstMonitor != null)
        assertEquals(200, firstMonitor!!.optInt("last_status", 0))
        assertEquals(99.0, firstMonitor.optDouble("uptime", 0.0), 0.0)
        assertEquals("good.example", ResolverPool.normalizeHost(firstMetadata.optString("uri")))
        val candidates = InvidiousRegistryParser.parse(json)
        assertEquals(listOf("good.example"), candidates.map { it.host })
    }
}
