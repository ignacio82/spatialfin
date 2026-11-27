package dev.spatialfin.fcast.session

import android.content.Context
import android.content.Intent
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.models.SpatialFinItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Unified launch plan resolved by [CastSessionManager.castSpatialItemSplitAv] so both the remote
 * audio receiver and the local video-master player open the exact same Jellyfin media source,
 * audio stream index, and start-hold policy.
 */
data class SplitAvLaunchPlan(
    val mediaSourceIndex: Int? = null,
    val audioStreamIndex: Int? = null,
    val holdAtStart: Boolean = false,
    val startPositionMs: Long? = null,
)

/**
 * Single decision point for "user tapped Play on a media item": route to casting if a receiver is
 * picked, otherwise fall through to the local player Activity. Call sites used to do
 * `context.startActivity(BeamPlayerActivity.createIntentForSpatialItem(...))` directly; they now
 * call [launchPlayback] with the same item plus the [CastSessionManager], and the session
 * manager handles the remote-vs-local fork.
 *
 * Intentionally takes a `intentBuilder` lambda rather than the intent itself so we don't build
 * the local-player intent when we're going to route remotely (avoids resolving extras that the
 * cast path doesn't need).
 *
 * Split-A/V mode is a third routing path. When [CastSessionManager.splitAvMode] is on (or a
 * [CastProtocol.SendspinAvr] Theater Audio room is picked) AND a split intent builder is supplied,
 * the launcher routes to [CastSessionManager.castSpatialItemSplitAv].
 */
fun launchPlayback(
    context: Context,
    sessionManager: CastSessionManager,
    scope: CoroutineScope,
    item: SpatialFinItem,
    startPositionMs: Long? = null,
    mediaSourceIndex: Int? = null,
    audioStreamIndex: Int? = null,
    formFactor: String = "xr_full_space",
    onLocalLaunchFailed: () -> Unit = {},
    splitAvIntentBuilder: () -> Intent? = { null },
    splitAvIntentBuilderForPlan: ((SplitAvLaunchPlan) -> Intent?)? = null,
    intentBuilder: () -> Intent?,
): Boolean {
    if (sessionManager.hasCastIntent()) {
        scope.launch {
            val isTheaterAvr = sessionManager.isTheaterAvrPicked()
            if (sessionManager.splitAvMode.value || isTheaterAvr) {
                sessionManager.castSpatialItemSplitAv(
                    item = item,
                    startPositionMs = startPositionMs,
                    requestedMediaSourceIndex = mediaSourceIndex,
                    requestedAudioStreamIndex = audioStreamIndex,
                    formFactor = formFactor,
                    localPlayerIntentBuilder = splitAvIntentBuilder,
                    localPlayerIntentBuilderForPlan = splitAvIntentBuilderForPlan,
                    localFallbackIntentBuilder = intentBuilder,
                )
                return@launch
            }
            val ok = sessionManager.castSpatialItem(item, startPositionMs)
            if (!ok) {
                Timber.tag("PlaybackLauncher").w("Cast routing failed; preserving explicit cast intent")
            }
        }
        return true
    }
    val intent = intentBuilder() ?: return false
    return runCatching { context.startActivity(intent) }
        .onFailure { onLocalLaunchFailed() }
        .isSuccess
}
