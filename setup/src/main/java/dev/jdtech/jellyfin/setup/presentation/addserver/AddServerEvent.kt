package dev.jdtech.jellyfin.setup.presentation.addserver

sealed interface AddServerEvent {
    data class Success(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
    ) : AddServerEvent
}
