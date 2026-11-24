package dev.jdtech.jellyfin.setup.presentation.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.onboarding.toOnboardingFailureCode
import dev.jdtech.jellyfin.session.ActivationResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionChangeReason
import dev.jdtech.jellyfin.setup.domain.SetupRepository
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.lan.LanSetupMode
import dev.jdtech.jellyfin.setup.lan.LeaseHandle
import dev.jdtech.jellyfin.setup.onboarding.OnboardingFailureMessages
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.AuthenticationResult

@HiltViewModel
class LoginViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: SetupRepository,
    private val sessionActivationCoordinator: SessionActivationCoordinator,
    private val quickConnectControllerFactory: QuickConnectController.Factory,
    private val lanSetupController: LanSetupController,
) : ViewModel() {

    private data class BoundTarget(
        val serverId: String,
        val serverName: String,
        val serverAddress: String,
        val prefilledUsername: String?,
        val lanSetupSessionId: String?,
    )

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    private var boundTarget: BoundTarget? = null
    private var activeLanSessionId: String? = null
    private var localQuickConnectController: QuickConnectController? = null
    private var quickConnectObserverJob: Job? = null
    private var lanSessionMonitorJob: Job? = null
    private var disclaimerJob: Job? = null

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

        // Same-target configuration-change idempotency: preserve active QC code and in-flight exchangeJob.
        if (boundTarget == requested) {
            if (initialFailureCode != null && _state.value.failureCode == null) {
                _state.update {
                    it.copy(
                        failureCode = initialFailureCode,
                        error = UiText.StringResource(OnboardingFailureMessages.messageRes(initialFailureCode)),
                    )
                }
            }
            return
        }

        teardownBinding()
        boundTarget = requested
        _state.value =
            LoginState(
                serverName = serverName,
                failureCode = initialFailureCode,
                error = initialFailureCode?.let {
                    UiText.StringResource(OnboardingFailureMessages.messageRes(it))
                },
            )

        loadDisclaimerFor(normalizedAddress)

        if (lanSetupSessionId != null && lanSetupController.isActiveSession(lanSetupSessionId)) {
            bindLanSession(lanSetupSessionId, requested)
        } else {
            bindLocalQuickConnect(requested, autoStart = lanSetupSessionId != null)
        }
    }

    fun onScreenEntered() {
        // Preserve active binding & Quick Connect state; resume local controller if paused
        localQuickConnectController?.resumeOnStart()
    }

    fun onPauseOrStop() {
        // LAN-session Quick Connect is owned by LanSetupController and must not be paused on ON_STOP.
        if (activeLanSessionId == null) {
            localQuickConnectController?.pauseOnStop()
        }
    }

    fun onResumeOrStart() {
        if (activeLanSessionId == null) {
            localQuickConnectController?.resumeOnStart()
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
        _state.update { it.copy(isLoading = true, error = null, failureCode = null) }
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
        val activation =
            sessionActivationCoordinator.activateServer(
                serverId = canonicalServerId,
                addressUrl = target.serverAddress,
                reason = SessionChangeReason.QUICK_CONNECT,
                user = user,
                serverName = target.serverName,
                legacyServerIds = if (isLegacyPlaceholder) listOf(target.serverId) else emptyList(),
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
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, failureCode = null) }
            try {
                val lanSid = activeLanSessionId
                val result =
                    if (lanSid != null && lanSetupController.isActiveSession(lanSid)) {
                        lanSetupController.performManualLogin(
                            lanSetupSessionId = lanSid,
                            serverId = target.serverId,
                            serverName = target.serverName,
                            serverAddress = target.serverAddress,
                            username = username,
                            password = password,
                        )
                    } else {
                        repository.login(
                            serverId = target.serverId,
                            serverName = target.serverName,
                            serverAddress = target.serverAddress,
                            username = username,
                            password = password,
                        )
                    }
                when (result) {
                    is ActivationResult.Activated -> {
                        _state.update { it.copy(isLoading = false, error = null, failureCode = null) }
                    }
                    is ActivationResult.Failed -> applyFailure(result.code)
                    is ActivationResult.NeedsAuth -> applyFailure(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                applyFailure(e.toOnboardingFailureCode())
            }
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
        }
    }

    private fun teardownBinding() {
        lanSessionMonitorJob?.cancel()
        lanSessionMonitorJob = null
        quickConnectObserverJob?.cancel()
        quickConnectObserverJob = null
        localQuickConnectController?.cancel()
        localQuickConnectController = null
        disclaimerJob?.cancel()
        disclaimerJob = null
        activeLanSessionId = null
    }

    override fun onCleared() {
        teardownBinding()
        super.onCleared()
    }
}
