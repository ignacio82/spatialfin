package dev.jdtech.jellyfin.data.musicassistant.utils

sealed interface AuthProcessState {
    object NotStarted : AuthProcessState
    object InProgress : AuthProcessState
    object LoggedOut : AuthProcessState
    data class Failed(val reason: String) : AuthProcessState
}
