package dev.jdtech.jellyfin.data.musicassistant.utils

sealed interface DataConnectionState {
    object AwaitingServerInfo : DataConnectionState
    data class AwaitingAuth(val authProcessState: AuthProcessState) : DataConnectionState
    object Authenticated : DataConnectionState
}
