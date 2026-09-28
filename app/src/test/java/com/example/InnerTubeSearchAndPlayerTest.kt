package com.example

import com.example.flowave.data.remote.InnerTubeClients
import com.example.flowave.data.remote.InnerTubeParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InnerTubeSearchAndPlayerTest {

    @Test
    fun `inner tube client fallback chain uses valid clients and complete device contexts`() {
        val chain = InnerTubeClients.FALLBACK_CHAIN
        assertTrue(chain.isNotEmpty())
        
        // Ensure no invalid or deprecated clients exist in the fallback chain
        for (client in chain) {
            assertFalse("ANDROID_TESTSUITE must not be in fallback chain", client.clientName == "ANDROID_TESTSUITE")
            assertFalse("ANDROID_EMBEDDED_PLAYER is an invalid enum and must not be used", client.clientName == "ANDROID_EMBEDDED_PLAYER")
            assertTrue("Client version must not be empty", client.clientVersion.isNotBlank())
            assertTrue("User agent must not be empty", client.userAgent.isNotBlank())
        }

        // Verify Android client context completeness
        val android = chain.first { it.clientName == "ANDROID" }
        assertEquals(30, android.androidSdkVersion)
        assertEquals("Android", android.osName)
        assertEquals("11", android.osVersion)

        // Verify iOS client context completeness
        val ios = chain.first { it.clientName == "IOS" }
        assertEquals("Apple", ios.deviceMake)
        assertEquals("iPhone16,2", ios.deviceModel)
        assertEquals("iPhone", ios.osName)
    }

    @Test
    fun `parseInnerTubeSearchJson extracts tracks from musicCardShelfRenderer and handles Song prefix`() {
        val jsonString = """
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
                                "musicCardShelfRenderer": {
                                  "contents": [
                                    {
                                      "musicResponsiveListItemRenderer": {
                                        "overlay": {
                                          "musicItemThumbnailOverlayRenderer": {
                                            "content": {
                                              "musicPlayButtonRenderer": {
                                                "playNavigationEndpoint": {
                                                  "watchEndpoint": {
                                                    "videoId": "vid_card_hit"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        },
                                        "flexColumns": [
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [{"text": "Cruel Summer"}]
                                              }
                                            }
                                          },
                                          {
                                            "musicResponsiveListItemFlexColumnRenderer": {
                                              "text": {
                                                "runs": [
                                                  {"text": "Song"},
                                                  {"text": " • "},
                                                  {"text": "Taylor Swift"},
                                                  {"text": " • "},
                                                  {"text": "Lover"},
                                                  {"text": " • "},
                                                  {"text": "2:58"}
                                                ]
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

        val json = JSONObject(jsonString)
        val tracks = InnerTubeParser.parseInnerTubeSearchJson(json)

        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("vid_card_hit", track.id)
        assertEquals("Cruel Summer", track.title)
        assertEquals("Taylor Swift", track.artist)
        assertEquals("Lover", track.album)
        assertEquals("2:58", track.durationText)
    }

    @Test
    fun `extractVideoId correctly extracts from nested flexColumn watchEndpoint`() {
        val jsonString = """
            {
              "flexColumns": [
                {
                  "musicResponsiveListItemFlexColumnRenderer": {
                    "text": {
                      "runs": [
                        {
                          "text": "Song Title",
                          "navigationEndpoint": {
                            "watchEndpoint": {
                              "videoId": "nested_col_vid"
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val item = JSONObject(jsonString)
        val videoId = InnerTubeParser.extractVideoId(item)
        assertEquals("nested_col_vid", videoId)
    }
}
