package dev.jdtech.jellyfin.presentation.setup.addserver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.util.rememberQrBitmap
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.setup.components.DiscoveredServerItem
import dev.jdtech.jellyfin.presentation.setup.components.LoadingButton
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerAction
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerEvent
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerState
import dev.jdtech.jellyfin.setup.presentation.addserver.AddServerViewModel
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.spatialfin.presentation.theme.SpatialFinTheme

@Composable
fun AddServerScreen(
    entryId: String,
    onSuccess: (serverId: String, serverName: String, serverAddress: String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: AddServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lanState by viewModel.lanSetupUiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.discoverServers() }

    DisposableEffect(entryId) {
        val lease = viewModel.acquireBrowserLease(entryId, clearCompletedDrain = true)
        onDispose {
            viewModel.releaseBrowserLease(lease, LanSetupController.DEFAULT_RELEASE_GRACE_MS)
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is AddServerEvent.Success ->
                onSuccess(event.serverId, event.serverName, event.serverAddress)
        }
    }

    AddServerScreenLayout(
        state = state,
        lanState = lanState,
        onRescan = { viewModel.discoverServers() },
        onRegenerateCode = { viewModel.regenerateManualCode() },
        onAction = { action ->
            when (action) {
                is AddServerAction.OnBackClick -> onBackClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

@Composable
private fun AddServerScreenLayout(
    state: AddServerState,
    lanState: LanSetupUiState,
    onRescan: () -> Unit,
    onRegenerateCode: () -> Unit,
    onAction: (AddServerAction) -> Unit,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    var serverAddress by rememberSaveable { mutableStateOf("") }

    val doConnect = { onAction(AddServerAction.OnConnectClick(serverAddress)) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    RootLayout {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier.fillMaxHeight()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .verticalScroll(scrollState),
        ) {
            Spacer(modifier = Modifier.height(40.dp))
            Image(
                painter = painterResource(id = SetupR.drawable.ic_beam_launcher),
                contentDescription = null,
                modifier = Modifier.width(260.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                text = stringResource(SetupR.string.add_server),
                style = MaterialTheme.typography.displaySmall,
            )

            // Discovered LAN servers (UDP 7359)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        if (state.isDiscovering) {
                            stringResource(SetupR.string.setup_scanning_servers)
                        } else {
                            stringResource(SetupR.string.setup_discovered_servers)
                        },
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onRescan, enabled = !state.isDiscovering) {
                    Text(stringResource(SetupR.string.setup_rescan_servers))
                }
            }
            AnimatedVisibility(state.discoveredServers.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.discoveredServers) { discoveredServer ->
                        DiscoveredServerItem(
                            name = discoveredServer.name,
                            onClick = {
                                serverAddress = discoveredServer.address
                                onAction(AddServerAction.OnConnectClick(discoveredServer.address))
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            // Manual URL input + shortcut chips
            OutlinedTextField(
                value = serverAddress,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_server),
                        contentDescription = null,
                    )
                },
                onValueChange = { serverAddress = it },
                label = { Text(text = stringResource(SetupR.string.edit_text_server_address_hint)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { doConnect() }),
                isError = state.failureCode != null || state.error != null,
                enabled = !state.isLoading,
                modifier =
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = 72.dp)
                        .focusRequester(focusRequester),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val stripped = serverAddress.trim().removePrefix("http://").removePrefix("https://")
                        serverAddress = "http://$stripped"
                    },
                    label = { Text("http://") },
                )
                AssistChip(
                    onClick = {
                        val stripped = serverAddress.trim().removePrefix("http://").removePrefix("https://")
                        serverAddress = "https://$stripped"
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
                ErrorBannerWithCopy(context = context, code = code)
            }

            LoadingButton(
                text = stringResource(SetupR.string.add_server_btn_connect),
                onClick = { doConnect() },
                isLoading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )

            // Enter on another device (/setup QR + manual code)
            if (lanState.isRunning && (lanState.setupUrl != null || lanState.manualCodeExpired)) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(SetupR.string.enter_on_another_device),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val qrUrl = lanState.setupQrUrl
                        val qrBitmap = qrUrl?.let { rememberQrBitmap(it, size = 440) }
                        if (qrBitmap != null) {
                            Box(
                                modifier =
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(14.dp),
                            ) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(196.dp),
                                )
                            }
                        }
                        lanState.setupUrl?.let { url ->
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        lanState.formattedManualCode?.let { code ->
                            Text(
                                text = stringResource(SetupR.string.setup_code_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val spokenCode = QuickConnectController.formatSpacedForAccessibility(code)
                            Text(
                                text = code,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { contentDescription = spokenCode },
                            )
                        }
                        if (lanState.manualCodeExpired) {
                            Text(
                                text = OnboardingFailureMessages.message(
                                    context,
                                    OnboardingFailureCode.PAIRING_CODE_EXPIRED,
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Button(onClick = onRegenerateCode) {
                                Text(stringResource(SetupR.string.setup_new_code))
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = { onAction(AddServerAction.OnBackClick) },
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(painter = painterResource(CoreR.drawable.ic_arrow_left), contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@PreviewScreenSizes
@Composable
private fun AddServerScreenLayoutPreview() {
    SpatialFinTheme {
        AddServerScreenLayout(
            state = AddServerState(),
            lanState = LanSetupUiState(),
            onRescan = {},
            onRegenerateCode = {},
            onAction = {},
        )
    }
}
