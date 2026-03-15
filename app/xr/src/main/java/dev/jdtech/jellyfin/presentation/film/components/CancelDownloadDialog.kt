package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.components.XrConfirmDialog
import dev.spatialfin.presentation.theme.SpatialFinTheme

@Composable
fun CancelDownloadDialog(onCancel: () -> Unit, onDismiss: () -> Unit) {
    XrConfirmDialog(
        title = stringResource(CoreR.string.cancel_download),
        message = stringResource(CoreR.string.cancel_download_message),
        confirmLabel = stringResource(CoreR.string.stop_download),
        dismissLabel = stringResource(CoreR.string.cancel),
        onConfirm = onCancel,
        onDismiss = onDismiss,
    )
}

@Composable
@Preview
private fun CancelDownloadDialogPreview() {
    SpatialFinTheme { CancelDownloadDialog(onCancel = {}, onDismiss = {}) }
}
