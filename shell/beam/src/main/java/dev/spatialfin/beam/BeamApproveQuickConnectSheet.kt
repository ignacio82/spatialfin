package dev.spatialfin.beam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectApprovalState
import dev.jdtech.jellyfin.setup.quickconnect.QuickConnectApprovalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeamApproveQuickConnectSheet(
    isServerAuthenticated: Boolean,
    onDismiss: () -> Unit,
    viewModel: QuickConnectApprovalViewModel = hiltViewModel(),
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var digits by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.reset() }
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    val canSubmit =
        isServerAuthenticated &&
            state !is QuickConnectApprovalState.Submitting &&
            digits.length == 6

    val submit = {
        if (canSubmit) {
            viewModel.approve(digits)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(SetupR.string.approve_qc_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text =
                    if (isServerAuthenticated) {
                        stringResource(SetupR.string.approve_qc_body)
                    } else {
                        stringResource(SettingsR.string.settings_approve_qc_disabled_hint)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isServerAuthenticated) {
                Text(
                    text = stringResource(SetupR.string.approve_qc_phishing_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = digits,
                onValueChange = { raw -> digits = raw.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(SetupR.string.approve_qc_code_hint)) },
                singleLine = true,
                enabled = isServerAuthenticated && state !is QuickConnectApprovalState.Submitting,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            when (val current = state) {
                QuickConnectApprovalState.Approved -> {
                    Text(
                        text = stringResource(SetupR.string.approve_qc_approved),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                is QuickConnectApprovalState.Failed -> {
                    ErrorBannerWithCopy(
                        code = current.code,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                QuickConnectApprovalState.Submitting,
                QuickConnectApprovalState.Idle,
                -> Unit
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = submit,
                    enabled = canSubmit,
                ) {
                    if (state is QuickConnectApprovalState.Submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(SetupR.string.approve_qc_btn_approve))
                }
            }
        }
    }
}
