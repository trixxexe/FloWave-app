package com.example.flowave.utils

object FloWaveConstants {
    const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val USER_AGENT_MOBILE = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    const val USER_AGENT_ANDROID_MUSIC = "com.google.android.apps.youtube.music/6.25.52 (Linux; U; Android 13; US)"
    const val USER_AGENT_TVHTML5 = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebkit/538.1"
    const val USER_AGENT_WEB_EMBEDDED = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
    const val USER_AGENT_FLOWAVE_APP = "FloWave/2.0 (Android)"
    
    // In-memory stream cache duration (2 hours in milliseconds)
    const val STREAM_CACHE_DURATION_MS = 7_200_000L
    
    // Connect and read timeouts
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 15L
    const val FAST_TIMEOUT_SECONDS = 3L
    
    // Base URLs
    const val INNERTUBE_KEY = "AIzaSyC186e8K1Z5mY666B_1A"
    const val INNERTUBE_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY"
    const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY"

    val PIPED_SEARCH_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks/search?q=",
        "https://api.piped.video/search?q=",
        "https://pipedapi.mha.fi/search?"
    )
    
    val INVIDIOUS_SEARCH_INSTANCES = listOf(
        "https://inv.tux.pizza/api/v1/search?q=",
        "https://invidious.drgns.space/api/v1/search?q=",
        "https://vid.puffyan.us/api/v1/search?q="
    )
    
    val PIPED_STREAM_INSTANCES = listOf(
        "https://pipedapi.colby.land/streams/",
        "https://piped-api.garudalinux.org/streams/",
        "https://pipedapi.tokhmi.xyz/streams/",
        "https://pipedapi.privacydev.net/streams/",
        "https://pipedapi.swg.dev/streams/",
        "https://pipedapi.oxit.co/streams/",
        "https://pipedapi.kavin.rocks/streams/",
        "https://api.piped.video/streams/"
    )
}
