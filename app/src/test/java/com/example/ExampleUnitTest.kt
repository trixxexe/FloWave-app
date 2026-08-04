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
  fun testExtractJsonFromHtmlMock() {
    val repo = InnerTubeRepository()
    val mockHtml = """
      <html>
        <script>var ytInitialData = {"contents":{"twoColumnSearchResultsRenderer":{}}};</script>
      </html>
    """.trimIndent()

    val extractMethod = repo.javaClass.getDeclaredMethod("extractJsonFromHtml", String::class.java).apply {
      isAccessible = true
    }
    val extracted = extractMethod.invoke(repo, mockHtml) as? String
    assertNotNull("Extracted JSON should not be null for valid mock HTML", extracted)
    assertTrue("Extracted JSON should contain twoColumnSearchResultsRenderer", extracted!!.contains("twoColumnSearchResultsRenderer"))
  }
}
