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
 * call [launchPlayback] with the same item plus the [CastSessionManager], and the session
 * manager handles the remote-vs-local fork.
 *
 * Intentionally takes a `intentBuilder` lambda rather than the intent itself so we don't build
 * the local-player intent when we're going to route remotely (avoids resolving extras that the
 * cast path doesn't need).
 *
 * Split-A/V mode is a third routing path. When [CastSessionManager.splitAvMode] is on AND
 * [splitAvIntentBuilder] returns a non-null Intent, the launcher routes to
 * [CastSessionManager.castSpatialItemSplitAv] which runs auto-calibration if needed and then
 * sends Play (with `splitAv.role=AUDIO`) to the picked receiver while launching the local
 * video-master Activity built by [splitAvIntentBuilder]. If split mode is requested but the
 * caller didn't supply a split intent builder, the launcher logs and falls through to a normal
 * cast — better to play with bad sync than to silently swallow the user's tap.
 */
fun launchPlayback(
    context: Context,
    sessionManager: CastSessionManager,
    scope: CoroutineScope,
    item: SpatialFinItem,
    startPositionMs: Long? = null,
    onLocalLaunchFailed: () -> Unit = {},
    splitAvIntentBuilder: () -> Intent? = { null },
    intentBuilder: () -> Intent?,
): Boolean {
    if (sessionManager.hasCastIntent()) {
        scope.launch {
            if (sessionManager.splitAvMode.value) {
                // Split-A/V explicitly requested. Do NOT fall back to local on failure — the
                // calibration error dialog stays visible and the user can dismiss + re-tap Play
                // to retry. Silently launching the local player here would hide the error and
                // play the same media without split sync, which is the opposite of what the
                // user asked for.
                sessionManager.castSpatialItemSplitAv(
                    item = item,
                    startPositionMs = startPositionMs,
                    localPlayerIntentBuilder = splitAvIntentBuilder,
                )
                return@launch
            }
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
