package dev.jdtech.jellyfin.setup.presentation.users

import dev.jdtech.jellyfin.models.User

data class UsersState(
    val users: List<User> = emptyList(),
    val publicUsers: List<User> = emptyList(),
    val serverName: String? = null,
    /** Base URL of the currently-selected server address — used to build user
     *  profile image URIs like `{serverAddress}/Users/{userId}/Images/Primary`. */
    val serverAddress: String? = null,
)
