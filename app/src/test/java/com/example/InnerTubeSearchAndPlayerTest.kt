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
        assertEquals(3, chain.size)
        
        // Ensure no invalid or deprecated clients exist in the fallback chain
        for (client in chain) {
            assertFalse("ANDROID_EMBEDDED_PLAYER is an invalid enum and must not be used", client.clientName == "ANDROID_EMBEDDED_PLAYER")
            assertTrue("Client version must not be empty", client.clientVersion.isNotBlank())
            assertTrue("User agent must not be empty", client.userAgent.isNotBlank())
        }

        // Verify Android VR client context completeness
        val vr = chain.first { it.clientName == "ANDROID_VR" }
        assertEquals("Oculus", vr.deviceMake)
        assertEquals("Quest 2", vr.deviceModel)
        assertEquals("Android", vr.osName)
        assertEquals("12", vr.osVersion)
        assertEquals(32, vr.androidSdkVersion)
        assertEquals("1.56.21", vr.clientVersion)
        assertEquals("28", vr.clientNumber)

        // Verify iOS client context completeness
        val ios = chain.first { it.clientName == "IOS" }
        assertEquals("Apple", ios.deviceMake)
        assertEquals("iPhone16,2", ios.deviceModel)
        assertEquals("iPhone", ios.osName)
        assertEquals("17.5.1.21F90", ios.osVersion)
        assertEquals("19.29.1", ios.clientVersion)
        assertEquals("5", ios.clientNumber)

        // Verify Android TestSuite client context completeness
        val testsuite = chain.first { it.clientName == "ANDROID_TESTSUITE" }
        assertEquals(32, testsuite.androidSdkVersion)
        assertEquals("1.9", testsuite.clientVersion)
        assertEquals("30", testsuite.clientNumber)
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
