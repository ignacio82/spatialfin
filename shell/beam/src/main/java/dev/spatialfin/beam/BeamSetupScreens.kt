package dev.spatialfin.beam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.userPrimaryImageUri
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerAction
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerEvent
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerViewModel
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.setup.presentation.servers.ServersAction
import dev.jdtech.jellyfin.setup.presentation.servers.ServersEvent
import dev.jdtech.jellyfin.setup.presentation.servers.ServersViewModel
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@Composable
fun BeamServersScreen(
    contentPadding: PaddingValues,
    onNavigateToUsers: (serverId: String, serverName: String, serverAddress: String) -> Unit,
    onAddServerClick: () -> Unit,
    onCompanionImportClick: () -> Unit,
    onBackClick: (() -> Unit)? = null,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var failureCode by remember { mutableStateOf<OnboardingFailureCode?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadServers()
    }
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is ServersEvent.NavigateToUsers -> {
                failureCode = null
                onNavigateToUsers(event.serverId, event.serverName, event.serverAddress)
            }
            is ServersEvent.Failed -> {
                failureCode = event.code
            }
            ServersEvent.ServerChanged -> {
                // Navigation is driven authoritatively by SessionNavigationPolicy.
                failureCode = null
            }
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Choose A Server",
                body = "Select an existing server or add a new one.",
            )
        }
        failureCode?.let { code ->
            item {
                ErrorBannerWithCopy(
                    code = code,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.servers.isEmpty()) {
            item {
                BeamEmptyCard("No servers configured yet.")
            }
        } else {
            items(state.servers, key = { it.server.id }) { server ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(server.server.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            server.addresses.firstOrNull()?.address.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    failureCode = null
                                    viewModel.onAction(ServersAction.OnServerClick(server.server.id))
                                },
                            ) {
                                Text("Use Server")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.onAction(ServersAction.DeleteServer(server.server.id))
                                },
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
        item {
            BeamActionRow(
                primaryLabel = "Add Server",
                onPrimaryClick = onAddServerClick,
                secondaryLabel = "Set Up From Device",
                onSecondaryClick = onCompanionImportClick,
                tertiaryLabel = if (onBackClick != null) "Back" else null,
                onTertiaryClick = onBackClick,
            )
        }
    }
}

@Composable
fun BeamAddServerScreen(
    contentPadding: PaddingValues,
    onSuccess: (serverId: String, serverName: String, serverAddress: String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var serverAddress by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.discoverServers()
    }
    ObserveAsEvents(viewModel.events) { event ->
        if (event is AddServerEvent.Success) {
            onSuccess(event.serverId, event.serverName, event.serverAddress)
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = stringResource(SetupR.string.connect_to_jellyfin_server),
                body = "Enter a server address or choose a discovered server on your network.",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Discovered Servers", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { viewModel.discoverServers() }) {
                            Text("Rescan")
                        }
                    }
                    if (state.discoveredServers.isEmpty()) {
                        Text(
                            text = "Scanning local network (UDP 7359)…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.discoveredServers.forEach { server ->
                            OutlinedButton(
                                onClick = {
                                    serverAddress = server.address
                                    viewModel.onAction(AddServerAction.OnConnectClick(server.address))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${server.name} • ${server.address}")
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Server Address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            viewModel.onAction(AddServerAction.OnConnectClick(serverAddress))
                        }),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                val trimmed = serverAddress.trim()
                                serverAddress =
                                    when {
                                        trimmed.startsWith("https://") -> "http://" + trimmed.removePrefix("https://")
                                        trimmed.startsWith("http://") -> trimmed
                                        else -> "http://$trimmed"
                                    }
                            },
                            label = { Text("http://") },
                        )
                        AssistChip(
                            onClick = {
                                val trimmed = serverAddress.trim()
                                serverAddress =
                                    when {
                                        trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
                                        trimmed.startsWith("https://") -> trimmed
                                        else -> "https://$trimmed"
                                    }
                            },
                            label = { Text("https://") },
                        )
                        AssistChip(
                            onClick = {
                                val trimmed = serverAddress.trim().trimEnd('/')
                                if (!trimmed.endsWith(":8096")) {
                                    serverAddress = "$trimmed:8096"
                                }
                            },
                            label = { Text(":8096") },
                        )
                    }
                    state.failureCode?.let { code ->
                        ErrorBannerWithCopy(
                            code = code,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } ?: run {
                        val errorText = state.error?.joinToString("\n") { it.asString(context.resources) }
                        if (!errorText.isNullOrBlank()) {
                            Text(
                                text = errorText,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                viewModel.onAction(AddServerAction.OnConnectClick(serverAddress))
                            },
                            enabled = !state.isLoading && serverAddress.isNotBlank(),
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text("Connect")
                        }
                        BeamBackAction(onClick = onBackClick)
                    }
                }
            }
        }
    }
}

@Composable
fun BeamUsersScreen(
    contentPadding: PaddingValues,
    serverId: String,
    serverName: String,
    serverAddress: String,
    onChangeServerClick: () -> Unit,
    onAddClick: () -> Unit,
    onNeedsLogin: (prefilledUsername: String?) -> Unit,
    onBrowseOfflineMedia: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedUser by remember { mutableStateOf<User?>(null) }
    var failureCode by remember { mutableStateOf<OnboardingFailureCode?>(null) }

    LaunchedEffect(serverId, serverName, serverAddress) {
        viewModel.loadUsers(
            serverId = serverId,
            serverName = serverName,
            serverAddress = serverAddress,
        )
    }
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            UsersEvent.UserActivated -> {
                failureCode = null
                // Authoritative SessionNavigationPolicy navigates to Home.
            }
            is UsersEvent.NeedsLogin -> {
                failureCode = null
                onNeedsLogin(event.username)
            }
            is UsersEvent.Failed -> {
                failureCode = event.code
            }
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Choose A User",
                body = (state.serverName ?: serverName).takeIf { it.isNotBlank() }?.let { "Connected to $it" }
                    ?: "Choose a user to continue.",
            )
        }
        failureCode?.let { code ->
            item {
                ErrorBannerWithCopy(
                    code = code,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (state.users.isEmpty() && state.publicUsers.isEmpty()) {
            item {
                BeamEmptyCard("No saved users are available for this server yet.")
            }
        } else {
            items(state.users, key = { it.id }) { user ->
                BeamUserCard(
                    title = user.name,
                    subtitle = "Saved user",
                    primaryLabel = "Continue",
                    onPrimaryClick = {
                        failureCode = null
                        viewModel.onAction(UsersAction.OnUserClick(user.id))
                    },
                    secondaryLabel = "Delete",
                    onSecondaryClick = { selectedUser = user },
                    avatarUri = userPrimaryImageUri(state.serverAddress ?: serverAddress, user.id),
                )
            }
            items(state.publicUsers, key = { it.id }) { user ->
                BeamUserCard(
                    title = user.name,
                    subtitle = "Public user",
                    primaryLabel = "Log In",
                    onPrimaryClick = { onNeedsLogin(user.name) },
                    secondaryLabel = null,
                    onSecondaryClick = null,
                    avatarUri = userPrimaryImageUri(state.serverAddress ?: serverAddress, user.id),
                )
            }
        }
        item {
            BeamActionRow(
                primaryLabel = "Add User",
                onPrimaryClick = onAddClick,
                secondaryLabel = "Change Server",
                onSecondaryClick = onChangeServerClick,
                tertiaryLabel = if (onLogout != null) "Sign Out" else null,
                onTertiaryClick = onLogout,
            )
        }
        if (onBrowseOfflineMedia != null) {
            item {
                OutlinedButton(
                    onClick = onBrowseOfflineMedia,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(SetupR.string.browse_downloads_and_local_media))
                }
            }
        }
    }

    if (selectedUser != null) {
        AlertDialog(
            onDismissRequest = { selectedUser = null },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onAction(UsersAction.OnDeleteUser(selectedUser!!.id))
                        selectedUser = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { selectedUser = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Remove User") },
            text = { Text("Remove ${selectedUser!!.name} from this device?") },
        )
    }
}

@Composable
fun BeamLoginScreen(
    contentPadding: PaddingValues,
    serverId: String,
    serverName: String,
    serverAddress: String,
    prefilledUsername: String? = null,
    initialFailureCode: OnboardingFailureCode? = null,
    onChangeServerClick: () -> Unit,
    onBackClick: () -> Unit,
    onBrowseOfflineMedia: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by rememberSaveable(serverId, serverAddress, prefilledUsername) {
        mutableStateOf(prefilledUsername.orEmpty())
    }
    var password by rememberSaveable(serverId, serverAddress, prefilledUsername) {
        mutableStateOf("")
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(serverId, serverName, serverAddress, prefilledUsername, initialFailureCode) {
        viewModel.bindTarget(
            serverId = serverId,
            serverName = serverName,
            serverAddress = serverAddress,
            prefilledUsername = prefilledUsername,
            initialFailureCode = initialFailureCode,
        )
    }
    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }
    LifecycleStartEffect(viewModel) {
        viewModel.onResumeOrStart()
        onStopOrDispose {
            viewModel.onPauseOrStop()
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Sign In",
                body = (state.serverName ?: serverName).takeIf { it.isNotBlank() }?.let { "Sign in to $it" }
                    ?: "Enter your credentials or use Quick Connect.",
            )
        }
        state.failureCode?.let { code ->
            item {
                ErrorBannerWithCopy(
                    code = code,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        item {
            BeamQuickConnectCard(
                quickConnectState = state.quickConnectState,
                onStart = { viewModel.onAction(LoginAction.OnStartQuickConnect) },
                onCancel = { viewModel.onAction(LoginAction.OnCancelQuickConnect) },
                onRetry = { viewModel.onAction(LoginAction.OnRetryQuickConnect) },
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Username & Password", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            viewModel.onAction(LoginAction.OnLoginClick(username, password))
                        }),
                        visualTransformation =
                            if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = {
                            if (state.failureCode == null) {
                                state.error?.let {
                                    Text(
                                        text = it.asString(),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                }
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                viewModel.onAction(LoginAction.OnLoginClick(username, password))
                            },
                            enabled = !state.isLoading && username.isNotBlank(),
                        ) {
                            Text(if (state.isLoading) "Signing In…" else "Sign In")
                        }
                        OutlinedButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide Password" else "Show Password")
                        }
                    }
                    if (!state.disclaimer.isNullOrBlank()) {
                        Text(
                            text = state.disclaimer!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            BeamActionRow(
                primaryLabel = "Change Server",
                onPrimaryClick = onChangeServerClick,
                secondaryLabel = "Back",
                onSecondaryClick = onBackClick,
            )
        }
        if (onBrowseOfflineMedia != null) {
            item {
                OutlinedButton(
                    onClick = onBrowseOfflineMedia,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(SetupR.string.browse_downloads_and_local_media))
                }
            }
        }
    }
}

@Composable
private fun BeamQuickConnectCard(
    quickConnectState: QuickConnectState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Quick Connect", style = MaterialTheme.typography.titleMedium)
            when (quickConnectState) {
                QuickConnectState.Idle -> {
                    Text(
                        text = "Approve this sign-in from another signed-in Jellyfin device using a 6-digit code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onStart) {
                        Text("Use Quick Connect")
                    }
                }
                QuickConnectState.Checking -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Starting Quick Connect…")
                    }
                }
                QuickConnectState.DisabledOnServer -> {
                    Text(
                        text = stringResource(SetupR.string.quick_connect_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                is QuickConnectState.Active -> {
                    val formattedCode =
                        if (quickConnectState.code.length == 6) {
                            "${quickConnectState.code.substring(0, 3)} ${quickConnectState.code.substring(3)}"
                        } else {
                            quickConnectState.code
                        }
                    val spokenCode = quickConnectState.code.toCharArray().joinToString(" ")
                    Text(
                        text = formattedCode,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics {
                            contentDescription = spokenCode
                        },
                    )
                    Text(
                        text = if (quickConnectState.rotatedNotice) {
                            stringResource(SetupR.string.quick_connect_rotated_notice)
                        } else {
                            stringResource(SetupR.string.quick_connect_instructions)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel Quick Connect")
                    }
                }
                is QuickConnectState.Authenticated -> {
                    Text(
                        text = "Approved — finishing sign-in…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                QuickConnectState.Expired -> {
                    Text(
                        text = stringResource(SetupR.string.quick_connect_expired),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = onRetry) {
                        Text(stringResource(SetupR.string.setup_new_code))
                    }
                }
                is QuickConnectState.Failed -> {
                    ErrorBannerWithCopy(
                        code = quickConnectState.code,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = onRetry) {
                        Text("Try Again")
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeamScreenHeader(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BeamUserCard(
    title: String,
    subtitle: String,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String?,
    onSecondaryClick: (() -> Unit)?,
    avatarUri: android.net.Uri? = null,
) {
    Card(
        onClick = onPrimaryClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUri != null) {
                    AsyncImage(
                        model = avatarUri,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPrimaryClick) {
                        Text(primaryLabel)
                    }
                    if (secondaryLabel != null && onSecondaryClick != null) {
                        OutlinedButton(onClick = onSecondaryClick) {
                            Text(secondaryLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamActionRow(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String,
    onSecondaryClick: () -> Unit,
    tertiaryLabel: String? = null,
    onTertiaryClick: (() -> Unit)? = null,
) {
    val hasTertiary = tertiaryLabel != null && onTertiaryClick != null

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onPrimaryClick,
            modifier = if (hasTertiary) Modifier.weight(1f) else Modifier,
        ) {
            BeamActionLabel(primaryLabel)
        }
        OutlinedButton(
            onClick = onSecondaryClick,
            modifier = if (hasTertiary) Modifier.weight(1f) else Modifier,
        ) {
            BeamActionLabel(secondaryLabel)
        }
        if (tertiaryLabel != null && onTertiaryClick != null) {
            OutlinedButton(
                onClick = onTertiaryClick,
                modifier = Modifier.weight(1f),
            ) {
                BeamActionLabel(tertiaryLabel)
            }
        }
    }
}

@Composable
private fun BeamActionLabel(label: String) {
    Text(
        text = label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun BeamEmptyCard(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(
            modifier = Modifier.padding(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
