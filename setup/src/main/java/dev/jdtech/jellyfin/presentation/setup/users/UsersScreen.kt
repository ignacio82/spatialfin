package dev.jdtech.jellyfin.presentation.setup.users

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.components.XrConfirmDialog
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.presentation.setup.components.UserItem
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.onboarding.usersCompanionStatusRes
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.UsersState
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.spatialfin.presentation.theme.SpatialFinTheme
import java.util.UUID

@Composable
fun UsersScreen(
    serverId: String,
    serverName: String,
    serverAddress: String,
    onNeedsLogin: (userId: UUID?, username: String, failureCode: OnboardingFailureCode?) -> Unit,
    onChangeServerClick: () -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    onBrowseOfflineMedia: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    showBack: Boolean = true,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var failureCode by remember { mutableStateOf<OnboardingFailureCode?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(serverId, serverAddress) {
        viewModel.loadUsers(serverId, serverName, serverAddress)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onResumeOrStart()
                Lifecycle.Event.ON_STOP -> viewModel.onPauseOrStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            // SessionNavigationPolicy handles root transition on UserActivated.
            is UsersEvent.UserActivated -> failureCode = null
            is UsersEvent.NeedsLogin -> {
                failureCode = null
                onNeedsLogin(event.userId, event.username, event.failureCode)
            }
            is UsersEvent.Failed -> failureCode = event.code
        }
    }

    UsersScreenLayout(
        state = state,
        failureCode = failureCode,
        showBack = showBack,
        onBrowseOfflineMedia = onBrowseOfflineMedia,
        onLogout = onLogout,
        onAction = { action ->
            failureCode = null
            when (action) {
                is UsersAction.OnChangeServerClick -> onChangeServerClick()
                is UsersAction.OnAddClick -> onAddClick()
                is UsersAction.OnBackClick -> onBackClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun UsersScreenLayout(
    state: UsersState,
    failureCode: OnboardingFailureCode? = null,
    showBack: Boolean = true,
    onBrowseOfflineMedia: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    onAction: (UsersAction) -> Unit,
) {
    val context = LocalContext.current
    var openDeleteDialog by remember { mutableStateOf(false) }
    var selectedUser by remember { mutableStateOf<User?>(null) }

    RootLayout {
        Column(
            modifier =
                Modifier.padding(horizontal = 24.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
        ) {
            Spacer(modifier = Modifier.weight(0.2f))
            Image(
                painter = painterResource(id = SetupR.drawable.ic_beam_launcher),
                contentDescription = null,
                modifier = Modifier.width(320.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(SetupR.string.users),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(SetupR.string.server_subtitle, state.serverName ?: ""),
                style = MaterialTheme.typography.titleLarge,
            )
            if (failureCode != null) {
                Spacer(modifier = Modifier.height(16.dp))
                ErrorBannerWithCopy(context = context, code = failureCode)
            }
            if (state.isConnectingCompanion) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(SetupR.string.users_companion_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (state.users.isEmpty() && state.companionUsers.isEmpty() && state.publicUsers.isEmpty()) {
                Text(
                    text = stringResource(SetupR.string.users_no_users),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(state.users, key = { it.id }) { user ->
                        val companionStatus = state.companionStatusFor(user)
                        val hasToken = !user.accessToken.isNullOrBlank()
                        val (subtitle, subtitleColor) =
                            when {
                                !hasToken && companionStatus != null && companionStatus.requiresCredentialUpdate ->
                                    stringResource(usersCompanionStatusRes(companionStatus)) to MaterialTheme.colorScheme.error
                                !hasToken && companionStatus != null ->
                                    stringResource(SetupR.string.users_status_companion_ready) to MaterialTheme.colorScheme.primary
                                !hasToken ->
                                    stringResource(SetupR.string.users_status_sign_in_required) to MaterialTheme.colorScheme.onSurfaceVariant
                                companionStatus != null && companionStatus.needsRelink ->
                                    stringResource(SetupR.string.users_status_signed_in_companion_relink) to MaterialTheme.colorScheme.error
                                else ->
                                    stringResource(SetupR.string.users_status_signed_in) to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        UserItem(
                            name = user.name,
                            avatarUri = userPrimaryImageUri(state.serverAddress, user.id),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAction(UsersAction.OnUserClick(userId = user.id)) },
                            onLongClick = {
                                selectedUser = user
                                openDeleteDialog = true
                            },
                            subtitle = subtitle,
                            subtitleColor = subtitleColor,
                        )
                    }
                    items(
                        state.companionUsers,
                        key = { "companion:${it.serverId}:${it.userId ?: it.username.lowercase()}" },
                    ) { candidate ->
                        val subtitleColor =
                            if (candidate.requiresCredentialUpdate) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        UserItem(
                            name = candidate.username,
                            avatarUri = candidate.userId?.let { userPrimaryImageUri(state.serverAddress, it) },
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAction(UsersAction.OnCompanionUserClick(candidate)) },
                            onLongClick = {},
                            subtitle = stringResource(usersCompanionStatusRes(candidate)),
                            subtitleColor = subtitleColor,
                        )
                    }
                    items(state.publicUsers, key = { it.id }) { user ->
                        UserItem(
                            name = user.name,
                            avatarUri = userPrimaryImageUri(state.serverAddress, user.id),
                            modifier = Modifier.fillMaxWidth().alpha(0.7f),
                            onClick = {
                                onAction(UsersAction.OnPublicUserClick(username = user.name))
                            },
                            onLongClick = {},
                            subtitle = "Public user",
                        )
                    }
                }
            }
            if (onBrowseOfflineMedia != null || onLogout != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    if (onBrowseOfflineMedia != null) {
                        TextButton(onClick = onBrowseOfflineMedia) {
                            Text(stringResource(SetupR.string.browse_downloads_and_local_media))
                        }
                    }
                    if (onLogout != null) {
                        OutlinedButton(onClick = onLogout) {
                            Text(stringResource(SetupR.string.sign_out))
                        }
                    }
                }
            }
        }
        if (showBack) {
            FilledTonalButton(
                onClick = { onAction(UsersAction.OnBackClick) },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_left),
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Back", style = MaterialTheme.typography.titleMedium)
            }
        }
        FilledTonalButton(
            onClick = { onAction(UsersAction.OnChangeServerClick) },
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
        ) {
            Icon(painter = painterResource(CoreR.drawable.ic_server), contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Server", style = MaterialTheme.typography.titleMedium)
        }
        ExtendedFloatingActionButton(
            onClick = { onAction(UsersAction.OnAddClick) },
            icon = { Icon(painterResource(CoreR.drawable.ic_plus), contentDescription = null) },
            text = {
                Text(
                    text = stringResource(SetupR.string.users_btn_add_user),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )

        if (openDeleteDialog && selectedUser != null) {
            XrConfirmDialog(
                title = stringResource(SetupR.string.remove_user_dialog),
                message =
                    stringResource(
                        SetupR.string.remove_user_dialog_text,
                        selectedUser!!.name,
                    ),
                confirmLabel = stringResource(SetupR.string.confirm),
                dismissLabel = stringResource(SetupR.string.cancel),
                onDismiss = { openDeleteDialog = false },
                onConfirm = {
                    openDeleteDialog = false
                    onAction(UsersAction.OnDeleteUser(selectedUser!!.id))
                },
            )
        }
    }
}

@PreviewScreenSizes
@Composable
private fun UsersScreenLayoutPreview() {
    SpatialFinTheme {
        UsersScreenLayout(
            state =
                UsersState(
                    users = listOf(User(id = UUID.randomUUID(), name = "Bob", serverId = "")),
                    publicUsers =
                        listOf(User(id = UUID.randomUUID(), name = "Alice", serverId = "")),
                ),
            onAction = {},
        )
    }
}
