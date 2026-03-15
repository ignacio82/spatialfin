package dev.jdtech.jellyfin.presentation.setup.addresses

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.presentation.components.XrConfirmDialog
import dev.jdtech.jellyfin.setup.R as SetupR

@Composable
fun DeleteServerAddressDialog(address: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    XrConfirmDialog(
        title = stringResource(SetupR.string.remove_server_address),
        message = stringResource(SetupR.string.remove_server_address_dialog_text, address),
        confirmLabel = stringResource(SetupR.string.confirm),
        dismissLabel = stringResource(SetupR.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
