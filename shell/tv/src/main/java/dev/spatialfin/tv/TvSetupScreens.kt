package dev.spatialfin.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.core.util.rememberQrBitmap
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionEvent
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionState
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionViewModel
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerAction
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerEvent
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerViewModel
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.setup.presentation.servers.ServersAction
import dev.jdtech.jellyfin.setup.presentation.servers.ServersEvent
import dev.jdtech.jellyfin.setup.presentation.servers.ServersViewModel
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@Composable
internal fun TvSourcePickerScreen(
    onConnectToServer: () -> Unit,
    onSetUpFromDevice: () -> Unit,
    onAddNetworkShare: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { primaryFocus.requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item {
            Text(
                text = "Welcome to SpatialFin",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Choose how you want to set up this TV.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = onConnectToServer,
                    modifier = Modifier.focusRequester(primaryFocus),
                ) {
                    Text(stringResource(SetupR.string.connect_to_jellyfin_server))
                }
                OutlinedButton(onClick = onSetUpFromDevice) {
                    Text(stringResource(SetupR.string.set_up_from_another_device))
                }
                OutlinedButton(onClick = onAddNetworkShare) {
                    Text(stringResource(SetupR.string.continue_without_server))
                }
            }
        }
    }
}

@Composable
internal fun TvServersScreen(
    onNavigateToUsers: (serverId: String, serverName: String, serverAddress: String) -> Unit,
    onAddServerClick: () -> Unit,
    onSetUpFromDeviceClick: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var failureCode by remember { mutableStateOf<OnboardingFailureCode?>(null) }
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.loadServers()
        runCatching { primaryFocus.requestFocus() }
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
                failureCode = null
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 64.dp, bottom = 48.dp),
    ) {
        item {
            Text(
                text = "Choose a Jellyfin server",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        failureCode?.let { code ->
            item {
                TvInlineErrorBanner(
                    message = OnboardingFailureMessages.message(context, code),
                    wireCode = code.wireCode,
                )
            }
        }
        if (state.servers.isEmpty()) {
            item {
                Text(
                    text = "No Jellyfin servers are saved on this TV yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.servers, key = { it.server.id }) { serverWithAddresses ->
                val isFirst = state.servers.firstOrNull()?.server?.id == serverWithAddresses.server.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(serverWithAddresses.server.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            serverWithAddresses.addresses.firstOrNull()?.address.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                failureCode = null
                                viewModel.onAction(ServersAction.OnServerClick(serverWithAddresses.server.id))
                            },
                            modifier = if (isFirst) Modifier.focusRequester(primaryFocus) else Modifier,
                        ) {
                            Text("Use server")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.onAction(ServersAction.DeleteServer(serverWithAddresses.server.id))
                            },
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onAddServerClick,
                    modifier = if (state.servers.isEmpty()) Modifier.focusRequester(primaryFocus) else Modifier,
                ) {
                    Text("Add server")
                }
                OutlinedButton(onClick = onSetUpFromDeviceClick) {
                    Text(stringResource(SetupR.string.set_up_from_another_device))
                }
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvConnectServerScreen(
    entryId: String,
    onSuccess: (serverId: String, serverName: String, serverAddress: String) -> Unit,
    onBack: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lanState by viewModel.lanSetupUiState.collectAsStateWithLifecycle()
    var serverAddress by rememberSaveable { mutableStateOf("") }
    val primaryFocus = remember { FocusRequester() }

    DisposableEffect(entryId) {
        val lease = viewModel.acquireBrowserLease(entryId = entryId, clearCompletedDrain = true)
        onDispose {
            viewModel.releaseBrowserLease(lease)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.discoverServers()
        runCatching { primaryFocus.requestFocus() }
    }
    ObserveAsEvents(viewModel.events) { event ->
        if (event is AddServerEvent.Success) {
            onSuccess(event.serverId, event.serverName, event.serverAddress)
        }
    }

    val qrBitmap = rememberQrBitmap(lanState.setupQrUrl.orEmpty(), size = 360)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 48.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(SetupR.string.connect_to_jellyfin_server),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Left column: Enter on another device (/setup QR + short URL + Setup code)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(SetupR.string.enter_on_another_device),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(12.dp),
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(196.dp),
                            )
                        }
                    }
                    lanState.setupUrl?.let { shortUrl ->
                        Text(
                            text = shortUrl,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    lanState.formattedManualCode?.let { manualCode ->
                        val spoken = manualCode.filter { it.isLetterOrDigit() }.toCharArray().joinToString(" ")
                        Text(
                            text = stringResource(SetupR.string.setup_code_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = manualCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { contentDescription = spoken },
                        )
                    }
                    if (lanState.manualCodeExpired) {
                        Text(
                            text = stringResource(SetupR.string.onboarding_err_pairing_code_expired),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    lanState.nsdWarning?.let { warn ->
                        Text(
                            text = OnboardingFailureMessages.message(context, warn),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    OutlinedButton(onClick = viewModel::regenerateManualCode) {
                        Text(stringResource(SetupR.string.setup_new_code))
                    }
                }

                // Right column: Discovered LAN servers + Manual URL entry
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(SetupR.string.setup_discovered_servers),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedButton(onClick = { viewModel.discoverServers() }) {
                            Text(stringResource(SetupR.string.setup_rescan_servers))
                        }
                    }
                    if (state.discoveredServers.isEmpty()) {
                        Text(
                            text = stringResource(SetupR.string.setup_scanning_servers),
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
                            ) {
                                Text("${server.name} • ${server.address}")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverAddress,
                        onValueChange = { serverAddress = it },
                        label = { androidx.compose.material3.Text("Server URL", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            viewModel.onAction(AddServerAction.OnConnectClick(serverAddress))
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocus),
                        colors = tvTextFieldColors(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val t = serverAddress.trim()
                                serverAddress = when {
                                    t.startsWith("https://") -> "http://" + t.removePrefix("https://")
                                    t.startsWith("http://") -> t
                                    else -> "http://$t"
                                }
                            },
                        ) { Text("http://") }
                        OutlinedButton(
                            onClick = {
                                val t = serverAddress.trim()
                                serverAddress = when {
                                    t.startsWith("http://") -> "https://" + t.removePrefix("http://")
                                    t.startsWith("https://") -> t
                                    else -> "https://$t"
                                }
                            },
                        ) { Text("https://") }
                        OutlinedButton(
                            onClick = {
                                val t = serverAddress.trim().trimEnd('/')
                                if (!t.endsWith(":8096")) serverAddress = "$t:8096"
                            },
                        ) { Text(":8096") }
                    }

                    state.failureCode?.let { code ->
                        TvInlineErrorBanner(
                            message = OnboardingFailureMessages.message(context, code),
                            wireCode = code.wireCode,
                        )
                    }

                    Button(
                        onClick = { viewModel.onAction(AddServerAction.OnConnectClick(serverAddress)) },
                        enabled = !state.isLoading && serverAddress.isNotBlank(),
                    ) {
                        Text(if (state.isLoading) "Connecting…" else "Connect")
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvLoginScreen(
    serverId: String,
    serverName: String,
    serverAddress: String,
    prefilledUsername: String? = null,
    lanSetupSessionId: String? = null,
    initialFailureCode: OnboardingFailureCode? = null,
    onChangeServerClick: () -> Unit,
    onBack: () -> Unit,
    onBrowseOfflineMedia: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(key = "tv-login:$serverId:${lanSetupSessionId.orEmpty()}"),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by rememberSaveable(serverId, serverAddress, prefilledUsername) {
        mutableStateOf(prefilledUsername.orEmpty())
    }
    var password by rememberSaveable(serverId, serverAddress, prefilledUsername) {
        mutableStateOf("")
    }
    val qcActionFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }

    LaunchedEffect(serverId, serverName, serverAddress, prefilledUsername, lanSetupSessionId, initialFailureCode) {
        viewModel.bindTarget(
            serverId = serverId,
            serverName = serverName,
            serverAddress = serverAddress,
            prefilledUsername = prefilledUsername,
            lanSetupSessionId = lanSetupSessionId,
            initialFailureCode = initialFailureCode,
        )
    }
    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
        runCatching { qcActionFocus.requestFocus() }
    }
    LaunchedEffect(state.quickConnectState) {
        when (state.quickConnectState) {
            QuickConnectState.DisabledOnServer -> runCatching { usernameFocus.requestFocus() }
            QuickConnectState.Expired, is QuickConnectState.Failed -> runCatching { qcActionFocus.requestFocus() }
            else -> Unit
        }
    }

    if (lanSetupSessionId != null) {
        DisposableEffect(lanSetupSessionId) {
            val lease = viewModel.acquireBrowserKeepAliveLease()
            onDispose { viewModel.releaseBrowserKeepAliveLease(lease) }
        }
    } else {
        LifecycleStartEffect(viewModel) {
            viewModel.onResumeOrStart()
            onStopOrDispose {
                viewModel.onPauseOrStop()
            }
        }
    }

    val handleBack = {
        if (lanSetupSessionId != null) {
            viewModel.cancelActiveLanSessionOnBack()
        }
        onBack()
    }
    BackHandler(onBack = handleBack)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 48.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(SetupR.string.sign_in),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = (state.serverName ?: serverName).takeIf { it.isNotBlank() }?.let { "Sign in to $it" }
                            ?: serverAddress,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onChangeServerClick) { Text("Change server") }
                    OutlinedButton(onClick = handleBack) { Text("Back") }
                }
            }
        }

        state.failureCode?.let { code ->
            item {
                TvInlineErrorBanner(
                    message = OnboardingFailureMessages.message(context, code),
                    wireCode = code.wireCode,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Quick Connect primary pane
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Quick Connect", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    when (val qc = state.quickConnectState) {
                        QuickConnectState.Idle -> {
                            Text(
                                text = "Approve sign-in from your phone or web browser with a 6-digit code.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { viewModel.onAction(LoginAction.OnStartQuickConnect) },
                                modifier = Modifier.focusRequester(qcActionFocus),
                            ) {
                                Text("Get Quick Connect code")
                            }
                        }
                        QuickConnectState.Checking -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(stringResource(SetupR.string.quick_connect_checking))
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
                            val grouped = if (qc.code.length == 6) {
                                "${qc.code.substring(0, 3)} ${qc.code.substring(3)}"
                            } else {
                                qc.code
                            }
                            val spoken = qc.code.toCharArray().joinToString(" ")
                            Text(
                                text = grouped,
                                style = MaterialTheme.typography.displayMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { contentDescription = spoken },
                            )
                            Text(
                                text = if (qc.rotatedNotice) {
                                    stringResource(SetupR.string.quick_connect_rotated_notice)
                                } else {
                                    stringResource(SetupR.string.quick_connect_instructions)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            OutlinedButton(
                                onClick = { viewModel.onAction(LoginAction.OnCancelQuickConnect) },
                                modifier = Modifier.focusRequester(qcActionFocus),
                            ) {
                                Text("Cancel code")
                            }
                        }
                        is QuickConnectState.Authenticated -> {
                            Text(
                                text = "Approved — finishing sign-in…",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        QuickConnectState.Expired -> {
                            Text(
                                text = stringResource(SetupR.string.quick_connect_expired),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Button(
                                onClick = { viewModel.onAction(LoginAction.OnRetryQuickConnect) },
                                modifier = Modifier.focusRequester(qcActionFocus),
                            ) {
                                Text(stringResource(SetupR.string.setup_new_code))
                            }
                        }
                        is QuickConnectState.Failed -> {
                            TvInlineErrorBanner(
                                message = OnboardingFailureMessages.message(context, qc.code),
                                wireCode = qc.code.wireCode,
                            )
                            Button(
                                onClick = { viewModel.onAction(LoginAction.OnRetryQuickConnect) },
                                modifier = Modifier.focusRequester(qcActionFocus),
                            ) {
                                Text("Try again")
                            }
                        }
                    }
                }

                // Username & password fallback pane
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Username & Password", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { androidx.compose.material3.Text("Username", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(usernameFocus),
                        colors = tvTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { androidx.compose.material3.Text("Password", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            viewModel.onAction(LoginAction.OnLoginClick(username, password))
                        }),
                        modifier = Modifier.fillMaxWidth(),
                        colors = tvTextFieldColors(),
                    )
                    Button(
                        onClick = { viewModel.onAction(LoginAction.OnLoginClick(username, password)) },
                        enabled = !state.isLoading && username.isNotBlank(),
                    ) {
                        Text(if (state.isLoading) "Signing in…" else stringResource(SetupR.string.sign_in))
                    }
                    if (onBrowseOfflineMedia != null) {
                        OutlinedButton(onClick = onBrowseOfflineMedia) {
                            Text(stringResource(SetupR.string.browse_downloads_and_local_media))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvSetUpFromDeviceScreen(
    entryId: String,
    clearCompletedDrain: Boolean,
    onBack: () -> Unit,
    onBrokerFallbackToLogin: (
        serverId: String,
        serverName: String,
        serverAddress: String,
        prefilledUsername: String?,
        failureCode: OnboardingFailureCode,
    ) -> Unit,
    viewModel: CompanionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lanState by viewModel.lanSetupUiState.collectAsStateWithLifecycle()
    val pendingHandoff by viewModel.pendingHandoffSelection.collectAsStateWithLifecycle()
    val connectedUrl by viewModel.connectedCompanionUrl.collectAsStateWithLifecycle()
    val primaryFocus = remember { FocusRequester() }
    val pickerFocus = remember { FocusRequester() }

    DisposableEffect(entryId, clearCompletedDrain) {
        val lease = viewModel.acquireReceiverLease(
            entryId = entryId,
            clearCompletedDrain = clearCompletedDrain,
        )
        onDispose {
            viewModel.releaseReceiverLease(lease)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
        runCatching { primaryFocus.requestFocus() }
    }
    LaunchedEffect(pendingHandoff) {
        if (pendingHandoff != null) {
            runCatching { pickerFocus.requestFocus() }
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is CompanionEvent.Activated -> viewModel.reset()
            is CompanionEvent.BrokerFallbackToLogin -> {
                viewModel.reset()
                onBrokerFallbackToLogin(
                    event.serverId,
                    event.serverName,
                    event.serverAddress,
                    event.prefilledUsername,
                    event.failureCode,
                )
            }
        }
    }

    if (pendingHandoff != null) {
        BackHandler {
            viewModel.cancelPendingUserSelection()
        }
    }

    val receiverQr = rememberQrBitmap(lanState.receiverPairingQrJson.orEmpty(), size = 360)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 48.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(SetupR.string.set_up_from_another_device),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }

        lanState.errorCode?.let { code ->
            item {
                TvInlineErrorBanner(
                    message = OnboardingFailureMessages.message(context, code),
                    wireCode = code.wireCode,
                )
            }
        }
        if (state is CompanionState.Error) {
            val err = state as CompanionState.Error
            item {
                val msg = err.code?.let { OnboardingFailureMessages.message(context, it) } ?: err.message
                TvInlineErrorBanner(message = msg, wireCode = err.code?.wireCode)
            }
        }

        val handoffSelection = pendingHandoff
        if (handoffSelection != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(SetupR.string.setup_choose_user_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(SetupR.string.setup_choose_user_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    handoffSelection.candidates.forEachIndexed { idx, candidate ->
                        Button(
                            onClick = {
                                viewModel.completePendingUserSelection(
                                    serverId = candidate.serverId,
                                    userId = candidate.userId,
                                )
                            },
                            enabled = !lanState.isCompletingUserSelection,
                            modifier = if (idx == 0) Modifier.focusRequester(pickerFocus) else Modifier,
                        ) {
                            Text("${candidate.username} • ${candidate.serverName}")
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelPendingUserSelection,
                        enabled = !lanState.isCompletingUserSelection,
                    ) {
                        Text("Cancel")
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Scan from SpatialFin Companion",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (receiverQr != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .padding(12.dp),
                            ) {
                                Image(
                                    bitmap = receiverQr,
                                    contentDescription = null,
                                    modifier = Modifier.size(216.dp),
                                )
                            }
                        }
                        lanState.formattedManualCode?.let { code ->
                            val spoken = code.filter { it.isLetterOrDigit() }.toCharArray().joinToString(" ")
                            Text(
                                text = stringResource(SetupR.string.companion_pairing_code_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = code,
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { contentDescription = spoken },
                            )
                        }
                        if (lanState.manualCodeExpired) {
                            Text(
                                text = stringResource(SetupR.string.onboarding_err_pairing_code_expired),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        Button(
                            onClick = viewModel::regenerateManualCode,
                            modifier = Modifier.focusRequester(primaryFocus),
                        ) {
                            Text(stringResource(SetupR.string.setup_new_code))
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.companion_server_label),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!connectedUrl.isNullOrBlank()) {
                            Text(
                                text = connectedUrl.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = viewModel::syncNow,
                                    enabled = state !is CompanionState.Fetching,
                                ) {
                                    Text(stringResource(SetupR.string.companion_sync_now))
                                }
                                OutlinedButton(
                                    onClick = viewModel::disconnect,
                                    enabled = state !is CompanionState.Fetching,
                                ) {
                                    Text(stringResource(SetupR.string.companion_disconnect))
                                }
                            }
                        } else {
                            Text(
                                text = "Open SpatialFin Companion on your phone or computer and choose \"Set up a device\" to send your server and settings to this TV.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(SetupR.string.companion_install_help_tv_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvInlineErrorBanner(
    message: String,
    wireCode: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!wireCode.isNullOrBlank()) {
            Text(
                text = "Code: $wireCode",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun tvTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.border,
    )
