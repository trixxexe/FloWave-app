package com.example.flowave.scratch

import com.example.flowave.data.remote.InnerTubeRepository
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val repo = InnerTubeRepository(null)
    try {
        val streamUrl = repo.getStreamResolution("jdb8fzY-oVA", forceRefresh = true)
        println("SUCCESS: " + streamUrl)
    } catch(e: Exception) {
        println("FAILURE:")
        e.printStackTrace()
    }
}
