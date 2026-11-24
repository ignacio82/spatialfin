package dev.jdtech.jellyfin.presentation.setup.addresses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.setup.welcome.ErrorBannerWithCopy
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.spatialfin.presentation.theme.SpatialFinTheme

@Composable
fun AddServerAddressDialog(
    onAdd: (address: String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    error: OnboardingFailureCode? = null,
) {
    val context = LocalContext.current
    var textFieldValue by remember { mutableStateOf(TextFieldValue(text = "")) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BaseDialog(
        title = stringResource(SetupR.string.add_server_address),
        onDismiss = { if (!isLoading) onDismiss() },
        negativeButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(text = stringResource(SetupR.string.cancel))
            }
        },
        positiveButton = {
            TextButton(
                onClick = { onAdd(textFieldValue.text) },
                enabled = !isLoading && textFieldValue.text.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(CoreR.string.add))
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier.padding(contentPadding).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { textFieldValue = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                placeholder = { Text("http://<server_ip>:8096") },
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                singleLine = true,
                enabled = !isLoading,
                isError = error != null,
            )
            if (error != null) {
                ErrorBannerWithCopy(context = context, code = error)
            }
        }
    }
}

@Preview
@Composable
private fun AddServerAddressDialogPreview() {
    SpatialFinTheme { AddServerAddressDialog(onAdd = {}, onDismiss = {}) }
}
