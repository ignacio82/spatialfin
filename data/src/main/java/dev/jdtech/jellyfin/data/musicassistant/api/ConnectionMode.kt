package dev.jdtech.jellyfin.data.musicassistant.api

sealed interface ConnectionMode {
    data class Direct(
        val host: String,
        val port: Int,
        val isTls: Boolean,
    ) : ConnectionMode {
        val webUrl: String = "${if (isTls) "https" else "http"}://$host:$port"
    }
}
