package dev.spatialfin.beam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionEvent
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionScanner
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionState
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionViewModel
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.presentation.setup.welcome.isCompanionCameraAvailable
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@Composable
fun BeamCompanionScreen(
    contentPadding: PaddingValues,
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
    val connectedUrl by viewModel.connectedCompanionUrl.collectAsStateWithLifecycle()
    val cameraAvailable = isCompanionCameraAvailable(context)

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is CompanionEvent.Activated -> {
                // Dismiss transient scanner/picker UI only; SessionNavigationPolicy owns Home navigation.
                viewModel.reset()
            }
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

    if (state is CompanionState.NeedsUserSelection) {
        BackHandler {
            viewModel.cancelQrUserSelection()
        }
    } else if (state is CompanionState.Scanning) {
        BackHandler {
            viewModel.reset()
        }
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = stringResource(SetupR.string.set_up_from_another_device),
                body = "Scan the SpatialFin Companion QR code to import your server, user, preferences, and sync settings.",
            )
        }

        if (!connectedUrl.isNullOrBlank()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Connected Companion",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = connectedUrl.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = viewModel::syncNow,
                                enabled = state !is CompanionState.Fetching,
                            ) {
                                Text("Sync now")
                            }
                            OutlinedButton(
                                onClick = viewModel::disconnect,
                                enabled = state !is CompanionState.Fetching,
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }
        }

        when (val current = state) {
            CompanionState.Idle -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Scan a QR code from SpatialFin Companion or pair using your saved Companion server.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (cameraAvailable) {
                                    Button(onClick = viewModel::startScanning) {
                                        Text("Scan QR")
                                    }
                                }
                                OutlinedButton(
                                    onClick = viewModel::pairWithSavedCompanion,
                                    enabled = !connectedUrl.isNullOrBlank(),
                                ) {
                                    Text("Use Saved Companion")
                                }
                            }
                            BeamBackAction(onClick = onBack)
                        }
                    }
                }
                item {
                    val uriHandler = LocalUriHandler.current
                    val installUrl = stringResource(SetupR.string.companion_install_url)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(SetupR.string.companion_install_help_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(SetupR.string.companion_install_help_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { runCatching { uriHandler.openUri(installUrl) } },
                            ) {
                                Text(stringResource(SetupR.string.companion_install_help_action))
                            }
                        }
                    }
                }
            }
            CompanionState.Scanning -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Scan the QR code from the Companion app.", style = MaterialTheme.typography.bodyLarge)
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(360.dp)
                                        .padding(top = 8.dp),
                            ) {
                                CompanionScanner(
                                    onPayloadFound = { payload ->
                                        viewModel.fetchAndApplyConfig(payload)
                                    },
                                )
                            }
                            BeamBackAction(
                                label = "Cancel",
                                onClick = { viewModel.reset() },
                            )
                        }
                    }
                }
            }
            CompanionState.Fetching -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text("Fetching and applying Companion config…", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            is CompanionState.NeedsUserSelection -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Choose an account", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Multiple Jellyfin accounts are available in Companion. Pick one to finish signing in.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            current.errorCode?.let { code ->
                                ErrorBannerWithCopy(
                                    code = code,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            current.candidates.forEach { candidate ->
                                Button(
                                    onClick = {
                                        viewModel.completeQrUserSelection(
                                            serverId = candidate.serverId,
                                            userId = candidate.userId,
                                        )
                                    },
                                    enabled = !current.isCompleting,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${candidate.username} • ${candidate.serverName}")
                                }
                            }
                            OutlinedButton(
                                onClick = viewModel::cancelQrUserSelection,
                                enabled = !current.isCompleting,
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
            CompanionState.Success -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(20.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Setup complete!", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Your Companion configuration has been applied.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            BeamBackAction(onClick = onBack)
                        }
                    }
                }
            }
            is CompanionState.Error -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Companion import failed",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            if (current.code != null) {
                                ErrorBannerWithCopy(
                                    code = current.code!!,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            } else {
                                Text(
                                    current.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (cameraAvailable) {
                                    Button(onClick = viewModel::startScanning) {
                                        Text("Try Again")
                                    }
                                }
                                if (!connectedUrl.isNullOrBlank()) {
                                    OutlinedButton(onClick = viewModel::pairWithSavedCompanion) {
                                        Text("Retry Saved Companion")
                                    }
                                }
                            }
                            BeamBackAction(onClick = onBack)
                        }
                    }
                }
            }
        }
    }
}
