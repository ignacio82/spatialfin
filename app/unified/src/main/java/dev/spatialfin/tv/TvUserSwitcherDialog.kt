package dev.spatialfin.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.viewmodels.MainState
import java.util.UUID

@Composable
fun TvUserSwitcherDialog(
    state: MainState,
    onDismissRequest: () -> Unit,
    onUserSwitched: () -> Unit,
    onAddUser: () -> Unit,
    viewModel: UsersViewModel = hiltViewModel()
) {
    val usersState by viewModel.state.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) { viewModel.loadUsers() }
    
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            if (it is dev.jdtech.jellyfin.setup.presentation.users.UsersEvent.NavigateToHome) {
                onUserSwitched()
            }
        }
    }

    val allUsers = (usersState.users + usersState.publicUsers).distinctBy { it.id }
    
    val actions = buildList {
        allUsers.forEach { user ->
            add(
                TvOverflowAction(
                    id = user.id.toString(),
                    iconContent = {
                        val avatarUri = userPrimaryImageUri(state.currentServerAddress, user.id)
                        if (avatarUri != null) {
                            AsyncImage(
                                model = avatarUri,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initial = user.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            Box(
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    },
                    label = user.name + if (user.id == state.currentUser?.id) " (current)" else "",
                    onClick = {
                        if (usersState.users.any { it.id == user.id }) {
                            viewModel.onAction(dev.jdtech.jellyfin.setup.presentation.users.UsersAction.OnUserClick(user.id))
                        } else {
                            viewModel.onAction(dev.jdtech.jellyfin.setup.presentation.users.UsersAction.OnPublicUserClick(user.name))
                        }
                    }
                )
            )
        }
        add(
            TvOverflowAction(
                id = "add_user",
                iconContent = {
                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                },
                label = "Add another user",
                subtitle = "Sign in with a different account",
                onClick = onAddUser
            )
        )
    }

    TvOverflowSheet(
        title = "Switch user",
        subtitle = usersState.serverName?.let { "Connected to $it" } ?: "Choose a Jellyfin profile on this server",
        actions = actions,
        onDismissRequest = onDismissRequest
    )
}
