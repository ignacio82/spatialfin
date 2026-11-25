package dev.jdtech.jellyfin.presentation.setup.welcome

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.util.rememberQrBitmap
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.onboarding.CompanionRecoveryAction
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.onboarding.companionCandidateStatusRes
import dev.jdtech.jellyfin.setup.onboarding.openCompanionUrlInBrowser
import dev.jdtech.jellyfin.setup.onboarding.resolveCompanionRecoveryAction
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import java.util.Date
import java.util.UUID

@Composable
fun SetUpFromDeviceScreen(
    entryId: String,
    clearCompletedDrain: Boolean = true,
    onNavigateToLogin: (
        serverId: String,
        serverName: String,
        serverAddress: String,
        prefilledUsername: String?,
        failureCode: OnboardingFailureCode,
    ) -> Unit,
    onBackClick: () -> Unit,
    viewModel: CompanionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val companionState by viewModel.state.collectAsStateWithLifecycle()
    val lanState by viewModel.lanSetupUiState.collectAsStateWithLifecycle()
    val pendingHandoff by viewModel.pendingHandoffSelection.collectAsStateWithLifecycle()
    val connectedUrl by viewModel.connectedCompanionUrl.collectAsStateWithLifecycle()
    val lastSyncMs by viewModel.lastSyncTimestampMs.collectAsStateWithLifecycle()
    val cameraAvailable = remember(context) { isCompanionCameraAvailable(context) }

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    DisposableEffect(entryId, clearCompletedDrain) {
        val lease = viewModel.acquireReceiverLease(entryId, clearCompletedDrain)
        onDispose {
            viewModel.releaseReceiverLease(lease, LanSetupController.DEFAULT_RELEASE_GRACE_MS)
        }
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is CompanionEvent.Activated -> {
                // Dismiss transient scanner/picker UI only; SessionNavigationPolicy handles root navigation.
                viewModel.reset()
            }
            is CompanionEvent.BrokerFallbackToLogin -> {
                onNavigateToLogin(
                    event.serverId,
                    event.serverName,
                    event.serverAddress,
                    event.prefilledUsername,
                    event.failureCode,
                )
            }
        }
    }

    val handleBack = {
        when {
            pendingHandoff != null -> viewModel.cancelPendingUserSelection()
            companionState is CompanionState.NeedsUserSelection -> viewModel.cancelQrUserSelection()
            companionState is CompanionState.Scanning -> viewModel.reset()
            else -> onBackClick()
        }
    }

    BackHandler(onBack = handleBack)

    val scrollState = rememberScrollState()
    val activeCandidates: List<CompanionCandidate>? =
        pendingHandoff?.candidates
            ?: (companionState as? CompanionState.NeedsUserSelection)?.candidates
    val isHandoffSelection = pendingHandoff != null
    val isCompletingSelection =
        if (isHandoffSelection) {
            lanState.isCompletingUserSelection
        } else {
            (companionState as? CompanionState.NeedsUserSelection)?.isCompleting == true
        }
    val selectionErrorCode: OnboardingFailureCode? =
        if (isHandoffSelection) {
            lanState.errorCode
        } else {
            (companionState as? CompanionState.NeedsUserSelection)?.errorCode
        }

    RootLayout {
        Column(
            modifier =
                Modifier.fillMaxHeight()
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = stringResource(SetupR.string.set_up_from_another_device),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(SetupR.string.set_up_from_another_device_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // User selection prompt (either LAN handoff or camera QR)
            if (activeCandidates != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.setup_choose_user_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(SetupR.string.setup_choose_user_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selectionErrorCode != null) {
                            ErrorBannerWithCopy(
                                context = context,
                                code = selectionErrorCode,
                                recoveryAction =
                                    resolveCompanionRecoveryAction(
                                        code = selectionErrorCode,
                                        companionBaseUrl = connectedUrl,
                                        isCompanionContext = true,
                                    ),
                                onRetryCompanion = if (!connectedUrl.isNullOrBlank()) viewModel::pairWithSavedCompanion else null,
                                isRetryingCompanion = companionState is CompanionState.Fetching,
                            )
                        }
                        if (isCompletingSelection) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text(text = stringResource(SetupR.string.quick_connect_checking))
                            }
                        } else {
                            activeCandidates.forEach { candidate ->
                                val statusText = stringResource(companionCandidateStatusRes(candidate))
                                val onSelectCandidate = {
                                    if (isHandoffSelection) {
                                        viewModel.completePendingUserSelection(
                                            serverId = candidate.serverId,
                                            userId = candidate.userId,
                                            username = candidate.username,
                                        )
                                    } else {
                                        viewModel.completeQrUserSelection(
                                            serverId = candidate.serverId,
                                            userId = candidate.userId,
                                            username = candidate.username,
                                        )
                                    }
                                }
                                if (candidate.requiresCredentialUpdate) {
                                    OutlinedButton(
                                        onClick = onSelectCandidate,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = "${candidate.username} — ${candidate.serverName}",
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onSelectCandidate,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = "${candidate.username} — ${candidate.serverName}",
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                            )
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = {
                                    if (isHandoffSelection) {
                                        viewModel.cancelPendingUserSelection()
                                    } else {
                                        viewModel.cancelQrUserSelection()
                                    }
                                },
                            ) {
                                Text(stringResource(SetupR.string.cancel))
                            }
                        }
                    }
                }
            } else if (companionState is CompanionState.Scanning) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    CompanionScanner(
                        onPayloadFound = { payload -> viewModel.fetchAndApplyConfig(payload) },
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(SetupR.string.cancel))
                }
            } else {
                // Receiver pairing QR + manual Companion pairing code
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val qrJson = lanState.receiverPairingQrJson
                        val qrBitmap = qrJson?.let { rememberQrBitmap(it, size = 480) }
                        if (qrBitmap != null) {
                            Box(
                                modifier =
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(16.dp),
                            ) {
                                Image(
                                    bitmap = qrBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(220.dp),
                                )
                            }
                        }

                        val manualCode = lanState.formattedManualCode
                        if (!manualCode.isNullOrBlank()) {
                            Text(
                                text = stringResource(SetupR.string.companion_pairing_code_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val spokenCode = QuickConnectController.formatSpacedForAccessibility(manualCode)
                            Text(
                                text = manualCode,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { contentDescription = spokenCode },
                            )
                            Text(
                                text = stringResource(SetupR.string.companion_manual_code_version_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            Button(onClick = { viewModel.regenerateManualCode() }) {
                                Text(stringResource(SetupR.string.setup_new_code))
                            }
                        }

                        lanState.nsdWarning?.let { warning ->
                            Text(
                                text = OnboardingFailureMessages.message(context, warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }

                // Connected Companion status & actions
                if (!connectedUrl.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(SetupR.string.companion_server_label),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = connectedUrl.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (lastSyncMs > 0L) {
                                val date = Date(lastSyncMs)
                                val formatted =
                                    DateFormat.getMediumDateFormat(context).format(date) +
                                        " " +
                                        DateFormat.getTimeFormat(context).format(date)
                                Text(
                                    text = "Last sync: $formatted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { viewModel.syncNow() },
                                    enabled = companionState !is CompanionState.Fetching,
                                ) {
                                    Text(stringResource(SetupR.string.companion_sync_now))
                                }
                                OutlinedButton(
                                    onClick = { viewModel.pairWithSavedCompanion() },
                                    enabled = companionState !is CompanionState.Fetching,
                                ) {
                                    Text(stringResource(SetupR.string.setup_use_saved_companion))
                                }
                                TextButton(onClick = { viewModel.disconnect() }) {
                                    Text(stringResource(SetupR.string.companion_disconnect))
                                }
                            }
                        }
                    }
                }

                // Camera QR scanning (gated on camera availability)
                if (cameraAvailable) {
                    OutlinedButton(
                        onClick = { viewModel.startScanning() },
                        enabled = companionState !is CompanionState.Fetching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(SetupR.string.setup_scan_qr))
                    }
                }

                when (val s = companionState) {
                    CompanionState.Fetching -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(text = stringResource(SetupR.string.quick_connect_checking))
                        }
                    }
                    is CompanionState.Error -> {
                        val code = s.code ?: OnboardingFailureCode.SERVER_HTTP_ERROR
                        ErrorBannerWithCopy(
                            context = context,
                            code = code,
                            detail = s.message,
                            recoveryAction =
                                resolveCompanionRecoveryAction(
                                    code = code,
                                    companionBaseUrl = connectedUrl,
                                    isCompanionContext = true,
                                ),
                            onRetryCompanion = if (!connectedUrl.isNullOrBlank()) viewModel::pairWithSavedCompanion else null,
                            isRetryingCompanion = false,
                        )
                    }
                    else -> Unit
                }

                // Install instructions callout for users who haven't set up SpatialFin Companion yet
                val uriHandler = LocalUriHandler.current
                val installUrl = stringResource(SetupR.string.companion_install_url)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.companion_install_help_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(SetupR.string.companion_install_help_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri(installUrl) } },
                        ) {
                            Text(stringResource(SetupR.string.companion_install_help_action))
                        }
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = handleBack,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(painter = painterResource(CoreR.drawable.ic_arrow_left), contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun ErrorBannerWithCopy(
    context: Context = LocalContext.current,
    code: OnboardingFailureCode,
    detail: String? = null,
    recoveryAction: CompanionRecoveryAction? = null,
    onRetryCompanion: (() -> Unit)? = null,
    isRetryingCompanion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = OnboardingFailureMessages.message(context, code),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (recoveryAction != null) {
                Text(
                    text = stringResource(recoveryAction.hintRes),
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = code.wireCode,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
            if (recoveryAction != null) {
                FilledTonalButton(
                    onClick = { openCompanionUrlInBrowser(context, recoveryAction.targetUrl) },
                ) {
                    Text(stringResource(recoveryAction.labelRes))
                }
            }
            if (onRetryCompanion != null) {
                OutlinedButton(
                    onClick = onRetryCompanion,
                    enabled = !isRetryingCompanion,
                ) {
                    Text(
                        stringResource(
                            if (isRetryingCompanion) {
                                SetupR.string.companion_recovery_checking
                            } else {
                                SetupR.string.companion_recovery_retry_check
                            },
                        ),
                    )
                }
            }
            TextButton(
                onClick = {
                    val text = OnboardingFailureMessages.formatCopyableErrorDetails(context, code, detail)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("SpatialFin Error", text))
                    Toast.makeText(
                        context,
                        context.getString(SetupR.string.error_details_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Text(stringResource(SetupR.string.copy_error_details))
            }
        }
    }
}
