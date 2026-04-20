package dev.spatialfin.beam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerAction
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerEvent
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerState
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerViewModel
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginEvent
import dev.jdtech.jellyfin.setup.presentation.login.LoginState
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.setup.presentation.servers.ServersAction
import dev.jdtech.jellyfin.setup.presentation.servers.ServersEvent
import dev.jdtech.jellyfin.setup.presentation.servers.ServersState
import dev.jdtech.jellyfin.setup.presentation.servers.ServersViewModel
import dev.jdtech.jellyfin.setup.presentation.users.UsersAction
import dev.jdtech.jellyfin.setup.presentation.users.UsersEvent
import dev.jdtech.jellyfin.setup.presentation.users.UsersState
import dev.jdtech.jellyfin.setup.presentation.users.UsersViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun BeamServersScreen(
    contentPadding: PaddingValues,
    onServerSelected: () -> Unit,
    onAddServerClick: () -> Unit,
    onCompanionImportClick: () -> Unit,
    onResetOnboarding: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadServers()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            if (event is ServersEvent.ServerChanged) {
                onServerSelected()
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
                                    viewModel.onAction(ServersAction.OnServerClick(server.server.id))
                                }
                            ) {
                                Text("Use Server")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.onAction(ServersAction.DeleteServer(server.server.id))
                                }
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
                secondaryLabel = "Use Companion",
                onSecondaryClick = onCompanionImportClick,
                tertiaryLabel = "Reset Onboarding",
                onTertiaryClick = onResetOnboarding,
            )
        }
    }
}

@Composable
fun BeamAddServerScreen(
    contentPadding: PaddingValues,
    onSuccess: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var serverAddress by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.discoverServers()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            if (event is AddServerEvent.Success) {
                onSuccess()
            }
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Add A Server",
                body = "Enter a server address or choose a discovered server.",
            )
        }
        if (state.discoveredServers.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Discovered Servers", style = MaterialTheme.typography.titleLarge)
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
                        supportingText = {
                            val errorText =
                                state.error?.joinToString("\n") { it.asString(context.resources) }
                            if (!errorText.isNullOrBlank()) {
                                Text(
                                    text = errorText,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                viewModel.onAction(AddServerAction.OnConnectClick(serverAddress))
                            },
                            enabled = !state.isLoading,
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text("Connect")
                        }
                        OutlinedButton(onClick = onBackClick) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BeamUsersScreen(
    contentPadding: PaddingValues,
    onNavigateToHome: () -> Unit,
    onChangeServerClick: () -> Unit,
    onAddClick: () -> Unit,
    onPublicUserClick: (String?) -> Unit,
    onResetOnboarding: () -> Unit,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedUser by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadUsers()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            if (event is UsersEvent.NavigateToHome) {
                onNavigateToHome()
            }
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Choose A User",
                body = state.serverName?.let { "Connected to $it" } ?: "Choose a user to continue.",
            )
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
                        viewModel.onAction(UsersAction.OnUserClick(user.id))
                    },
                    secondaryLabel = "Delete",
                    onSecondaryClick = { selectedUser = user },
                    avatarUri = dev.jdtech.jellyfin.core.presentation.components
                        .userPrimaryImageUri(state.serverAddress, user.id),
                )
            }
            items(state.publicUsers, key = { it.id }) { user ->
                BeamUserCard(
                    title = user.name,
                    subtitle = "Public user",
                    primaryLabel = "Log In",
                    onPrimaryClick = { onPublicUserClick(user.name) },
                    secondaryLabel = null,
                    onSecondaryClick = null,
                    avatarUri = dev.jdtech.jellyfin.core.presentation.components
                        .userPrimaryImageUri(state.serverAddress, user.id),
                )
            }
        }
        item {
            BeamActionRow(
                primaryLabel = "Add User",
                onPrimaryClick = onAddClick,
                secondaryLabel = "Change Server",
                onSecondaryClick = onChangeServerClick,
            )
        }
        item {
            OutlinedButton(onClick = onResetOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Reset Onboarding")
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
                    }
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
    prefilledUsername: String?,
    onSuccess: () -> Unit,
    onChangeServerClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by rememberSaveable(prefilledUsername) { mutableStateOf(prefilledUsername.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadServer()
        viewModel.loadDisclaimer()
        viewModel.loadQuickConnectEnabled()
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            if (event is LoginEvent.Success) {
                onSuccess()
            }
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Log In",
                body = state.serverName?.let { "Sign in to $it" } ?: "Enter your credentials.",
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                            state.error?.let {
                                Text(
                                    text = it.asString(),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                viewModel.onAction(LoginAction.OnLoginClick(username, password))
                            },
                            enabled = !state.isLoading,
                        ) {
                            Text(if (state.isLoading) "Logging In..." else "Log In")
                        }
                        OutlinedButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide Password" else "Show Password")
                        }
                    }
                    if (state.quickConnectEnabled) {
                        OutlinedButton(
                            onClick = { viewModel.onAction(LoginAction.OnQuickConnectClick) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(state.quickConnectCode ?: "Quick Connect")
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
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                if (avatarUri != null) {
                    coil3.compose.AsyncImage(
                        model = avatarUri,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPrimaryClick) {
            Text(primaryLabel)
        }
        OutlinedButton(onClick = onSecondaryClick) {
            Text(secondaryLabel)
        }
        if (tertiaryLabel != null && onTertiaryClick != null) {
            OutlinedButton(onClick = onTertiaryClick) {
                Text(tertiaryLabel)
            }
        }
    }
}

@Composable
internal fun BeamEmptyCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(
            modifier = Modifier.padding(20.dp),
            contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
