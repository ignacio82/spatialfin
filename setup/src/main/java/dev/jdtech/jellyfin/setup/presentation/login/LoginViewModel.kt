package dev.jdtech.jellyfin.setup.presentation.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.companion.CompanionAdminRelinkClient
import dev.jdtech.jellyfin.companion.CompanionCandidate
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.CompanionRelinkResult
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.setup.domain.AuthenticateUserResult
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.AuthenticationResult

sealed interface LoginEvent {
    data class CompanionRelinked(val username: String?) : LoginEvent

    data object CompanionAutoRecovered : LoginEvent
}

@HiltViewModel
class LoginViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: SetupRepository,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val quickConnectControllerFactory: QuickConnectController.Factory,
    private val lanSetupController: LanSetupController,
    private val appPreferences: AppPreferences,
    private val companionAdminRelinkClient: CompanionAdminRelinkClient,
    private val companionLiveSyncClient: CompanionLiveSyncClient,
    private val companionConfigImporter: CompanionConfigImporter,
) : ViewModel() {

    private data class BoundTarget(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
        val prefilledUsername: String?,
        val lanSetupSessionId: String?,
    )

    private data class PendingActivation(
        val canonicalServerId: String,
        val serverName: String,
        val serverAddress: String,
        val user: User,
        val legacyServerIds: List<String>,
        val reason: SessionChangeReason,
        val rawUsername: String?,
        val rawPassword: String?,
        val enableQuickConnectBroker: Boolean,
    )

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events: Flow<LoginEvent> = _events.receiveAsFlow()

    private var boundTarget: BoundTarget? = null
    private var entryFailureCode: OnboardingFailureCode? = null
    private var activeLanSessionId: String? = null
    private var localQuickConnectController: QuickConnectController? = null
    private var quickConnectObserverJob: Job? = null
    private var lanSessionMonitorJob: Job? = null
    private var disclaimerJob: Job? = null
    private var companionPushObserverJob: Job? = null
    private var companionRecheckJob: Job? = null
    private var pausedWhileAwaitingCompanionRecovery: Boolean = false
    private var pendingSelectionOutcome: CompanionImportOutcome.NeedsUserSelection? = null

    @Volatile
    private var transientCompanionAdminPassword: String = ""

    @Volatile
    private var pendingActivation: PendingActivation? = null

    fun bindTarget(
        serverId: String,
        serverName: String,
        serverAddress: String,
        prefilledUsername: String? = null,
        lanSetupSessionId: String? = null,
        initialFailureCode: OnboardingFailureCode? = null,
    ) {
        val normalizedAddress = serverAddress.trim().trimEnd('/')
        val requested =
            BoundTarget(
                serverId = serverId,
                serverName = serverName,
                serverAddress = normalizedAddress,
                prefilledUsername = prefilledUsername,
                lanSetupSessionId = lanSetupSessionId,
            )
        val savedCompanionUrl = appPreferences.getValue(appPreferences.companionUrl).takeIf { it.isNotBlank() }

        // Same-target configuration-change idempotency: preserve active QC code and in-flight exchangeJob.
        if (boundTarget == requested) {
            val effectiveFailureCode = entryFailureCode ?: initialFailureCode
            if (initialFailureCode != null && entryFailureCode == null) {
                entryFailureCode = initialFailureCode
            }
            val showRelink = shouldShowCompanionRelink(savedCompanionUrl, lanSetupSessionId, effectiveFailureCode)
            _state.update { current ->
                val canPopulateInitialError =
                    current.pendingSignedInUsername == null && !current.isLoading && current.failureCode == null
                current.copy(
                    companionUrl = savedCompanionUrl ?: current.companionUrl,
                    showCompanionRelinkOption = showRelink || current.showCompanionRelinkOption,
                    failureCode = if (canPopulateInitialError) initialFailureCode else current.failureCode,
                    error =
                        if (canPopulateInitialError && current.error == null) {
                            initialFailureCode?.let {
                                UiText.StringResource(OnboardingFailureMessages.messageRes(it))
                            }
                        } else {
                            current.error
                        },
                )
            }
            ensureCompanionPushObserver(lanSetupSessionId)
            return
        }

        teardownBinding()
        boundTarget = requested
        entryFailureCode = initialFailureCode
        val showRelink = shouldShowCompanionRelink(savedCompanionUrl, lanSetupSessionId, initialFailureCode)
        _state.value =
            LoginState(
                serverName = serverName,
                failureCode = initialFailureCode,
                error =
                    initialFailureCode?.let {
                        UiText.StringResource(OnboardingFailureMessages.messageRes(it))
                    },
                companionUrl = savedCompanionUrl,
                showCompanionRelinkOption = showRelink,
            )

        loadDisclaimerFor(normalizedAddress)

        if (lanSetupSessionId != null && lanSetupController.isActiveSession(lanSetupSessionId)) {
            bindLanSession(lanSetupSessionId, requested)
        } else {
            bindLocalQuickConnect(requested, autoStart = lanSetupSessionId != null)
        }
        ensureCompanionPushObserver(lanSetupSessionId)
    }

    private fun shouldMonitorCompanionState(state: LoginState): Boolean =
        isCompanionRecoverableFailure(state.failureCode) || state.companionSelectionCandidates.isNotEmpty()

    private fun ensureCompanionPushObserver(lanSetupSessionId: String?) {
        if (lanSetupSessionId != null) return
        if (CompanionEndpoint.fromPreferences(appPreferences) == null) return
        companionLiveSyncClient.ensureConnected()
        if (companionPushObserverJob?.isActive == true) return
        companionPushObserverJob =
            viewModelScope.launch {
                companionLiveSyncClient.configChangedEvents.collect {
                    if (activeLanSessionId == null &&
                        shouldMonitorCompanionState(_state.value) &&
                        !_state.value.isLoading &&
                        _state.value.pendingSignedInUsername == null
                    ) {
                        retryCompanionPairing(manual = false)
                    }
                }
            }
    }

    private fun isCompanionRecoverableFailure(code: OnboardingFailureCode?): Boolean =
        code == OnboardingFailureCode.PAIRING_NO_VERIFIED_USER ||
            code == OnboardingFailureCode.QC_BROKER_REJECTED ||
            code == OnboardingFailureCode.QC_BROKER_UNREACHABLE ||
            code == OnboardingFailureCode.QC_DISABLED_ON_SERVER ||
            code == OnboardingFailureCode.SERVER_IDENTITY_MISMATCH

    private fun shouldShowCompanionRelink(
        companionUrl: String?,
        lanSetupSessionId: String?,
        failureCode: OnboardingFailureCode?,
    ): Boolean =
        companionUrl != null &&
            lanSetupSessionId == null &&
            (failureCode == OnboardingFailureCode.PAIRING_NO_VERIFIED_USER ||
                failureCode == OnboardingFailureCode.QC_BROKER_REJECTED)

    fun onScreenEntered() {
        // Preserve active binding & Quick Connect state; resume local controller if paused
        localQuickConnectController?.resumeOnStart()
    }

    fun onPauseOrStop() {
        // LAN-session Quick Connect is owned by LanSetupController and must not be paused on ON_STOP.
        if (activeLanSessionId == null) {
            localQuickConnectController?.pauseOnStop()
            if (shouldMonitorCompanionState(_state.value) &&
                CompanionEndpoint.fromPreferences(appPreferences) != null
            ) {
                pausedWhileAwaitingCompanionRecovery = true
            }
        }
    }

    fun onResumeOrStart() {
        if (activeLanSessionId == null) {
            localQuickConnectController?.resumeOnStart()
            if (CompanionEndpoint.fromPreferences(appPreferences) != null) {
                companionLiveSyncClient.ensureConnected()
                if (pausedWhileAwaitingCompanionRecovery) {
                    pausedWhileAwaitingCompanionRecovery = false
                    if (shouldMonitorCompanionState(_state.value) &&
                        !_state.value.isLoading &&
                        _state.value.pendingSignedInUsername == null
                    ) {
                        retryCompanionPairing(manual = false)
                    }
                }
            }
        }
    }

    private fun retryCompanionPairing(manual: Boolean) {
        if (companionRecheckJob?.isActive == true) return
        val endpoint = CompanionEndpoint.fromPreferences(appPreferences) ?: return
        companionRecheckJob =
            viewModelScope.launch {
                _state.update { it.copy(isCheckingCompanion = true) }
                val outcome =
                    try {
                        companionConfigImporter.importFromEndpoint(
                            endpoint = endpoint,
                            mode = ImportMode.PAIRING,
                        )
                    } catch (e: CancellationException) {
                        _state.update { it.copy(isCheckingCompanion = false) }
                        throw e
                    } catch (t: Throwable) {
                        CompanionImportOutcome.Failed(
                            t.toOnboardingFailureCode(),
                            t.message ?: "Companion sync failed",
                        )
                    }
                handleCompanionRecoveryOutcome(outcome, manual = manual, allowAutoSelect = true)
            }
    }

    private suspend fun handleCompanionRecoveryOutcome(
        outcome: CompanionImportOutcome,
        manual: Boolean,
        allowAutoSelect: Boolean,
    ) {
        when (outcome) {
            is CompanionImportOutcome.Activated -> {
                pendingSelectionOutcome = null
                localQuickConnectController?.cancel()
                _state.update {
                    it.copy(
                        isCheckingCompanion = false,
                        isLoading = false,
                        failureCode = null,
                        error = null,
                        companionSelectionCandidates = emptyList(),
                    )
                }
                _events.trySend(LoginEvent.CompanionAutoRecovered)
            }
            is CompanionImportOutcome.NeedsUserSelection -> {
                val matched =
                    if (allowAutoSelect) {
                        matchSinglePreferredCandidate(outcome.candidates)
                    } else {
                        null
                    }
                if (matched != null) {
                    val completed =
                        try {
                            companionConfigImporter.completeUserSelection(
                                config = outcome.config,
                                endpoint = outcome.endpoint,
                                policy = outcome.policy,
                                serverId = matched.serverId,
                                userId = matched.userId,
                                username = matched.username,
                            )
                        } catch (e: CancellationException) {
                            _state.update { it.copy(isCheckingCompanion = false) }
                            throw e
                        } catch (t: Throwable) {
                            CompanionImportOutcome.Failed(
                                t.toOnboardingFailureCode(),
                                t.message ?: "Companion user selection failed",
                            )
                        }
                    handleCompanionRecoveryOutcome(completed, manual = manual, allowAutoSelect = false)
                } else {
                    pendingSelectionOutcome = outcome
                    val targetName = boundTarget?.prefilledUsername?.trim()?.takeIf { it.isNotEmpty() }
                    val targetCandidate =
                        targetName?.let { name ->
                            outcome.candidates.firstOrNull { it.username.equals(name, ignoreCase = true) }
                        }
                    val targetStillNeedsUpdate =
                        outcome.candidates.none { it.isVerified } ||
                            (targetCandidate != null && targetCandidate.requiresCredentialUpdate)
                    val showCandidates =
                        outcome.candidates.any { it.isVerified } ||
                            manual ||
                            targetName == null ||
                            outcome.candidates.size >= 2
                    _state.update { current ->
                        val keptFailureCode =
                            if (targetStillNeedsUpdate) {
                                current.failureCode ?: entryFailureCode ?: OnboardingFailureCode.PAIRING_NO_VERIFIED_USER
                            } else {
                                null
                            }
                        val keptError =
                            if (keptFailureCode != null) {
                                current.error ?: UiText.StringResource(OnboardingFailureMessages.messageRes(keptFailureCode))
                            } else {
                                null
                            }
                        current.copy(
                            isCheckingCompanion = false,
                            failureCode = keptFailureCode,
                            error = keptError,
                            showCompanionRelinkOption =
                                current.showCompanionRelinkOption ||
                                    shouldShowCompanionRelink(current.companionUrl, activeLanSessionId, keptFailureCode),
                            companionSelectionCandidates = if (showCandidates) outcome.candidates else emptyList(),
                        )
                    }
                }
            }
            is CompanionImportOutcome.BrokerFallbackToLogin -> {
                pendingSelectionOutcome = null
                entryFailureCode = outcome.failureCode
                val fallbackUser = outcome.prefilledUsername?.trim()?.takeIf { it.isNotEmpty() }
                if (fallbackUser != null) {
                    boundTarget = boundTarget?.copy(prefilledUsername = fallbackUser)
                }
                _state.update {
                    it.copy(
                        isCheckingCompanion = false,
                        suggestedUsername = fallbackUser ?: it.suggestedUsername,
                        failureCode = outcome.failureCode,
                        error = UiText.StringResource(OnboardingFailureMessages.messageRes(outcome.failureCode)),
                        showCompanionRelinkOption =
                            shouldShowCompanionRelink(it.companionUrl, activeLanSessionId, outcome.failureCode),
                        companionSelectionCandidates = emptyList(),
                    )
                }
            }
            is CompanionImportOutcome.Failed -> {
                _state.update { current ->
                    val shouldReplaceError =
                        manual || outcome.code == OnboardingFailureCode.AUTH_INVALID_CREDENTIALS
                    current.copy(
                        isCheckingCompanion = false,
                        failureCode = if (shouldReplaceError) outcome.code else current.failureCode,
                        error =
                            if (shouldReplaceError) {
                                UiText.StringResource(OnboardingFailureMessages.messageRes(outcome.code))
                            } else {
                                current.error
                            },
                    )
                }
            }
        }
    }

    private fun matchSinglePreferredCandidate(candidates: List<CompanionCandidate>): CompanionCandidate? {
        val preferredUser = boundTarget?.prefilledUsername?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val targetServerId = boundTarget?.serverId?.trim().orEmpty()
        val eligible = candidates.filter { it.isVerified && it.quickConnectBrokerEnabled }
        return eligible.singleOrNull { cand ->
            cand.username.equals(preferredUser, ignoreCase = true) &&
                (targetServerId.isEmpty() ||
                    targetServerId.startsWith("srv-") ||
                    normalizeJellyfinId(cand.serverId) == normalizeJellyfinId(targetServerId))
        } ?: eligible.singleOrNull { cand ->
            cand.username.equals(preferredUser, ignoreCase = true)
        }
    }

    fun acquireBrowserKeepAliveLease(): LeaseHandle =
        lanSetupController.acquire(
            mode = LanSetupMode.BROWSER_URL_ENTRY,
            clearCompletedDrain = false,
        )

    fun releaseBrowserKeepAliveLease(
        lease: LeaseHandle,
        graceMs: Long = LanSetupController.DEFAULT_RELEASE_GRACE_MS,
    ) {
        lanSetupController.release(lease, graceMs)
    }

    fun cancelActiveLanSessionOnBack() {
        val sid = activeLanSessionId ?: return
        lanSetupController.cancelSessionFromDevice(sid)
    }

    private fun bindLanSession(sessionId: String, target: BoundTarget) {
        activeLanSessionId = sessionId
        lanSessionMonitorJob?.cancel()
        lanSessionMonitorJob =
            viewModelScope.launch {
                lanSetupController.uiState.collect { lanState ->
                    if (lanState.activeSessionId == sessionId) {
                        _state.update { it.copy(quickConnectState = lanState.quickConnectState) }
                    } else if (activeLanSessionId == sessionId) {
                        // Live LAN session ended or was superseded: degrade cleanly to local Quick Connect
                        activeLanSessionId = null
                        lanSessionMonitorJob?.cancel()
                        lanSessionMonitorJob = null
                        bindLocalQuickConnect(target, autoStart = false)
                    }
                }
            }
    }

    private fun bindLocalQuickConnect(target: BoundTarget, autoStart: Boolean) {
        activeLanSessionId = null
        val controller =
            quickConnectControllerFactory.create(
                savedStateHandle = savedStateHandle,
                scope = viewModelScope,
                keyPrefix = "${target.serverId}|${target.serverAddress}",
            )
        localQuickConnectController = controller
        quickConnectObserverJob?.cancel()
        quickConnectObserverJob =
            viewModelScope.launch {
                controller.state.collect { qcState ->
                    _state.update { it.copy(quickConnectState = qcState) }
                    if (qcState is QuickConnectState.Authenticated) {
                        completeLocalQuickConnectAuth(target, qcState.result)
                    }
                }
            }
        if (autoStart) {
            controller.start(target.serverAddress)
        }
    }

    private suspend fun completeLocalQuickConnectAuth(
        target: BoundTarget,
        authResult: AuthenticationResult,
    ) {
        companionRecheckJob?.cancel()
        companionRecheckJob = null
        pendingSelectionOutcome = null
        _state.update {
            it.copy(
                isLoading = true,
                isCheckingCompanion = false,
                error = null,
                failureCode = null,
                companionSelectionCandidates = emptyList(),
            )
        }
        val returnedServerId = authResult.serverId?.trim()?.takeIf { it.isNotEmpty() }
        val isLegacyPlaceholder = target.serverId.startsWith("srv-")
        if (returnedServerId == null ||
            (!isLegacyPlaceholder && normalizeJellyfinId(returnedServerId) != normalizeJellyfinId(target.serverId))
        ) {
            applyFailure(OnboardingFailureCode.SERVER_IDENTITY_MISMATCH)
            return
        }
        val userDto = authResult.user
        val token = authResult.accessToken?.takeIf { it.isNotBlank() }
        if (userDto == null || token == null) {
            applyFailure(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
            return
        }

        val canonicalServerId = if (isLegacyPlaceholder) returnedServerId else target.serverId
        val user =
            User(
                id = userDto.id,
                name = userDto.name?.takeIf { it.isNotBlank() } ?: userDto.id.toString(),
                serverId = canonicalServerId,
                accessToken = token,
            )
        val legacyIds = if (isLegacyPlaceholder) listOf(target.serverId) else emptyList()

        if (_state.value.companionRelinkEnabled && !_state.value.companionUrl.isNullOrBlank()) {
            val pending =
                PendingActivation(
                    canonicalServerId = canonicalServerId,
                    serverName = target.serverName,
                    serverAddress = target.serverAddress,
                    user = user,
                    legacyServerIds = legacyIds,
                    reason = SessionChangeReason.QUICK_CONNECT,
                    rawUsername = user.name,
                    rawPassword = null,
                    enableQuickConnectBroker = entryFailureCode == OnboardingFailureCode.QC_BROKER_REJECTED,
                )
            relinkCompanionThenActivate(pending, adminPasswordOverride = null)
            return
        }

        val activation =
            sessionActivationCoordinator.activateServer(
                serverId = canonicalServerId,
                addressUrl = target.serverAddress,
                reason = SessionChangeReason.QUICK_CONNECT,
                user = user,
                serverName = target.serverName,
                legacyServerIds = legacyIds,
                isDedicatedToken = true,
                overwriteUserPreferences = false,
            )
        when (activation) {
            is ActivationResult.Activated -> {
                _state.update { it.copy(isLoading = false, error = null, failureCode = null) }
            }
            is ActivationResult.Failed -> applyFailure(activation.code)
            is ActivationResult.NeedsAuth -> applyFailure(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
        }
    }

    private fun loadDisclaimerFor(serverAddress: String) {
        disclaimerJob?.cancel()
        disclaimerJob =
            viewModelScope.launch {
                try {
                    val disclaimer = repository.loadDisclaimer(serverAddress)
                    _state.update { it.copy(disclaimer = disclaimer) }
                } catch (_: Exception) {
                    // Optional branding disclaimer
                }
            }
    }

    private fun login(username: String, password: String) {
        val target = boundTarget ?: return
        companionRecheckJob?.cancel()
        companionRecheckJob = null
        pendingSelectionOutcome = null
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    isCheckingCompanion = false,
                    error = null,
                    failureCode = null,
                    companionSelectionCandidates = emptyList(),
                )
            }
            try {
                val lanSid = activeLanSessionId
                if (lanSid != null && lanSetupController.isActiveSession(lanSid)) {
                    val result =
                        lanSetupController.performManualLogin(
                            lanSetupSessionId = lanSid,
                            serverId = target.serverId,
                            serverName = target.serverName,
                            serverAddress = target.serverAddress,
                            username = username,
                            password = password,
                        )
                    handleActivationResult(result)
                } else if (_state.value.companionRelinkEnabled && !_state.value.companionUrl.isNullOrBlank()) {
                    when (
                        val auth =
                            repository.authenticate(
                                serverId = target.serverId,
                                serverName = target.serverName,
                                serverAddress = target.serverAddress,
                                username = username,
                                password = password,
                            )
                    ) {
                        is AuthenticateUserResult.Failed -> applyFailure(auth.code)
                        is AuthenticateUserResult.Authenticated -> {
                            val pending =
                                PendingActivation(
                                    canonicalServerId = auth.canonicalServerId,
                                    serverName = auth.serverName,
                                    serverAddress = auth.serverAddress,
                                    user = auth.user,
                                    legacyServerIds = auth.legacyServerIds,
                                    reason = SessionChangeReason.MANUAL_LOGIN,
                                    rawUsername = username.ifBlank { auth.user.name },
                                    rawPassword = password,
                                    enableQuickConnectBroker =
                                        entryFailureCode == OnboardingFailureCode.QC_BROKER_REJECTED,
                                )
                            relinkCompanionThenActivate(pending, adminPasswordOverride = null)
                        }
                    }
                } else {
                    val result =
                        repository.login(
                            serverId = target.serverId,
                            serverName = target.serverName,
                            serverAddress = target.serverAddress,
                            username = username,
                            password = password,
                        )
                    handleActivationResult(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                applyFailure(e.toOnboardingFailureCode())
            }
        }
    }

    private suspend fun relinkCompanionThenActivate(
        pending: PendingActivation,
        adminPasswordOverride: String?,
    ) {
        val companionBaseUrl = _state.value.companionUrl
        if (companionBaseUrl.isNullOrBlank()) {
            pendingActivation = null
            activatePendingSession(pending)
            return
        }

        val adminPassword = adminPasswordOverride ?: transientCompanionAdminPassword
        transientCompanionAdminPassword = ""
        pendingActivation = pending

        _state.update {
            it.copy(
                isLoading = true,
                companionRelinkState = CompanionRelinkState.Linking,
            )
        }

        val relinkResult =
            companionAdminRelinkClient.relinkUser(
                companionBaseUrl = companionBaseUrl,
                adminPassword = adminPassword,
                serverId = pending.canonicalServerId,
                jellyfinBaseUrl = pending.serverAddress,
                jellyfinAccessToken = pending.user.accessToken.orEmpty(),
                jellyfinUsername = pending.rawUsername ?: pending.user.name,
                jellyfinPassword = pending.rawPassword,
                enableQuickConnectBroker = pending.enableQuickConnectBroker,
            )

        when (relinkResult) {
            is CompanionRelinkResult.Success -> {
                val linkedName = relinkResult.username ?: pending.user.name
                pendingActivation = null
                _state.update {
                    it.copy(
                        pendingSignedInUsername = null,
                        companionRelinkState = CompanionRelinkState.Succeeded(linkedName),
                    )
                }
                _events.trySend(LoginEvent.CompanionRelinked(linkedName))
                activatePendingSession(pending)
            }
            is CompanionRelinkResult.InvalidAdminPassword -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        pendingSignedInUsername = pending.user.name,
                        companionRelinkState =
                            CompanionRelinkState.Failed(
                                message = relinkResult.message,
                                isAdminPasswordError = true,
                            ),
                    )
                }
            }
            is CompanionRelinkResult.Failed -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        pendingSignedInUsername = pending.user.name,
                        companionRelinkState =
                            CompanionRelinkState.Failed(
                                message = relinkResult.message,
                                isAdminPasswordError = false,
                            ),
                    )
                }
            }
        }
    }

    private suspend fun activatePendingSession(pending: PendingActivation) {
        val activation =
            sessionActivationCoordinator.activateServer(
                serverId = pending.canonicalServerId,
                addressUrl = pending.serverAddress,
                reason = pending.reason,
                user = pending.user,
                serverName = pending.serverName,
                legacyServerIds = pending.legacyServerIds,
                isDedicatedToken = true,
                overwriteUserPreferences = false,
            )
        handleActivationResult(activation)
    }

    private fun handleActivationResult(result: ActivationResult) {
        when (result) {
            is ActivationResult.Activated -> {
                _state.update { it.copy(isLoading = false, error = null, failureCode = null) }
            }
            is ActivationResult.Failed -> applyFailure(result.code)
            is ActivationResult.NeedsAuth -> applyFailure(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
        }
    }

    private fun applyFailure(code: OnboardingFailureCode) {
        _state.update {
            it.copy(
                isLoading = false,
                failureCode = code,
                error = UiText.StringResource(OnboardingFailureMessages.messageRes(code)),
            )
        }
    }

    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.OnLoginClick -> login(action.username, action.password)
            LoginAction.OnStartQuickConnect -> {
                val target = boundTarget ?: return
                val lanSid = activeLanSessionId
                if (lanSid != null && lanSetupController.isActiveSession(lanSid)) {
                    lanSetupController.retryQuickConnect(lanSid)
                } else {
                    localQuickConnectController?.start(target.serverAddress)
                }
            }
            LoginAction.OnCancelQuickConnect -> {
                val lanSid = activeLanSessionId
                if (lanSid != null && lanSetupController.isActiveSession(lanSid)) {
                    lanSetupController.cancelQuickConnect(lanSid)
                } else {
                    localQuickConnectController?.cancel()
                    boundTarget?.let { bindLocalQuickConnect(it, autoStart = false) }
                }
            }
            LoginAction.OnRetryQuickConnect -> {
                val target = boundTarget ?: return
                val lanSid = activeLanSessionId
                if (lanSid != null && lanSetupController.isActiveSession(lanSid)) {
                    lanSetupController.retryQuickConnect(lanSid)
                } else {
                    localQuickConnectController?.retry(target.serverAddress)
                }
            }
            LoginAction.OnBackClick -> cancelActiveLanSessionOnBack()
            LoginAction.OnChangeServerClick -> cancelActiveLanSessionOnBack()
            is LoginAction.OnCompanionRelinkToggled -> {
                if (!action.enabled) {
                    transientCompanionAdminPassword = ""
                }
                _state.update {
                    it.copy(
                        companionRelinkEnabled = action.enabled,
                        companionRelinkState =
                            if (!action.enabled) CompanionRelinkState.Idle else it.companionRelinkState,
                    )
                }
            }
            is LoginAction.OnCompanionAdminPasswordChanged -> {
                transientCompanionAdminPassword = action.password
                if (_state.value.companionRelinkState is CompanionRelinkState.Failed) {
                    _state.update { it.copy(companionRelinkState = CompanionRelinkState.Idle) }
                }
            }
            is LoginAction.OnRetryCompanionRelink -> {
                val pending = pendingActivation ?: return
                viewModelScope.launch {
                    relinkCompanionThenActivate(pending, adminPasswordOverride = action.adminPassword)
                }
            }
            LoginAction.OnSkipCompanionRelinkAndContinue -> {
                val pending = pendingActivation ?: return
                pendingActivation = null
                transientCompanionAdminPassword = ""
                viewModelScope.launch {
                    _state.update {
                        it.copy(
                            isLoading = true,
                            pendingSignedInUsername = null,
                            companionRelinkState = CompanionRelinkState.Idle,
                        )
                    }
                    activatePendingSession(pending)
                }
            }
            LoginAction.OnRetryCompanionPairing -> retryCompanionPairing(manual = true)
            is LoginAction.OnSelectCompanionCandidate -> {
                val selectionOutcome = pendingSelectionOutcome ?: return
                if (companionRecheckJob?.isActive == true) return
                companionRecheckJob =
                    viewModelScope.launch {
                        _state.update { it.copy(isCheckingCompanion = true) }
                        val completed =
                            try {
                                companionConfigImporter.completeUserSelection(
                                    config = selectionOutcome.config,
                                    endpoint = selectionOutcome.endpoint,
                                    policy = selectionOutcome.policy,
                                    serverId = action.serverId,
                                    userId = action.userId,
                                    username = action.username,
                                )
                            } catch (e: CancellationException) {
                                _state.update { it.copy(isCheckingCompanion = false) }
                                throw e
                            } catch (t: Throwable) {
                                CompanionImportOutcome.Failed(
                                    t.toOnboardingFailureCode(),
                                    t.message ?: "Companion user selection failed",
                                )
                            }
                        handleCompanionRecoveryOutcome(completed, manual = true, allowAutoSelect = false)
                    }
            }
        }
    }

    private fun teardownBinding() {
        lanSessionMonitorJob?.cancel()
        lanSessionMonitorJob = null
        quickConnectObserverJob?.cancel()
        quickConnectObserverJob = null
        companionPushObserverJob?.cancel()
        companionPushObserverJob = null
        companionRecheckJob?.cancel()
        companionRecheckJob = null
        localQuickConnectController?.cancel()
        localQuickConnectController = null
        disclaimerJob?.cancel()
        disclaimerJob = null
        activeLanSessionId = null
        entryFailureCode = null
        pausedWhileAwaitingCompanionRecovery = false
        pendingSelectionOutcome = null
        transientCompanionAdminPassword = ""
        pendingActivation = null
    }

    override fun onCleared() {
        teardownBinding()
        super.onCleared()
    }
}
