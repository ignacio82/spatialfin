package dev.jdtech.jellyfin.data.musicassistant.api

data class ConnectionInfo(
    val host: String,
    val port: Int,
    val isTls: Boolean,
    /**
     * Bearer token for the WebSocket handshake. MA's `/ws` endpoint runs through
     * the same auth middleware as the REST API, so an `Authorization: Bearer`
     * header on the upgrade request authenticates the socket. Null for servers
     * with auth disabled.
     */
    val token: String? = null,
) {
    val webUrl = "${if (isTls) "https" else "http"}://$host:$port"
}
