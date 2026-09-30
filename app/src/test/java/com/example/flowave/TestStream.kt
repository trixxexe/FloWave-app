package com.example.flowave

import com.example.flowave.data.remote.InnerTubeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class TestStream {
    @Test
    fun testStreamResolution() = runBlocking {
        val repo = InnerTubeRepository(null)
        try {
            println("Testing resolution for video: jdb8fzY-oVA")
            val streamUrl = repo.getStreamResolution("jdb8fzY-oVA", forceRefresh = true)
            println("SUCCESS: " + streamUrl.url)
        } catch(e: Exception) {
            println("FAILURE:")
            e.printStackTrace()
            throw e
        }
    }
}
