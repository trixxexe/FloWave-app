package com.example

import com.example.flowave.data.remote.InnerTubeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testSearchTracks() = runBlocking {
    val repo = InnerTubeRepository()
    try {
      println("TEST: Running YouTube search scraper JSON analysis...")
      val query = "Taylor Swift"
      val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
      val searchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%3D%3D"
      
      val client = okhttp3.OkHttpClient()
      val request = okhttp3.Request.Builder()
          .url(searchUrl)
          .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
          .header("Accept-Language", "en-US,en;q=0.9")
          .build()

      client.newCall(request).execute().use { response ->
        val html = response.body?.string() ?: ""
        val jsonString = repo.javaClass.getDeclaredMethod("extractJsonFromHtml", String::class.java).apply {
          isAccessible = true
        }.invoke(repo, html) as? String
        
        if (jsonString != null) {
          val json = org.json.JSONObject(jsonString)
          val topKeys = mutableListOf<String>()
          json.keys().forEach { topKeys.add(it) }
          println("TEST: Scraped JSON top keys: $topKeys")
          
          val rendererKeys = mutableSetOf<String>()
          fun findRenderers(js: Any) {
            if (js is org.json.JSONObject) {
              for (key in js.keys()) {
                if (key.endsWith("Renderer")) {
                  rendererKeys.add(key)
                }
                findRenderers(js.get(key))
              }
            } else if (js is org.json.JSONArray) {
              for (i in 0 until js.length()) {
                findRenderers(js.get(i))
              }
            }
          }
          findRenderers(json)
          println("TEST: Found renderer keys: $rendererKeys")
          
          // Let's also see if videoRenderer exists in the JSON
          val videoRenderers = mutableListOf<org.json.JSONObject>()
          fun findVideoRenderers(js: Any) {
            if (js is org.json.JSONObject) {
              if (js.has("videoRenderer")) {
                js.optJSONObject("videoRenderer")?.let { videoRenderers.add(it) }
              }
              for (key in js.keys()) {
                val value = js.opt(key) ?: continue
                findVideoRenderers(value)
              }
            } else if (js is org.json.JSONArray) {
              for (i in 0 until js.length()) {
                val value = js.opt(i) ?: continue
                findVideoRenderers(value)
              }
            }
          }
          findVideoRenderers(json)
          println("TEST: Found ${videoRenderers.size} videoRenderer elements")
          if (videoRenderers.isNotEmpty()) {
            val first = videoRenderers.first()
            println("TEST: First videoRenderer videoId: ${first.optString("videoId")}")
            val titleKeys = mutableListOf<String>()
            first.optJSONObject("title")?.keys()?.forEach { titleKeys.add(it) }
            println("TEST: First videoRenderer title keys: $titleKeys")
          }
        } else {
          println("TEST: Extracted JSON was null!")
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      fail("Diagnostic failed: ${e.message}")
    }
  }
}
