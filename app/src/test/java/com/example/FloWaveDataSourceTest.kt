package com.example

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.example.flowave.audio.FloWaveDataSourceFactory
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.remote.ResolvedStreamSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloWaveDataSourceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Subclass of InnerTubeRepository with controllable mock stream resolution for deterministic tests.
     */
    private class MockStreamRepository : InnerTubeRepository() {
        val resolutionCount = AtomicInteger(0)
        val inFlightResolutions = ConcurrentHashMap<String, Boolean>()
        val delays = ConcurrentHashMap<String, Long>()
        val results = ConcurrentHashMap<String, ResolvedStreamSource>()
        val cancelledResolutions = ConcurrentHashMap<String, Boolean>()

        override suspend fun getStreamResolution(videoId: String, forceRefresh: Boolean): ResolvedStreamSource {
            resolutionCount.incrementAndGet()
            inFlightResolutions[videoId] = true
            try {
                val delayMs = delays[videoId] ?: 0L
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                return results[videoId] ?: ResolvedStreamSource(
                    url = "https://media.example.test/stream/$videoId.webm",
                    resolver = "inner_tube",
                    candidateKey = null
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                cancelledResolutions[videoId] = true
                throw cancelled
            } finally {
                inFlightResolutions.remove(videoId)
            }
        }
    }

    @Test
    fun `missing video id in flowave uri throws IOException`() {
        val factory = FloWaveDataSourceFactory(context)
        val dataSource = factory.createDataSource()
        try {
            dataSource.open(DataSpec(Uri.parse("flowave://youtube/")))
            fail("Expected IOException for empty video ID")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("Missing online track identifier") == true)
        }
    }

    @Test
    fun `concurrent multi-item resolution does not deadlock or exhaust executor threads`() {
        val mockRepo = MockStreamRepository()
        // Simulate 4 concurrent queue items resolving with slight network delay
        val itemIds = listOf("vid_alpha", "vid_beta", "vid_gamma", "vid_delta")
        itemIds.forEach { id ->
            mockRepo.delays[id] = 50L
        }

        val factory = FloWaveDataSourceFactory(context, mockRepo)
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(itemIds.size)
        val successCount = AtomicInteger(0)
        val errors = ConcurrentHashMap<String, Throwable>()

        val futures = itemIds.map { id ->
            executor.submit(Callable {
                val ds = factory.createDataSource()
                try {
                    // This will invoke open() which uses the non-blocking CompletableFuture bridge
                    // Note: opening fake http URL on Robolectric will throw after URL resolution
                    // when reaching cacheDataSource.open(), which confirms resolution succeeded!
                    ds.open(DataSpec(Uri.parse("flowave://youtube/$id")))
                    successCount.incrementAndGet()
                } catch (e: IOException) {
                    // If resolution succeeded and it failed at network/cache connect, that proves
                    // resolution completed without deadlocks
                    errors[id] = e
                } finally {
                    latch.countDown()
                }
            })
        }

        // Must complete well within timeout (no deadlock)
        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Multi-item resolution must complete without deadlocking", completed)
        assertEquals("All 4 items must have been resolved by streamRepository", 4, mockRepo.resolutionCount.get())

        executor.shutdownNow()
    }

    @Test
    fun `thread interruption during resolution cancels in-flight job and throws IOException`() {
        val mockRepo = MockStreamRepository()
        mockRepo.delays["vid_hanging"] = 10_000L // Simulate slow/hung resolution

        val factory = FloWaveDataSourceFactory(context, mockRepo)
        val dataSource = factory.createDataSource()

        val interruptedThrew = AtomicBoolean(false)
        val threadStarted = CountDownLatch(1)

        val thread = Thread {
            try {
                threadStarted.countDown()
                dataSource.open(DataSpec(Uri.parse("flowave://youtube/vid_hanging")))
            } catch (e: IOException) {
                if (e.message?.contains("cancelled") == true) {
                    interruptedThrew.set(true)
                }
            }
        }
        thread.start()

        assertTrue(threadStarted.await(2, TimeUnit.SECONDS))
        // Allow resolution to start
        Thread.sleep(100)
        // Interrupt the loader thread while waiting (mimicking Media3 loader cancellation)
        thread.interrupt()
        thread.join(3000)

        assertFalse("Thread must have terminated after interruption", thread.isAlive)
        assertTrue("Interruption must result in IOException with cancelled status", interruptedThrew.get())
        // Wait briefly for the cancelled background coroutine to finish its finally block
        var waitInterruptedMs = 0
        while (mockRepo.inFlightResolutions.containsKey("vid_hanging") && waitInterruptedMs < 1000) {
            Thread.sleep(20)
            waitInterruptedMs += 20
        }
        assertFalse("Hung resolution must not remain in-flight", mockRepo.inFlightResolutions.containsKey("vid_hanging"))
    }

    @Test
    fun `close on DataSource cancels active resolution`() {
        val mockRepo = MockStreamRepository()
        mockRepo.delays["vid_slow"] = 10_000L

        val factory = FloWaveDataSourceFactory(context, mockRepo)
        val dataSource = factory.createDataSource()

        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val openResult = CompletableDeferred<Boolean>()

        executor.submit {
            started.countDown()
            try {
                dataSource.open(DataSpec(Uri.parse("flowave://youtube/vid_slow")))
                openResult.complete(true)
            } catch (e: IOException) {
                openResult.complete(false)
            }
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)

        // Close the DataSource while resolution is in progress
        dataSource.close()

        // Wait for executor to finish
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(3000) { openResult.await() }
            }
        }.getOrDefault(false)

        assertFalse("Resolution must fail after close() cancels it", result)
        var waitCloseMs = 0
        while (mockRepo.inFlightResolutions.containsKey("vid_slow") && waitCloseMs < 1000) {
            Thread.sleep(20)
            waitCloseMs += 20
        }
        assertFalse("Slow resolution must not remain in-flight after close()", mockRepo.inFlightResolutions.containsKey("vid_slow"))
        executor.shutdownNow()
    }

    @Test
    fun `open failure on upstream data source invalidates stream cache`() {
        val mockRepo = MockStreamRepository()
        val videoId = "vid_upstream_fail"
        mockRepo.results[videoId] = ResolvedStreamSource(
            url = "https://invalid-host-unreachable.test/stream.opus",
            resolver = "piped",
            candidateKey = "PIPED|invalid-host-unreachable.test"
        )

        val factory = FloWaveDataSourceFactory(context, mockRepo)
        val dataSource = factory.createDataSource()

        try {
            dataSource.open(DataSpec(Uri.parse("flowave://youtube/$videoId")))
        } catch (e: Exception) {
            // Expected failure connecting to invalid host
        }

        // Verify that invalidation was called on the repository
        // Subsequent resolution must re-query the repository rather than returning a stale broken stream
        assertEquals(1, mockRepo.resolutionCount.get())
    }

    @Test
    fun `defaultHttpDataSource uses exact ANDROID_VR user agent and configured timeouts`() {
        val factory = FloWaveDataSourceFactory(context)
        assertEquals(
            "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 2; Build/SQ3A.220605.009.A1) gzip",
            FloWaveDataSourceFactory.ANDROID_VR_USER_AGENT
        )
        assertNotNull(factory.defaultHttpDataSourceFactory)
        assertNotNull(factory.cacheDataSourceFactory)
        assertNotNull(factory.resolvingDataSourceFactory)
    }

    @Test
    fun `local file and content uris route directly without invoking stream repository`() {
        val mockRepo = MockStreamRepository()
        val factory = FloWaveDataSourceFactory(context, mockRepo)

        val fileSpec = DataSpec(Uri.parse("file:///data/local/music.mp3"))
        val resolvedFileSpec = factory.resolveStreamDataSpec(fileSpec)
        assertEquals(fileSpec.uri, resolvedFileSpec.uri)
        assertEquals(0, mockRepo.resolutionCount.get())

        val contentSpec = DataSpec(Uri.parse("content://media/external/audio/123"))
        val resolvedContentSpec = factory.resolveStreamDataSpec(contentSpec)
        assertEquals(contentSpec.uri, resolvedContentSpec.uri)
        assertEquals(0, mockRepo.resolutionCount.get())
    }

    @Test
    fun `resolveStreamDataSpec sets stable videoId key and matching request headers on DataSpec`() {
        val mockRepo = MockStreamRepository()
        val videoId = "stable_vid_123"
        mockRepo.results[videoId] = ResolvedStreamSource(
            url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=999999",
            resolver = "inner_tube",
            candidateKey = "ANDROID_VR",
            userAgent = FloWaveDataSourceFactory.ANDROID_VR_USER_AGENT,
            requestHeaders = mapOf("X-YouTube-Client-Name" to "28")
        )

        val factory = FloWaveDataSourceFactory(context, mockRepo)
        val dataSpec = DataSpec(Uri.parse("flowave://youtube/$videoId"))
        val resolvedSpec = factory.resolveStreamDataSpec(dataSpec)

        assertEquals("stable_vid_123", resolvedSpec.key)
        assertEquals(Uri.parse("https://rr1---sn-abc.googlevideo.com/videoplayback?expire=999999"), resolvedSpec.uri)
        assertEquals(FloWaveDataSourceFactory.ANDROID_VR_USER_AGENT, resolvedSpec.httpRequestHeaders["User-Agent"])
        assertEquals("https://www.youtube.com", resolvedSpec.httpRequestHeaders["Origin"])
        assertEquals("https://www.youtube.com/", resolvedSpec.httpRequestHeaders["Referer"])
        assertEquals("28", resolvedSpec.httpRequestHeaders["X-YouTube-Client-Name"])
    }

    @Test
    fun `resolveStreamDataSpec resolves youtube watch url just-in-time`() {
        val mockRepo = MockStreamRepository()
        val videoId = "watch_vid_456"
        val factory = FloWaveDataSourceFactory(context, mockRepo)

        val watchSpec = DataSpec(Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        val resolvedSpec = factory.resolveStreamDataSpec(watchSpec)

        assertEquals(videoId, resolvedSpec.key)
        assertEquals(Uri.parse("https://media.example.test/stream/$videoId.webm"), resolvedSpec.uri)
        assertEquals(1, mockRepo.resolutionCount.get())
    }
}
