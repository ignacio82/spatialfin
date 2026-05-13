package dev.spatialfin.fcast.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.spatialfin.BuildConfig
import dev.spatialfin.fcast.session.CastSessionManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debug-only `adb am broadcast` backdoor that fires a split-A/V cast without requiring the
 * user to perform the gaze+pinch picker dance on the XR. Intended for development iteration:
 * after the user has done the initial calibration pick once, this lets the developer trigger
 * subsequent test plays from a workstation shell.
 *
 * Trigger:
 * ```
 * adb shell am broadcast -a dev.spatialfin.debug.SPLIT_AV \
 *   --es item_id <jellyfin-uuid> \
 *   [--ei host_a <a> --ei host_b <b> --ei host_c <c> --ei host_d <d> --ei port 46899] \
 *   [--es start_ms 0]
 * ```
 * If `host_a`..`host_d` are omitted, the currently picked receiver (set via the UI picker)
 * is reused — pick-once-then-iterate. Optional `start_ms` overrides the resume position
 * (defaults to 0 = beginning).
 *
 * Lifetime: registered exclusively in debug builds. In release builds the
 * [installIfDebug] call is a no-op so this code path never enters the user-facing binary.
 */
@Singleton
class SplitAvDebugBridge @Inject constructor(
    private val sessionManager: CastSessionManager,
    private val repository: JellyfinRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun installIfDebug(context: Context) {
        if (!BuildConfig.DEBUG) return
        val filter = IntentFilter(ACTION_SPLIT_AV_TEST)
        // Debug-only: register as EXPORTED so `adb shell am broadcast` from the dev
        // workstation can trigger it. NOT_EXPORTED silently swallows shell broadcasts
        // even with `-p <package>`. Safe because the entire bridge is BuildConfig.DEBUG-
        // gated and the APK already runs `android:debuggable=true` only on debug builds.
        val flags = Context.RECEIVER_EXPORTED
        context.registerReceiver(receiver, filter, flags)
        Timber.tag(TAG).i("SplitAvDebugBridge installed (action=%s)", ACTION_SPLIT_AV_TEST)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val itemIdString = intent.getStringExtra(EXTRA_ITEM_ID)
            val itemId = itemIdString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (itemId == null) {
                Timber.tag(TAG).w("debug broadcast missing/invalid item_id=%s", itemIdString)
                return
            }
            val startMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
            val maybeReceiver = parseReceiverOrNull(intent)
            scope.launch {
                try {
                    if (maybeReceiver != null) sessionManager.pickReceiver(maybeReceiver)
                    sessionManager.setSplitAvMode(true)
                    val item = repository.getItem(itemId)
                    if (item == null) {
                        Timber.tag(TAG).w("debug broadcast: getItem(%s) returned null", itemId)
                        return@launch
                    }
                    val result = sessionManager.castSpatialItemSplitAv(
                        item = item,
                        startPositionMs = startMs,
                        localPlayerIntentBuilder = {
                            // Drive the same XR Activity production launches use, with
                            // splitAvVideoRole=true so it registers itself as the video master.
                            // Returns null for non-movie/episode/season/show items — caller
                            // logs and bails.
                            XrPlayerActivity.createIntentForItem(
                                context = ctx.applicationContext,
                                item = item,
                                startFromBeginning = startMs == 0L,
                                splitAvVideoRole = true,
                            )
                        },
                    )
                    Timber.tag(TAG).i("debug broadcast: castSpatialItemSplitAv → %b", result)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "debug broadcast: castSpatialItemSplitAv threw")
                }
            }
        }
    }

    private fun parseReceiverOrNull(intent: Intent): FCastReceiver? {
        val a = intent.getIntExtra(EXTRA_HOST_A, -1)
        val b = intent.getIntExtra(EXTRA_HOST_B, -1)
        val c = intent.getIntExtra(EXTRA_HOST_C, -1)
        val d = intent.getIntExtra(EXTRA_HOST_D, -1)
        if (a < 0 || b < 0 || c < 0 || d < 0) return null
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        val host = "$a.$b.$c.$d"
        return FCastReceiver(
            host = host,
            port = port,
            name = "DebugBackdoor",
            source = FCastReceiver.Source.Manual,
        )
    }

    private companion object {
        const val TAG = "SplitAvDebug"
        const val ACTION_SPLIT_AV_TEST = "dev.spatialfin.debug.SPLIT_AV"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_HOST_A = "host_a"
        const val EXTRA_HOST_B = "host_b"
        const val EXTRA_HOST_C = "host_c"
        const val EXTRA_HOST_D = "host_d"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 46899
    }
}
