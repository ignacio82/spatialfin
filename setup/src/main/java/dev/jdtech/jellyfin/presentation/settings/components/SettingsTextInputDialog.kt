package dev.jdtech.jellyfin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.spatialfin.presentation.theme.spacings

@Composable
fun SettingsTextInputDialog(
    title: String,
    description: String,
    initialValue: String,
    onUpdate: (String) -> Unit,
    onDismissRequest: () -> Unit,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val textFieldState = remember {
        TextFieldState(
            initialText = initialValue,
            initialSelection = TextRange(initialValue.length),
        )
    }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BaseDialog(
        title = title,
        onDismiss = onDismissRequest,
        negativeButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(SettingsR.string.cancel))
            }
        },
        positiveButton = {
            TextButton(onClick = { onUpdate(textFieldState.text.toString()) }) {
                Text(text = stringResource(SettingsR.string.save))
            }
        },
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding)) {
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
            OutlinedSecureTextField(
                state = textFieldState,
                modifier =
                    Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .contentType(ContentType.Password),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                onKeyboardAction = { onUpdate(textFieldState.text.toString()) },
                textObfuscationMode = TextObfuscationMode.Hidden,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacings.medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val clip = clipboard.getClipEntry()
                                ?.clipData?.getItemAt(0)?.text?.toString().orEmpty()
                            textFieldState.setTextAndPlaceCursorAtEnd(clip)
                        }
                    },
                ) {
                    Text(stringResource(CoreR.string.paste))
                }
                TextButton(
                    onClick = { textFieldState.clearText() },
                ) {
                    Text(stringResource(CoreR.string.clear))
                }
                if (actionLabel != null && onActionClick != null) {
                    TextButton(onClick = onActionClick) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
