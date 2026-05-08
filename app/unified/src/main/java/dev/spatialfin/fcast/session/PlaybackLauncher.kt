package dev.spatialfin.fcast.session

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.models.SpatialFinItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Single decision point for "user tapped Play on a media item": route to FCast if a receiver is
 * picked, otherwise fall through to the local player Activity. Call sites used to do
 * `context.startActivity(BeamPlayerActivity.createIntentForSpatialItem(...))` directly; they now
 * call [launchPlayback] with the same item plus the [FCastSessionManager], and the session
 * manager handles the remote-vs-local fork.
 *
 * Intentionally takes a `intentBuilder` lambda rather than the intent itself so we don't build
 * the local-player intent when we're going to route remotely (avoids resolving extras that the
 * cast path doesn't need).
 */
fun launchPlayback(
    context: Context,
    sessionManager: FCastSessionManager,
    scope: CoroutineScope,
    item: SpatialFinItem,
    startPositionMs: Long? = null,
    onLocalLaunchFailed: () -> Unit = {},
    intentBuilder: () -> Intent?,
): Boolean {
    if (sessionManager.hasCastIntent()) {
        scope.launch {
            val ok = sessionManager.castSpatialItem(item, startPositionMs)
            if (!ok) {
                Timber.tag("PlaybackLauncher").w("Cast routing failed; falling back to local player")
                withContext(Dispatchers.Main) {
                    val intent = intentBuilder()
                    if (intent != null) {
                        runCatching { context.startActivity(intent) }
                            .onFailure { onLocalLaunchFailed() }
                    } else {
                        onLocalLaunchFailed()
                    }
                }
            }
        }
        return true
    }
    val intent = intentBuilder() ?: return false
    return runCatching { context.startActivity(intent) }
        .onFailure { onLocalLaunchFailed() }
        .isSuccess
}
