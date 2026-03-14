package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings

/**
 * Base dialog using Material3 AlertDialog so that EnableXrComponentOverrides can elevate it
 * to a floating spatial panel on Android XR devices.
 */
@Composable
fun BaseDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(contentPadding: PaddingValues) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                content(PaddingValues(horizontal = MaterialTheme.spacings.default))
            }
        },
        confirmButton = {},
    )
}

@Composable
fun BaseDialog(
    title: String,
    onDismiss: () -> Unit,
    negativeButton: @Composable () -> Unit,
    positiveButton: @Composable () -> Unit,
    content: @Composable ColumnScope.(contentPadding: PaddingValues) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                content(PaddingValues(horizontal = MaterialTheme.spacings.default))
            }
        },
        dismissButton = negativeButton,
        confirmButton = positiveButton,
    )
}

@Preview
@Composable
private fun BaseDialogPreview() {
    SpatialFinTheme {
        BaseDialog(title = "Dialog Title", onDismiss = {}) {
            Text(text = "Dialog content goes here.")
        }
    }
}

@Preview
@Composable
private fun BaseDialogButtonsPreview() {
    SpatialFinTheme {
        BaseDialog(
            title = "Dialog Title",
            negativeButton = { TextButton(onClick = {}) { Text(text = "Negative") } },
            positiveButton = { TextButton(onClick = {}) { Text(text = "Positive") } },
            onDismiss = {},
        ) {
            Text(text = "Dialog content goes here.")
        }
    }
}
