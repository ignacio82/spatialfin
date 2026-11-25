package dev.jdtech.jellyfin.presentation.setup.login

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.setup.components.LoadingButton
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.setup.onboarding.companionCandidateStatusRes
import dev.jdtech.jellyfin.setup.onboarding.resolveCompanionRecoveryAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginEvent
import dev.jdtech.jellyfin.setup.presentation.login.LoginState
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectController
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectState
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.spatialfin.presentation.theme.SpatialFinTheme

@Composable
fun LoginScreen(
    serverId: String,
    serverName: String,
    serverAddress: String,
    onChangeServerClick: () -> Unit,
    onBackClick: () -> Unit,
    prefilledUsername: String? = null,
    lanSetupSessionId: String? = null,
    initialFailureCode: OnboardingFailureCode? = null,
    onBrowseOfflineMedia: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is LoginEvent.CompanionRelinked -> {
                val label = event.username?.takeIf { it.isNotBlank() } ?: prefilledUsername.orEmpty()
                Toast.makeText(
                    context.applicationContext,
                    context.getString(SetupR.string.companion_relink_success_toast, label),
                    Toast.LENGTH_LONG,
                ).show()
            }
            LoginEvent.CompanionAutoRecovered -> {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(SetupR.string.companion_auto_recovered_toast),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

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
    }

    DisposableEffect(lanSetupSessionId) {
        val lease =
            if (lanSetupSessionId != null) {
                viewModel.acquireBrowserKeepAliveLease()
            } else {
                null
            }
        onDispose {
            if (lease != null) {
                viewModel.releaseBrowserKeepAliveLease(lease, LanSetupController.DEFAULT_RELEASE_GRACE_MS)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onPauseOrStop()
                Lifecycle.Event.ON_START -> viewModel.onResumeOrStart()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val handleBack = {
        viewModel.onAction(LoginAction.OnBackClick)
        onBackClick()
    }

    BackHandler(onBack = handleBack)

    LoginScreenLayout(
        serverId = serverId,
        serverAddress = serverAddress,
        state = state,
        prefilledUsername = prefilledUsername,
        onBrowseOfflineMedia = onBrowseOfflineMedia,
        onAction = { action ->
            when (action) {
                LoginAction.OnChangeServerClick -> {
                    viewModel.onAction(action)
                    onChangeServerClick()
                }
                LoginAction.OnBackClick -> handleBack()
                else -> viewModel.onAction(action)
            }
        },
    )
}

@Composable
private fun LoginScreenLayout(
    serverId: String,
    serverAddress: String,
    state: LoginState,
    onAction: (LoginAction) -> Unit,
    prefilledUsername: String? = null,
    onBrowseOfflineMedia: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var username by rememberSaveable(serverId, serverAddress, prefilledUsername) {
        mutableStateOf(prefilledUsername ?: "")
    }
    var password by rememberSaveable(serverId, serverAddress, prefilledUsername) {
        mutableStateOf("")
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.suggestedUsername) {
        val suggested = state.suggestedUsername
        if (!suggested.isNullOrBlank()) {
            username = suggested
        }
    }

    val doLogin = { onAction(LoginAction.OnLoginClick(username, password)) }

    RootLayout {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier.fillMaxHeight()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .widthIn(max = 640.dp)
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
                text = stringResource(SetupR.string.login),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(SetupR.string.server_subtitle, state.serverName ?: ""),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = username,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_user),
                        contentDescription = null,
                    )
                },
                onValueChange = { username = it },
                label = { Text(text = stringResource(SetupR.string.edit_text_username_hint)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                isError = state.failureCode != null || state.error != null,
                enabled = !state.isLoading,
                modifier =
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = 72.dp)
                        .contentType(ContentType.Username),
            )
            OutlinedTextField(
                value = password,
                leadingIcon = {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_lock),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            painter =
                                if (passwordVisible) painterResource(CoreR.drawable.ic_eye_off)
                                else painterResource(CoreR.drawable.ic_eye),
                            contentDescription = null,
                        )
                    }
                },
                onValueChange = { password = it },
                label = { Text(text = stringResource(SetupR.string.edit_text_password_hint)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                keyboardActions = KeyboardActions(onGo = { doLogin() }),
                visualTransformation =
                    if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                isError = state.failureCode != null || state.error != null,
                enabled = !state.isLoading,
                modifier =
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = 72.dp)
                        .contentType(ContentType.Password),
            )

            val bannerRecoveryAction =
                state.failureCode?.let { code ->
                    resolveCompanionRecoveryAction(
                        code = code,
                        companionBaseUrl = state.companionUrl,
                        serverId = serverId,
                        username = username.ifBlank { prefilledUsername },
                    )
                }
            val relinkRecoveryAction =
                bannerRecoveryAction
                    ?: if (state.pendingSignedInUsername != null) {
                        resolveCompanionRecoveryAction(
                            code = OnboardingFailureCode.PAIRING_NO_VERIFIED_USER,
                            companionBaseUrl = state.companionUrl,
                            serverId = serverId,
                            username = state.pendingSignedInUsername,
                        )
                    } else {
                        null
                    }

            state.failureCode?.let { code ->
                ErrorBannerWithCopy(
                    context = context,
                    code = code,
                    recoveryAction = bannerRecoveryAction,
                    onRetryCompanion =
                        if (bannerRecoveryAction != null) {
                            { onAction(LoginAction.OnRetryCompanionPairing) }
                        } else {
                            null
                        },
                    isRetryingCompanion = state.isCheckingCompanion,
                )
            }

            if (state.companionSelectionCandidates.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.companion_choose_account_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.companionSelectionCandidates.forEach { candidate ->
                            val statusText = stringResource(companionCandidateStatusRes(candidate))
                            val onSelect = {
                                onAction(
                                    LoginAction.OnSelectCompanionCandidate(
                                        serverId = candidate.serverId,
                                        userId = candidate.userId,
                                        username = candidate.username,
                                    ),
                                )
                            }
                            if (candidate.requiresCredentialUpdate) {
                                OutlinedButton(
                                    onClick = onSelect,
                                    enabled = !state.isCheckingCompanion,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "${candidate.username} • ${candidate.serverName}",
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
                                FilledTonalButton(
                                    onClick = onSelect,
                                    enabled = !state.isCheckingCompanion,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = "${candidate.username} • ${candidate.serverName}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.showCompanionRelinkOption || state.pendingSignedInUsername != null) {
                CompanionRelinkCard(
                    state = state,
                    recoveryAction = relinkRecoveryAction,
                    onAction = onAction,
                )
            }

            LoadingButton(
                text = stringResource(SetupR.string.login_btn_login),
                onClick = { doLogin() },
                isLoading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )

            // Quick Connect section
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(
                    text = stringResource(SetupR.string.or),
                    color = DividerDefaults.color,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            }

            when (val qc = state.quickConnectState) {
                QuickConnectState.Idle -> {
                    OutlinedButton(
                        onClick = { onAction(LoginAction.OnStartQuickConnect) },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.login_btn_quick_connect),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                QuickConnectState.Checking -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Text(text = stringResource(SetupR.string.quick_connect_checking))
                    }
                }
                is QuickConnectState.Active -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(SetupR.string.quick_connect_instructions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val grouped = QuickConnectController.formatGroupedCode(qc.code)
                            val spoken = QuickConnectController.formatSpacedForAccessibility(qc.code)
                            Text(
                                text = grouped,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics {
                                    contentDescription = spoken
                                    liveRegion = LiveRegionMode.Polite
                                },
                            )
                            if (qc.rotatedNotice) {
                                Text(
                                    text = stringResource(SetupR.string.quick_connect_rotated_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            TextButton(onClick = { onAction(LoginAction.OnCancelQuickConnect) }) {
                                Text(stringResource(SetupR.string.cancel))
                            }
                        }
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
                QuickConnectState.Expired -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(
                            text = stringResource(SetupR.string.quick_connect_expired),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = { onAction(LoginAction.OnRetryQuickConnect) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(SetupR.string.quick_connect_retry))
                        }
                    }
                }
                is QuickConnectState.Failed -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ErrorBannerWithCopy(context = context, code = qc.code)
                        OutlinedButton(
                            onClick = { onAction(LoginAction.OnRetryQuickConnect) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(SetupR.string.quick_connect_retry))
                        }
                    }
                }
                is QuickConnectState.Authenticated -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Text(text = stringResource(SetupR.string.sign_in))
                    }
                }
            }

            if (onBrowseOfflineMedia != null) {
                TextButton(
                    onClick = onBrowseOfflineMedia,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(SetupR.string.browse_downloads_and_local_media))
                }
            }

            if (state.disclaimer != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = state.disclaimer, style = MaterialTheme.typography.bodyLarge)
            }
        }
        FilledTonalButton(
            onClick = { onAction(LoginAction.OnBackClick) },
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(painter = painterResource(CoreR.drawable.ic_arrow_left), contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Back", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(
            onClick = { onAction(LoginAction.OnChangeServerClick) },
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp),
        ) {
            Icon(painter = painterResource(CoreR.drawable.ic_server), contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text("Server", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@PreviewScreenSizes
@Composable
private fun LoginScreenLayoutPreview() {
    SpatialFinTheme {
        LoginScreenLayout(
            serverId = "srv-preview",
            serverAddress = "http://192.168.1.50:8096",
            state = LoginState(serverName = "Home Jellyfin"),
            onAction = {},
        )
    }
}
