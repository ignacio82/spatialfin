package dev.jdtech.jellyfin.fcast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jdtech.jellyfin.fcast.protocol.PlayMessage
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import timber.log.Timber

/**
 * Drop-in host that wires together [FCastReceiverPickerDialog] and a caller-owned
 * [FCastCastingController]. The host owns nothing about the surface's UI — it only renders the
 * picker dialog when [visible] and dispatches the picked receiver to the controller. Lifecycle
 * is the caller's: in SpatialFin the controller is a Hilt @Singleton, so callers pass it in via
 * DI rather than creating one per-screen.
 */
@Composable
fun FCastSenderHost(
    visible: Boolean,
    buildPlayMessage: () -> PlayMessage?,
    controller: FCastCastingController,
    onDismiss: () -> Unit,
    onCastFailed: (String) -> Unit = {},
) {
    if (!visible) return

    var startingReceiver by remember { mutableStateOf<FCastReceiver?>(null) }

    FCastReceiverPickerDialog(
        onReceiverPicked = { receiver ->
            startingReceiver = receiver
            onDismiss()
        },
        onDismiss = onDismiss,
    )

    val target = startingReceiver
    if (target != null) {
        LaunchedEffect(target) {
            try {
                val play = buildPlayMessage()
                    ?: throw IllegalStateException("No active stream to cast")
                controller.startCast(target, play)
            } catch (e: Exception) {
                Timber.tag("FCastSenderHost").w(e, "FCast cast start failed")
                onCastFailed(e.message ?: "Cast failed")
            } finally {
                startingReceiver = null
            }
        }
    }
}
