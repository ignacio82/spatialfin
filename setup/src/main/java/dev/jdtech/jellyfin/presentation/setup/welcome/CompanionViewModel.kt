package dev.jdtech.jellyfin.presentation.setup.welcome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LanSetupUiState
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.lan.PendingHandoffUserSelection
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface CompanionEvent {
    data class Activated(
        val serverUrl: String?,
        val standalone: Boolean = false,
    ) : CompanionEvent

    data class BrokerFallbackToLogin(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
        val prefilledUsername: String?,
        val failureCode: OnboardingFailureCode,
    ) : CompanionEvent
}

sealed class CompanionState {
    data object Idle : CompanionState()

    data object Scanning : CompanionState()

    data object Fetching : CompanionState()

    data object Success : CompanionState()

    data class Error(
        val message: String,
        val code: OnboardingFailureCode? = null,
    ) : CompanionState()

    data class NeedsUserSelection(
        val candidates: List<CompanionCandidate>,
        val isCompleting: Boolean = false,
        val errorCode: OnboardingFailureCode? = null,
    ) : CompanionState()
}

private data class PendingQrSelectionContext(
    val config: CompanionConfig,
    val endpoint: CompanionEndpoint,
    val policy: UrlTargetPolicy,
)

@HiltViewModel
class CompanionViewModel
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val importer: CompanionConfigImporter,
    private val lanSetupController: LanSetupController,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val workManager: dagger.Lazy<WorkManager>,
    private val companionLiveSyncClient: CompanionLiveSyncClient,
) : ViewModel() {

    private val _state = MutableStateFlow<CompanionState>(CompanionState.Idle)
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private val _events = Channel<CompanionEvent>(Channel.BUFFERED)
    val events: Flow<CompanionEvent> = _events.receiveAsFlow()

    val lanSetupUiState: StateFlow<LanSetupUiState> = lanSetupController.uiState
    val pendingHandoffSelection: StateFlow<PendingHandoffUserSelection?> =
        lanSetupController.pendingHandoffSelection

    private val _connectedCompanionUrl =
        MutableStateFlow(appPreferences.getValue(appPreferences.companionUrl).takeIf { it.isNotBlank() })
    val connectedCompanionUrl: StateFlow<String?> = _connectedCompanionUrl.asStateFlow()

    private val _lastSyncTimestampMs =
        MutableStateFlow(appPreferences.getValue(appPreferences.lastCompanionSyncTime))
    val lastSyncTimestampMs: StateFlow<Long> = _lastSyncTimestampMs.asStateFlow()

    private var pendingQrContext: PendingQrSelectionContext? = null
    private var lastClearedEntryId: String? = null

    init {
        viewModelScope.launch {
            companionLiveSyncClient.configChangedEvents.collect {
                val current = _state.value
                when {
                    current is CompanionState.Error && CompanionEndpoint.fromPreferences(appPreferences) != null ->
                        pairWithSavedCompanion()
                    current is CompanionState.NeedsUserSelection && !current.isCompleting -> {
                        val endpoint = pendingQrContext?.endpoint ?: CompanionEndpoint.fromPreferences(appPreferences)
                        if (endpoint != null) {
                            importWithMode(endpoint, ImportMode.PAIRING)
                        }
                    }
                }
            }
        }
    }

    fun refreshSavedConnectionMeta() {
        _connectedCompanionUrl.value =
            appPreferences.getValue(appPreferences.companionUrl).takeIf { it.isNotBlank() }
        _lastSyncTimestampMs.value = appPreferences.getValue(appPreferences.lastCompanionSyncTime)
    }

    fun onScreenEntered() {
        refreshSavedConnectionMeta()
        if (CompanionEndpoint.fromPreferences(appPreferences) != null) {
            companionLiveSyncClient.ensureConnected()
        }
        // Preserve active pendingHandoffSelection and QR NeedsUserSelection across re-entry
        if (_state.value is CompanionState.NeedsUserSelection) return
        if (pendingHandoffSelection.value != null) return
        if (_state.value is CompanionState.Error || _state.value is CompanionState.Success) {
            _state.value = CompanionState.Idle
        }
    }

    fun acquireReceiverLease(entryId: String, clearCompletedDrain: Boolean = false): LeaseHandle {
        val shouldClear = clearCompletedDrain && lastClearedEntryId != entryId
        if (shouldClear) {
            lastClearedEntryId = entryId
        }
        return lanSetupController.acquire(
            mode = LanSetupMode.COMPANION_RECEIVER,
            clearCompletedDrain = shouldClear,
        )
    }

    fun releaseReceiverLease(lease: LeaseHandle, graceMs: Long = LanSetupController.DEFAULT_RELEASE_GRACE_MS) {
        lanSetupController.release(lease, graceMs)
    }

    fun regenerateManualCode() {
        lanSetupController.regenerateManualCode()
    }

    fun completePendingUserSelection(
        serverId: String,
        userId: UUID?,
        username: String? = null,
    ) {
        lanSetupController.completePendingUserSelection(serverId, userId, username)
    }

    fun cancelPendingUserSelection() {
        lanSetupController.cancelPendingUserSelection()
    }

    fun startScanning() {
        _state.value = CompanionState.Scanning
    }

    fun reset() {
        pendingQrContext = null
        _state.value = CompanionState.Idle
    }

    fun syncNow() {
        val url = appPreferences.getValue(appPreferences.companionUrl)
        val token = appPreferences.getValue(appPreferences.companionToken)
        if (url.isBlank() || token.isBlank()) {
            _state.value = CompanionState.Error(
                message = "No saved companion connection found yet.",
                code = OnboardingFailureCode.AUTH_INVALID_CREDENTIALS,
            )
            return
        }
        val endpoint =
            runCatching { CompanionEndpoint.parse(url, token) }
                .getOrElse { error ->
                    _state.value = CompanionState.Error(
                        message = error.message ?: "Invalid saved companion connection",
                        code = error.toOnboardingFailureCode(),
                    )
                    return
                }
        importWithMode(endpoint, ImportMode.BACKGROUND_SYNC)
    }

    fun pairWithSavedCompanion() {
        val url = appPreferences.getValue(appPreferences.companionUrl)
        val token = appPreferences.getValue(appPreferences.companionToken)
        if (url.isBlank() || token.isBlank()) {
            _state.value = CompanionState.Error(
                message = "No saved companion connection found yet.",
                code = OnboardingFailureCode.AUTH_INVALID_CREDENTIALS,
            )
            return
        }
        val endpoint =
            runCatching { CompanionEndpoint.parse(url, token) }
                .getOrElse { error ->
                    _state.value = CompanionState.Error(
                        message = error.message ?: "Invalid saved companion connection",
                        code = error.toOnboardingFailureCode(),
                    )
                    return
                }
        importWithMode(endpoint, ImportMode.PAIRING)
    }

    fun fetchAndApplyConfig(payload: CompanionDiscoveryPayload) {
        if (_state.value is CompanionState.Fetching) return
        val endpoint =
            runCatching { CompanionEndpoint.from(payload) }
                .getOrElse { error ->
                    _state.value = CompanionState.Error(
                        message = error.message ?: "Invalid companion pairing code",
                        code = error.toOnboardingFailureCode(),
                    )
                    return
                }
        importWithMode(endpoint, ImportMode.PAIRING)
    }

    fun completeQrUserSelection(
        serverId: String,
        userId: UUID?,
        username: String? = null,
    ) {
        val ctx = pendingQrContext ?: return
        val current = _state.value as? CompanionState.NeedsUserSelection ?: return
        if (current.isCompleting) return

        _state.value = current.copy(isCompleting = true, errorCode = null)
        viewModelScope.launch {
            val outcome =
                importer.completeUserSelection(
                    config = ctx.config,
                    endpoint = ctx.endpoint,
                    policy = ctx.policy,
                    serverId = serverId,
                    userId = userId,
                    username = username,
                )
            handlePairingOutcome(outcome)
        }
    }

    fun cancelQrUserSelection() {
        pendingQrContext = null
        _state.value = CompanionState.Idle
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching {
                val wm = workManager.get()
                wm.cancelUniqueWork("CompanionSync")
                wm.cancelUniqueWork("companion_sync")
                wm.cancelUniqueWork("CompanionSyncPush")
            }.onFailure {
                Timber.w(it, "CompanionViewModel: failed cancelling companion WorkManager tasks")
            }
            appPreferences.setValue(appPreferences.companionUrl, "")
            appPreferences.setValue(appPreferences.companionToken, "")
            appPreferences.setValue(appPreferences.lastCompanionSyncTime, 0L)
            sessionActivationCoordinator.clearCompanionPreferencesOnDisconnect()
            pendingQrContext = null
            _state.value = CompanionState.Idle
            refreshSavedConnectionMeta()
        }
    }

    private fun importWithMode(
        endpoint: CompanionEndpoint,
        mode: ImportMode,
    ) {
        if (_state.value is CompanionState.Fetching) return
        viewModelScope.launch {
            _state.value = CompanionState.Fetching
            val outcome =
                importer.importFromEndpoint(
                    endpoint = endpoint,
                    mode = mode,
                )
            refreshSavedConnectionMeta()
            if (mode == ImportMode.BACKGROUND_SYNC) {
                when (outcome) {
                    is CompanionImportOutcome.Activated -> _state.value = CompanionState.Success
                    is CompanionImportOutcome.Failed -> {
                        Timber.w("COMPANION: background sync failed (%s): %s", outcome.code, outcome.message)
                        _state.value = CompanionState.Error(outcome.message, outcome.code)
                    }
                    is CompanionImportOutcome.NeedsUserSelection -> _state.value = CompanionState.Success
                    is CompanionImportOutcome.BrokerFallbackToLogin -> {
                        _state.value = CompanionState.Error(
                            message = outcome.failureCode.wireCode,
                            code = outcome.failureCode,
                        )
                    }
                }
            } else {
                handlePairingOutcome(outcome)
            }
        }
    }

    private suspend fun handlePairingOutcome(outcome: CompanionImportOutcome) {
        when (outcome) {
            is CompanionImportOutcome.Activated -> {
                pendingQrContext = null
                _state.value = CompanionState.Success
                refreshSavedConnectionMeta()
                _events.send(
                    CompanionEvent.Activated(
                        serverUrl = outcome.serverUrl,
                        standalone = outcome.standalone,
                    ),
                )
            }
            is CompanionImportOutcome.NeedsUserSelection -> {
                pendingQrContext =
                    PendingQrSelectionContext(
                        config = outcome.config,
                        endpoint = outcome.endpoint,
                        policy = outcome.policy,
                    )
                _state.value =
                    CompanionState.NeedsUserSelection(
                        candidates = outcome.candidates,
                        isCompleting = false,
                        errorCode = null,
                    )
            }
            is CompanionImportOutcome.BrokerFallbackToLogin -> {
                pendingQrContext = null
                _state.value = CompanionState.Idle
                refreshSavedConnectionMeta()
                _events.send(
                    CompanionEvent.BrokerFallbackToLogin(
                        serverId = outcome.serverId,
                        serverName = outcome.serverName,
                        serverAddress = outcome.serverAddress,
                        prefilledUsername = outcome.prefilledUsername,
                        failureCode = outcome.failureCode,
                    ),
                )
            }
            is CompanionImportOutcome.Failed -> {
                Timber.w("COMPANION: pairing failed (%s): %s", outcome.code, outcome.message)
                val existingSelection = _state.value as? CompanionState.NeedsUserSelection
                if (existingSelection != null && pendingQrContext != null) {
                    _state.value = existingSelection.copy(isCompleting = false, errorCode = outcome.code)
                } else {
                    _state.value = CompanionState.Error(outcome.message, outcome.code)
                }
            }
        }
    }
}
