package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.viewmodels.MainState

@Composable
fun TvUserSwitcherDialog(
    state: MainState,
    serverId: String,
    serverName: String,
    serverAddress: String,
    isServerAuthenticated: Boolean,
    onDismissRequest: () -> Unit,
    onNeedsLogin: (prefilledUsername: String?) -> Unit,
    onAddUser: () -> Unit,
    onLogout: () -> Unit,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val usersState by viewModel.state.collectAsStateWithLifecycle()
    var failureCode by remember { mutableStateOf<OnboardingFailureCode?>(null) }

    LaunchedEffect(serverId, serverName, serverAddress) {
        viewModel.loadUsers(
            serverId = serverId,
            serverName = serverName,
            serverAddress = serverAddress,
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                UsersEvent.UserActivated -> {
                    failureCode = null
                    onDismissRequest()
                }
                is UsersEvent.NeedsLogin -> {
                    failureCode = null
                    onDismissRequest()
                    onNeedsLogin(event.username)
                }
                is UsersEvent.Failed -> {
                    failureCode = event.code
                }
            }
        }
    }

    val allUsers = (usersState.users + usersState.publicUsers).distinctBy { it.id }

    val actions = buildList {
        allUsers.forEach { user ->
            val isSaved = usersState.users.any { it.id == user.id }
            add(
                TvOverflowAction(
                    id = user.id.toString(),
                    iconContent = {
                        val avatarUri = userPrimaryImageUri(usersState.serverAddress ?: serverAddress, user.id)
                        if (avatarUri != null) {
                            AsyncImage(
                                model = avatarUri,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            val initial = user.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    },
                    label = user.name + if (user.id == state.currentUser?.id) " (current)" else "",
                    onClick = {
                        failureCode = null
                        if (isSaved) {
                            viewModel.onAction(UsersAction.OnUserClick(user.id))
                        } else {
                            viewModel.onAction(UsersAction.OnPublicUserClick(user.name))
                        }
                    },
                ),
            )
        }
        add(
            TvOverflowAction(
                id = "add_user",
                iconContent = {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                },
                label = "Add another user",
                subtitle = "Sign in with a different account",
                onClick = {
                    onDismissRequest()
                    onAddUser()
                },
            ),
        )
        if (isServerAuthenticated) {
            add(
                TvOverflowAction(
                    id = "sign_out",
                    iconContent = {
                        Box(
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    },
                    label = "Sign out",
                    subtitle = "Sign out of the current Jellyfin account",
                    onClick = {
                        onDismissRequest()
                        onLogout()
                    },
                ),
            )
        }
    }

    val subtitleText =
        failureCode?.let { code ->
            "${OnboardingFailureMessages.message(context, code)} (${code.wireCode})"
        } ?: (usersState.serverName ?: serverName).takeIf { it.isNotBlank() }?.let { "Connected to $it" }
            ?: "Choose a Jellyfin profile on this server"

    TvOverflowSheet(
        title = "Switch user",
        subtitle = subtitleText,
        actions = actions,
        onDismissRequest = onDismissRequest,
    )
}
