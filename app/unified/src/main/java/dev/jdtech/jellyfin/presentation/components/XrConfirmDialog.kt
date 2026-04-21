package dev.jdtech.jellyfin.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.xr.compose.spatial.SpatialDialog

@Composable
fun XrConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpatialDialog(onDismissRequest = onDismiss) {
        BaseDialog(
            title = title,
            onDismiss = onDismiss,
            negativeButton = {
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel, style = MaterialTheme.typography.titleMedium)
                }
            },
            positiveButton = {
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, style = MaterialTheme.typography.titleMedium)
                }
            },
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
