package dev.spatialfin

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
fun rememberPlaybackLauncher(): (PlayRequest) -> Unit {
    val context = LocalContext.current
    val fcastSession = LocalFCastSession.current
    val scope = rememberCoroutineScope()
    return remember(context, fcastSession, scope) {
        { request -> launchPlayRequest(context, fcastSession, scope, request) }
    }
}

private fun launchPlayRequest(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: CoroutineScope,
    request: PlayRequest,
) {
    when (request) {
        is PlayRequest.LibraryItem -> launchLibraryItem(context, fcastSession, scope, request)
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
    }
}

private fun launchLibraryItem(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: CoroutineScope,
    request: PlayRequest.LibraryItem,
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
            Intent(context, targetActivity).apply {
                putExtra("itemId", request.itemId.toString())
                putExtra("itemKind", request.itemKind)
                putExtra("startFromBeginning", request.startFromBeginning)
                request.mediaSourceIndex?.let { putExtra("mediaSourceIndex", it) }
                request.maxBitrate?.let { putExtra("maxBitrate", it) }
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
        launchPlayback(
            context = context,
            sessionManager = fcastSession,
            scope = scope,
            item = item,
            startPositionMs = resumeMs?.takeIf { it > 0 },
            splitAvIntentBuilder = {
                XrPlayerActivity.createIntent(
                    context = context,
                    itemId = request.itemId,
                    itemKind = request.itemKind,
                    startFromBeginning = request.startFromBeginning,
                    stereoMode = request.stereoMode,
                    projection = request.projection ?: "flat",
                    mediaSourceIndex = request.mediaSourceIndex,
                    maxBitrate = request.maxBitrate,
                    splitAvVideoRole = true,
                )
            },
            intentBuilder = buildLocalIntent,
        )
    } else {
        context.startActivity(buildLocalIntent())
    }
}
