package dev.jdtech.jellyfin.presentation.setup.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.setup.components.LoadingButton
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginEvent
import dev.jdtech.jellyfin.setup.presentation.login.LoginState
import dev.jdtech.jellyfin.setup.presentation.login.LoginViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents

@Composable
fun LoginScreen(
    onSuccess: () -> Unit,
    onChangeServerClick: () -> Unit,
    onBackClick: () -> Unit,
    prefilledUsername: String? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) {
        viewModel.loadServer()
        viewModel.loadDisclaimer()
        viewModel.loadQuickConnectEnabled()
    }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is LoginEvent.Success -> onSuccess()
        }
    }

    LoginScreenLayout(
        state = state,
        onAction = { action ->
            when (action) {
                is LoginAction.OnChangeServerClick -> onChangeServerClick()
                is LoginAction.OnBackClick -> onBackClick()
                else -> Unit
            }
            viewModel.onAction(action)
        },
        prefilledUsername = prefilledUsername,
    )
}

@Composable
private fun LoginScreenLayout(
    state: LoginState,
    onAction: (LoginAction) -> Unit,
    prefilledUsername: String? = null,
) {
    val scrollState = rememberScrollState()
    var username by rememberSaveable { mutableStateOf(prefilledUsername ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val doLogin = { onAction(LoginAction.OnLoginClick(username, password)) }

    RootLayout {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier.fillMaxHeight()
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 640.dp)
                    .align(Alignment.Center)
                    .verticalScroll(scrollState),
        ) {
            Image(
                painter = painterResource(id = CoreR.drawable.ic_banner),
                contentDescription = null,
                modifier = Modifier.width(320.dp).align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(SetupR.string.login),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(SetupR.string.server_subtitle, state.serverName ?: ""),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(24.dp))
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
                isError = state.error != null,
                enabled = !state.isLoading,
                modifier =
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = 72.dp)
                        .contentType(ContentType.Username),
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                isError = state.error != null,
                enabled = !state.isLoading,
                supportingText = {
                    if (state.error != null) {
                        Text(
                            text = state.error!!.asString(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                },
                modifier =
                    Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = 72.dp)
                        .contentType(ContentType.Password),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LoadingButton(
                text = stringResource(SetupR.string.login_btn_login),
                onClick = { doLogin() },
                isLoading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(state.quickConnectEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                        Text(
                            text = stringResource(SetupR.string.or),
                            color = DividerDefaults.color,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box {
                        if (state.quickConnectCode != null) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier.size(24.dp)
                                        .align(Alignment.CenterStart)
                                        .offset(x = 8.dp),
                            )
                        }
                        OutlinedButton(
                            onClick = { onAction(LoginAction.OnQuickConnectClick) },
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp),
                        ) {
                            Text(
                                text =
                                    if (state.quickConnectCode != null) state.quickConnectCode!!
                                    else stringResource(SetupR.string.login_btn_quick_connect),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            if (state.disclaimer != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = state.disclaimer!!, style = MaterialTheme.typography.bodyLarge)
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
private fun AddServerScreenLayoutPreview() {
    SpatialFinTheme {
        LoginScreenLayout(
            state =
                LoginState(
                    serverName = "Demo Server",
                    quickConnectEnabled = true,
                    disclaimer = "Sample disclaimer",
                ),
            onAction = {},
        )
    }
}
