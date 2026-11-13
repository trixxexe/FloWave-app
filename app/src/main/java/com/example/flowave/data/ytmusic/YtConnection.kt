package com.example.flowave.data.ytmusic

/**
 * Represents the signed-in YouTube Music user account connection state.
 */
data class YtConnection(
    val cookies: Map<String, String> = emptyMap(),
    val accountName: String = "",
    val channelHandle: String? = null,
    val photoUrl: String? = null,
    val connectedAtMillis: Long = 0L,
    /**
     * Selected YouTube channel within the signed-in session.
     * [pageId] (brand-channel delegation token) is sent as the `X-Goog-PageId` header.
     * [onBehalfOfUser] (or normalized `dataSyncId`) goes into the InnerTube context `user` block.
     * [authUserIndex] (multi-login session index) is sent as `X-Goog-AuthUser`.
     * [visitorData] is sent as `X-Goog-Visitor-Id` and in `context.client.visitorData`.
     */
    val onBehalfOfUser: String? = null,
    val authUserIndex: Int? = null,
    val pageId: String = "",
    val visitorData: String? = null,
) {
    val isConnected: Boolean
        get() = cookies.isNotEmpty() && accountName.isNotBlank()

    companion object {
        val DISCONNECTED = YtConnection()
    }
}

/** Summarized information for an account playlist. */
data class YtPlaylistSummary(
    val id: String,
    val title: String,
    val author: String? = null,
    val trackCountText: String? = null,
    val artworkUrl: String? = null,
)

/** Identity retrieved from YouTube's account_menu endpoint. */
data class YtAccountInfo(
    val accountName: String,
    val channelHandle: String? = null,
    val photoUrl: String? = null,
)
