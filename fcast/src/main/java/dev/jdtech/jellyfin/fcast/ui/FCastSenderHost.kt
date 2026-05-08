package dev.jdtech.jellyfin.fcast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * Drop-in host that wires together [FCastReceiverPickerDialog] and a screen-owned
 * [FCastCastingController]. Use it from any player surface as:
 *
 * ```kotlin
 * val controller = remember { FCastCastingController() }
 * FCastSenderHost(
 *     visible = showPicker,
 *     buildPlayMessage = { /* construct from current player state */ },
 *     controller = controller,
 *     onDismiss = { showPicker = false },
 * )
 * ```
 *
 * The host owns nothing about the surface's UI — it only renders the picker dialog when [visible]
 * and dispatches the picked receiver to the controller. The controller's lifecycle (shutdown on
 * dispose) is delegated back to the caller because the same controller may live longer than this
 * composable (e.g. across orbiter open/close).
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

/**
 * Disposable wrapper that calls [FCastCastingController.shutdown] when the composition leaves.
 * Use as `rememberFCastCastingController()` from a screen-level scope.
 */
@Composable
fun rememberFCastCastingController(): FCastCastingController {
    val controller = remember { FCastCastingController() }
    DisposableEffect(controller) {
        onDispose { controller.shutdown() }
    }
    return controller
}
