package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.flowave.data.remote.InnerTubeRepository
import com.example.flowave.data.model.InnerTubeTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FloWave", appName)
  }

  @Test
  fun testInnerTubeParserMockJson() {
    val mockJsonString = """
      {
        "contents": {
          "tabbedSearchResultsRenderer": {
            "tabs": [
              {
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "musicShelfRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "playlistItemData": {
                                    "videoId": "abc123test"
                                  },
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": {
                                          "runs": [{"text": "Test Song"}]
                                        }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": {
                                          "runs": [{"text": "Test Artist"}]
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            ]
          }
        }
      }
    """.trimIndent()

    val json = org.json.JSONObject(mockJsonString)
    val tracks = com.example.flowave.data.remote.InnerTubeParser.parseInnerTubeSearchJson(json)
    assertNotNull(tracks)
    assertEquals(1, tracks.size)
    assertEquals("abc123test", tracks[0].id)
    assertEquals("Test Song", tracks[0].title)
  }
}
