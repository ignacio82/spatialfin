package dev.jdtech.jellyfin.presentation.setup.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.onboarding.CompanionRecoveryAction
import dev.jdtech.jellyfin.setup.onboarding.openCompanionUrlInBrowser
import dev.jdtech.jellyfin.setup.presentation.login.CompanionRelinkState
import dev.jdtech.jellyfin.setup.presentation.login.LoginAction
import dev.jdtech.jellyfin.setup.presentation.login.LoginState

/**
 * Opt-in card shown when recovering from Companion user-link or broker failures so the user can
 * either authorize Companion during sign-in (with an ephemeral admin password) or retry / skip if
 * Companion failed after Jellyfin authentication succeeded.
 */
@Composable
fun CompanionRelinkCard(
    state: LoginState,
    recoveryAction: CompanionRecoveryAction?,
    onAction: (LoginAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Ephemeral in-memory state only — never use rememberSaveable for admin passwords.
    var adminPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val pendingUser = state.pendingSignedInUsername
    if (pendingUser != null) {
        val linking = state.companionRelinkState is CompanionRelinkState.Linking
        val failed = state.companionRelinkState as? CompanionRelinkState.Failed
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(SetupR.string.companion_relink_paused_title, pendingUser),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (failed != null) {
                    Text(
                        text = failed.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = adminPassword,
                    onValueChange = { adminPassword = it },
                    label = { Text(stringResource(SetupR.string.companion_relink_admin_password_label)) },
                    supportingText = { Text(stringResource(SetupR.string.companion_relink_admin_password_hint)) },
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
                    singleLine = true,
                    enabled = !linking,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (!linking) onAction(LoginAction.OnRetryCompanionRelink(adminPassword))
                            },
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (linking) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(SetupR.string.companion_relink_in_progress),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onAction(LoginAction.OnRetryCompanionRelink(adminPassword)) },
                        enabled = !linking,
                    ) {
                        Text(stringResource(SetupR.string.companion_relink_retry))
                    }
                    OutlinedButton(
                        onClick = { onAction(LoginAction.OnSkipCompanionRelinkAndContinue) },
                        enabled = !linking,
                    ) {
                        Text(stringResource(SetupR.string.companion_relink_skip))
                    }
                }
                if (recoveryAction != null) {
                    TextButton(
                        onClick = { openCompanionUrlInBrowser(context, recoveryAction.targetUrl) },
                    ) {
                        Text(stringResource(recoveryAction.labelRes))
                    }
                }
            }
        }
        return
    }

    if (!state.showCompanionRelinkOption) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(SetupR.string.companion_relink_toggle_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(SetupR.string.companion_relink_toggle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.companionRelinkEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) adminPassword = ""
                        onAction(LoginAction.OnCompanionRelinkToggled(enabled))
                    },
                    enabled = !state.isLoading,
                )
            }

            if (state.companionRelinkEnabled) {
                OutlinedTextField(
                    value = adminPassword,
                    onValueChange = { next ->
                        adminPassword = next
                        onAction(LoginAction.OnCompanionAdminPasswordChanged(next))
                    },
                    label = { Text(stringResource(SetupR.string.companion_relink_admin_password_label)) },
                    supportingText = { Text(stringResource(SetupR.string.companion_relink_admin_password_hint)) },
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
                    singleLine = true,
                    enabled = !state.isLoading,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.companionRelinkState is CompanionRelinkState.Linking) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(SetupR.string.companion_relink_in_progress),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
