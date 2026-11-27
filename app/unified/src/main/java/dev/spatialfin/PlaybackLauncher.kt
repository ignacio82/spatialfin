package dev.spatialfin

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.jdtech.jellyfin.player.xr.MultitaskPlayerActivity
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.presentation.player.PlayRequest
import dev.spatialfin.fcast.session.LocalFCastSession
import dev.spatialfin.fcast.session.launchPlayback
import kotlinx.coroutines.CoroutineScope

/**
 * The app-side implementation of the [PlayRequest] seam. Browse screens emit a
 * [PlayRequest]; this maps it to the right `:player:xr` Activity intent and FCast
 * routing. Keeping it here means the screens (and the feature modules they will
 * move into) never reference `XrPlayerActivity` / `MultitaskPlayerActivity` /
 * the FCast session launcher directly.
 *
 * The logic below was lifted verbatim from the per-screen play handlers
 * (Movie/Episode/Show/Season/Home + Local/Network video) so playback behavior —
 * immersive-vs-multitask, stereo/projection extras, FCast Split-A/V routing,
 * resume position — is unchanged.
 */
@Composable
fun rememberPlaybackLauncher(
    onPrepareMultitaskLaunch: (() -> Unit)? = null,
): (PlayRequest) -> Unit {
    val context = LocalContext.current
    val fcastSession = LocalFCastSession.current
    val scope = rememberCoroutineScope()
    val latestPrepareMultitask = rememberUpdatedState(onPrepareMultitaskLaunch)
    return remember(context, fcastSession, scope) {
        { request ->
            launchPlayRequest(
                context = context,
                fcastSession = fcastSession,
                scope = scope,
                request = request,
                onPrepareMultitaskLaunch = { latestPrepareMultitask.value?.invoke() },
            )
        }
    }
}

internal fun launchPlayRequest(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: CoroutineScope,
    request: PlayRequest,
    onPrepareMultitaskLaunch: (() -> Unit)? = null,
) {
    when (request) {
        is PlayRequest.LibraryItem ->
            launchLibraryItem(
                context = context,
                fcastSession = fcastSession,
                scope = scope,
                request = request,
                onPrepareMultitaskLaunch = onPrepareMultitaskLaunch,
            )
        is PlayRequest.LocalMedia -> {
            val intent = if (request.immersive) {
                XrPlayerActivity.createIntentForLocalMedia(
                    context = context,
                    mediaStoreId = request.mediaStoreId,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                )
            } else {
                onPrepareMultitaskLaunch?.invoke()
                MultitaskPlayerActivity.createIntentForLocalMedia(
                    context = context,
                    mediaStoreId = request.mediaStoreId,
                    startFromBeginning = request.startFromBeginning,
                )
            }
            context.startActivity(intent)
        }
        is PlayRequest.NetworkMedia -> {
            val intent = if (request.immersive) {
                XrPlayerActivity.createIntentForNetworkMedia(
                    context = context,
                    networkVideoId = request.networkVideoId,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                )
            } else {
                onPrepareMultitaskLaunch?.invoke()
                MultitaskPlayerActivity.createIntentForNetworkMedia(
                    context = context,
                    networkVideoId = request.networkVideoId,
                    startFromBeginning = request.startFromBeginning,
                )
            }
            context.startActivity(intent)
        }
        is PlayRequest.UniversalMedia -> {
            context.startActivity(
                XrPlayerActivity.createIntentForUniversalMedia(
                    context,
                    request.pluginId,
                    request.id,
                    request.videoUrl,
                    request.name,
                    stereoMode = request.stereoMode,
                    projection = request.projection,
                )
            )
        }
        is PlayRequest.UriMedia -> {
            val intent = if (request.immersive) {
                XrPlayerActivity.createIntentForUri(
                    context = context,
                    uri = request.uri,
                    title = request.title,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                )
            } else {
                onPrepareMultitaskLaunch?.invoke()
                MultitaskPlayerActivity.createIntentForUri(
                    context = context,
                    uri = request.uri,
                    title = request.title,
                    startFromBeginning = request.startFromBeginning,
                )
            }
            context.startActivity(intent)
        }
    }
}

private fun launchLibraryItem(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: CoroutineScope,
    request: PlayRequest.LibraryItem,
    onPrepareMultitaskLaunch: (() -> Unit)? = null,
) {
    val targetActivity = if (request.immersive) {
        XrPlayerActivity::class.java
    } else {
        MultitaskPlayerActivity::class.java
    }

    val buildLocalIntent: () -> Intent = {
        if (request.openSyncPlayDialogOnStart) {
            // Watch-party path: always immersive, no FCast routing.
            XrPlayerActivity.createIntent(
                context = context,
                itemId = request.itemId,
                itemKind = request.itemKind,
                startFromBeginning = request.startFromBeginning,
                stereoMode = request.stereoMode,
                projection = request.projection ?: "flat",
                openSyncPlayDialogOnStart = true,
            )
        } else {
            if (!request.immersive) {
                onPrepareMultitaskLaunch?.invoke()
            }
            Intent(context, targetActivity).apply {
                putExtra("itemId", request.itemId.toString())
                putExtra("itemKind", request.itemKind)
                putExtra("startFromBeginning", request.startFromBeginning)
                request.mediaSourceIndex?.let { putExtra("mediaSourceIndex", it) }
                request.maxBitrate?.let { putExtra("maxBitrate", it) }
                request.audioStreamIndex?.let { putExtra("audioStreamIndex", it) }
                request.subtitleStreamIndex?.let { putExtra("subtitleStreamIndex", it) }
                if (request.subtitlesDisabled) putExtra("subtitlesDisabled", true)
                if (request.immersive) {
                    putExtra("stereoMode", request.stereoMode)
                    request.projection?.let { putExtra("projection", it) }
                }
            }
        }
    }

    val item = request.item
    if (request.allowFcastRouting && fcastSession != null && item != null) {
        val resumeMs = request.resumePositionMs
        // Spec 1: XR Home Space (MultitaskPlayerActivity, !request.immersive) is excluded from
        // Split A/V v1; only immersive XR Full Space launches route through Split A/V.
        val allowSplitAv = request.immersive
        if (!allowSplitAv && fcastSession.hasCastIntent() && (fcastSession.splitAvMode.value || fcastSession.isTheaterAvrPicked())) {
            val localIntent = buildLocalIntent()
            dev.jdtech.jellyfin.core.util.XrPlayerSessionClient.notifyPendingLaunch(localIntent)
            context.startActivity(localIntent)
            return
        }
        launchPlayback(
            context = context,
            sessionManager = fcastSession,
            scope = scope,
            item = item,
            startPositionMs = resumeMs?.takeIf { it > 0 },
            mediaSourceIndex = request.mediaSourceIndex,
            audioStreamIndex = request.audioStreamIndex,
            formFactor = "xr_full_space",
            splitAvIntentBuilder = {
                if (!allowSplitAv) {
                    null
                } else {
                    XrPlayerActivity.createIntent(
                        context = context,
                        itemId = request.itemId,
                        itemKind = request.itemKind,
                        startFromBeginning = request.startFromBeginning,
                        stereoMode = request.stereoMode,
                        projection = request.projection ?: "flat",
                        mediaSourceIndex = request.mediaSourceIndex,
                        audioStreamIndex = request.audioStreamIndex,
                        subtitleStreamIndex = request.subtitleStreamIndex,
                        subtitlesDisabled = request.subtitlesDisabled,
                        maxBitrate = request.maxBitrate,
                        splitAvVideoRole = true,
                    )
                }
            },
            splitAvIntentBuilderForPlan = if (!allowSplitAv) {
                null
            } else {
                { plan ->
                    XrPlayerActivity.createIntent(
                        context = context,
                        itemId = request.itemId,
                        itemKind = request.itemKind,
                        startFromBeginning = request.startFromBeginning,
                        stereoMode = request.stereoMode,
                        projection = request.projection ?: "flat",
                        mediaSourceIndex = plan.mediaSourceIndex ?: request.mediaSourceIndex,
                        audioStreamIndex = plan.audioStreamIndex ?: request.audioStreamIndex,
                        subtitleStreamIndex = request.subtitleStreamIndex,
                        subtitlesDisabled = request.subtitlesDisabled,
                        maxBitrate = request.maxBitrate,
                        splitAvVideoRole = true,
                        holdAtStart = plan.holdAtStart,
                    )
                }
            },
            intentBuilder = buildLocalIntent,
        )
    } else {
        val localIntent = buildLocalIntent()
        dev.jdtech.jellyfin.core.util.XrPlayerSessionClient.notifyPendingLaunch(localIntent)
        context.startActivity(localIntent)
    }
}

/** Watch/remote launch while the browse activity is visible; no player must already be running. */
internal suspend fun launchWearLibraryItem(
    context: Context,
    repository: dev.jdtech.jellyfin.repository.JellyfinRepository,
    deviceClass: dev.spatialfin.unified.DeviceClass,
    itemId: String,
    positionMs: Long,
    canLaunch: () -> Boolean,
): String {
    check(canLaunch()) { "Open and unlock SpatialFin on the target device" }
    val item = repository.getItem(java.util.UUID.fromString(itemId)) ?: error("Item not found")
    check(canLaunch()) { "Open and unlock SpatialFin on the target device" }
    val intent = when (deviceClass) {
        dev.spatialfin.unified.DeviceClass.XR -> XrPlayerActivity.createIntentForItem(context, item)
        dev.spatialfin.unified.DeviceClass.TV -> dev.jdtech.jellyfin.player.tv.TvPlayerActivity.createIntentForSpatialItem(context, item, startPositionMs = positionMs.takeIf { it > 0 })
        dev.spatialfin.unified.DeviceClass.PHONE -> dev.jdtech.jellyfin.player.beam.BeamPlayerActivity.createIntentForSpatialItem(
            context, item, startPositionMs = positionMs.takeIf { it > 0 })
    } ?: error("This item cannot be played on the target")
    dev.jdtech.jellyfin.core.util.XrPlayerSessionClient.notifyPendingLaunch(intent)
    context.startActivity(intent)
    return "Opening ${item.name}"
}
