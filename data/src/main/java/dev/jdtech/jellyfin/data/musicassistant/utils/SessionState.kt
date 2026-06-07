package dev.jdtech.jellyfin.data.musicassistant.utils

import dev.jdtech.jellyfin.data.musicassistant.api.ConnectionInfo
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerInfo
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.User

sealed class SessionState {
    sealed class Connected : SessionState(), HasConnectionData {
        data class Direct(
            val connectionInfo: ConnectionInfo,
            override val connectionData: ConnectionData = ConnectionData(),
        ) : Connected()
    }

    data object Connecting : SessionState()

    sealed class Reconnecting : SessionState(), HasConnectionData {
        abstract val attempt: Int
        data class Direct(
            override val attempt: Int,
            val connectionInfo: ConnectionInfo,
            override val connectionData: ConnectionData = ConnectionData(),
        ) : Reconnecting()
    }

    sealed class Disconnected : SessionState() {
        data object Initial : Disconnected()
        data object ByUser : Disconnected()
        data object NoServerData : Disconnected()
        data object Backgrounded : Disconnected()
        data class Error(val reason: Exception?) : Disconnected()
    }
}

fun SessionState.Connected.update(
    connectionData: ConnectionData = this.connectionData,
): SessionState.Connected = when (this) {
    is SessionState.Connected.Direct -> copy(connectionData = connectionData)
}

fun SessionState.Connected.update(
    serverInfo: ServerInfo? = this.serverInfo,
    user: User? = this.user,
    authProcessState: AuthProcessState = this.authProcessState,
    wasAutoLogin: Boolean = this.wasAutoLogin,
    needsServerReauth: Boolean = this.connectionData.needsServerReauth,
): SessionState.Connected = update(
    connectionData = ConnectionData(serverInfo, user, authProcessState, wasAutoLogin, needsServerReauth),
)

val SessionState.Connected.connectionInfo: ConnectionInfo?
    get() = when (this) {
        is SessionState.Connected.Direct -> connectionInfo
    }

fun SessionState.Reconnecting.update(
    connectionData: ConnectionData = this.connectionData,
): SessionState.Reconnecting = when (this) {
    is SessionState.Reconnecting.Direct -> copy(connectionData = connectionData)
}

fun SessionState.Reconnecting.update(
    serverInfo: ServerInfo? = this.serverInfo,
    user: User? = this.user,
    authProcessState: AuthProcessState = this.authProcessState,
    wasAutoLogin: Boolean = this.wasAutoLogin,
    needsServerReauth: Boolean = this.connectionData.needsServerReauth,
): SessionState.Reconnecting = update(
    connectionData = ConnectionData(serverInfo, user, authProcessState, wasAutoLogin, needsServerReauth),
)

val SessionState.Reconnecting.connectionInfo: ConnectionInfo?
    get() = when (this) {
        is SessionState.Reconnecting.Direct -> connectionInfo
    }
