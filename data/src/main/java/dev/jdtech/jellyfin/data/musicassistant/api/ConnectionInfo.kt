package dev.jdtech.jellyfin.data.musicassistant.api

data class ConnectionInfo(
    val host: String,
    val port: Int,
    val isTls: Boolean,
) {
    val webUrl = "${if (isTls) "https" else "http"}://$host:$port"
}
