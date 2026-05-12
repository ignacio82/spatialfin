package dev.spatialfin.fcast.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.spatialfin.BuildConfig
import dev.spatialfin.fcast.session.FCastSessionManager
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debug-only Activity that exposes the split-A/V cast flow to `adb shell am start`. Replaces
 * the earlier BroadcastReceiver approach — Android 12+ Background Activity Launch (BAL)
 * restrictions silently block Activity launches issued from a BroadcastReceiver, so the XR's
 * `XrPlayerActivity` (which enters Full Space Mode) never came up. An `am start`-targeted
 * Activity provides the Activity-source context the OS needs to authorise the FSM transition.
 *
 * Trigger:
 * ```
 * adb shell am start -n dev.spatialfin.debug/dev.spatialfin.fcast.debug.SplitAvDebugLaunchActivity \
 *   --es item_id <jellyfin-uuid> \
 *   [--ei host_a 192 --ei host_b 168 --ei host_c 1 --ei host_d 102 --ei port 46899] \
 *   [--el start_ms 0]
 * ```
 *
 * The Activity finishes itself the moment the cast pipeline accepts the request — `XrPlayerActivity`
 * comes up on its own and takes over the screen. Errors are logged but otherwise ignored; the
 * Activity always terminates promptly to avoid leaving an empty surface on the user.
 */
@AndroidEntryPoint
class SplitAvDebugLaunchActivity : ComponentActivity() {

    @Inject lateinit var sessionManager: FCastSessionManager
    @Inject lateinit var repository: JellyfinRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme.NoDisplay requires finish() to be called before onResume() returns, so we
        // *must* finish synchronously here. The cast work is dispatched on an application-
        // scoped coroutine (`appScope`) that survives this Activity's death.
        try {
            if (!BuildConfig.DEBUG) {
                Timber.tag(TAG).w("invoked on non-debug build — refusing")
                return
            }
            val intent = intent
            val itemIdString = intent?.getStringExtra(EXTRA_ITEM_ID)
            val itemId = itemIdString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (itemId == null) {
                Timber.tag(TAG).w("missing/invalid item_id=%s", itemIdString)
                return
            }
            val startMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
            val maybeReceiver = parseReceiverOrNull(intent)

            // Launch XrPlayerActivity from *this* Activity so the XR shell grants Full Space
            // Mode authority — the underlying FCastSessionManager.castSpatialItemSplitAv
            // launches via applicationContext.startActivity, which the FSM gate rejects ("no
            // source activity"). We do the FSM launch ourselves and tell the session manager
            // to skip its own launch (returns null from the builder).
            val ok = runCatching {
                val xrIntent = XrPlayerActivity.createIntent(
                    context = this,
                    itemId = itemId,
                    itemKind = "Movie",
                    startFromBeginning = startMs == 0L,
                    splitAvVideoRole = true,
                )
                startActivity(xrIntent)
            }.isSuccess
            Timber.tag(TAG).i("XrPlayerActivity launch ok=%b", ok)

            appScope.launch {
                try {
                    if (maybeReceiver != null) sessionManager.pickReceiver(maybeReceiver)
                    sessionManager.setSplitAvMode(true)
                    val item = repository.getItem(itemId)
                    if (item == null) {
                        Timber.tag(TAG).w("getItem(%s) returned null", itemId)
                        return@launch
                    }
                    val result = sessionManager.castSpatialItemSplitAv(
                        item = item,
                        startPositionMs = startMs,
                        localPlayerIntentBuilder = { null }, // already launched above
                    )
                    Timber.tag(TAG).i("castSpatialItemSplitAv → %b", result)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "castSpatialItemSplitAv threw")
                }
            }
        } finally {
            finish()
        }
    }

    private fun parseReceiverOrNull(intent: android.content.Intent): FCastReceiver? {
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
