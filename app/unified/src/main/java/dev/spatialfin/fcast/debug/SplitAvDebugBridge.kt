package dev.spatialfin.fcast.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrDiscoveryBrowser
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.player.core.splitav.SplitAvTheaterBridge
import dev.jdtech.jellyfin.player.core.splitav.TheaterClientEvent
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.spatialfin.BuildConfig
import dev.spatialfin.fcast.session.CastSessionManager
import dev.spatialfin.fcast.session.SplitAvController
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debug-only `adb am broadcast` backdoor that fires a split-A/V cast (legacy FCast or Spec 0
 * `sendspin_avr` Theater Audio) or exercises seek / track-switch actions without requiring UI
 * navigation.
 *
 * Trigger examples:
 * ```
 * adb shell am broadcast -a dev.spatialfin.debug.SPLIT_AV \
 *   --es protocol sendspin_avr --es target xr|beam \
 *   --es host 192.168.1.50 --ei port 1984 --es server_id avr-550e8400 \
 *   --es item_id <jellyfin-uuid> [--el start_ms 0]
 *
 * adb shell am broadcast -a dev.spatialfin.debug.SPLIT_AV --es action seek --el seek_ms 60000
 * adb shell am broadcast -a dev.spatialfin.debug.SPLIT_AV --es action track_switch --ei audio_stream_index 2
 * ```
 */
@Singleton
class SplitAvDebugBridge @Inject constructor(
    private val sessionManager: CastSessionManager,
    private val splitAvController: SplitAvController,
    private val repository: JellyfinRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun installIfDebug(context: Context) {
        if (!BuildConfig.DEBUG) return
        val filter = IntentFilter(ACTION_SPLIT_AV_TEST)
        val flags = Context.RECEIVER_EXPORTED
        context.registerReceiver(receiver, filter, flags)
        Timber.tag(TAG).i("SplitAvDebugBridge installed (action=%s)", ACTION_SPLIT_AV_TEST)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.getStringExtra(EXTRA_ACTION)?.lowercase()?.trim()
            if (action == "seek") {
                val seekMs = intent.getLongExtra(EXTRA_SEEK_MS, 0L).coerceAtLeast(0L)
                scope.launch {
                    Timber.tag(TAG).i("debug broadcast: action=seek seekMs=%d", seekMs)
                    splitAvController.seekTo(seekMs)
                }
                return
            }
            if (action == "track_switch") {
                val audioStreamIndex = intent.getIntExtra(EXTRA_AUDIO_STREAM_INDEX, -1)
                if (audioStreamIndex < 0) {
                    Timber.tag(TAG).w("debug broadcast: track_switch missing valid audio_stream_index")
                    return
                }
                val codec = intent.getStringExtra(EXTRA_CODEC)
                Timber.tag(TAG).i("debug broadcast: action=track_switch audioStreamIndex=%d codec=%s", audioStreamIndex, codec)
                SplitAvTheaterBridge.emitClientEvent(
                    TheaterClientEvent.AudioTrackChanged(audioStreamIndex, codec),
                )
                return
            }

            val itemIdString = intent.getStringExtra(EXTRA_ITEM_ID)
            val itemId = itemIdString?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (itemId == null) {
                Timber.tag(TAG).w("debug broadcast missing/invalid item_id=%s", itemIdString)
                return
            }
            val startMs = intent.getLongExtra(EXTRA_START_MS, 0L).coerceAtLeast(0L)
            val protocol = intent.getStringExtra(EXTRA_PROTOCOL)?.lowercase()?.trim()
            val targetFormFactor = intent.getStringExtra(EXTRA_TARGET)?.lowercase()?.trim() ?: "xr"

            scope.launch {
                try {
                    if (protocol == "sendspin_avr") {
                        val host = intent.getStringExtra(EXTRA_HOST)?.trim()
                        if (!host.isNullOrEmpty()) {
                            val port = intent.getIntExtra(
                                EXTRA_PORT,
                                SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
                            )
                            val useTls = intent.getBooleanExtra(EXTRA_USE_TLS, false)
                            val serverId = intent.getStringExtra(EXTRA_SERVER_ID)?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?: "avr-debug"
                            sessionManager.pickCastReceiver(
                                CastReceiver(
                                    id = "SendspinAvr:$serverId",
                                    name = "Debug Theater AVR",
                                    host = host,
                                    port = port,
                                    protocol = CastProtocol.SendspinAvr,
                                    capabilities = setOf(
                                        CastCapability.Audio,
                                        CastCapability.Volume,
                                        CastCapability.SplitAv,
                                        CastCapability.TheaterAudioV1,
                                    ),
                                    source = CastReceiver.Source.Manual,
                                    useTls = useTls,
                                ),
                            )
                        }
                    } else {
                        val maybeReceiver = parseFCastReceiverOrNull(intent)
                        if (maybeReceiver != null) sessionManager.pickReceiver(maybeReceiver)
                    }
                    sessionManager.setSplitAvMode(true)
                    val item = repository.getItem(itemId)
                    if (item == null) {
                        Timber.tag(TAG).w("debug broadcast: getItem(%s) returned null", itemId)
                        return@launch
                    }
                    val formFactor = if (targetFormFactor == "beam") "beam_phone" else "xr_full_space"
                    val result = sessionManager.castSpatialItemSplitAv(
                        item = item,
                        startPositionMs = startMs,
                        formFactor = formFactor,
                        localPlayerIntentBuilderForPlan = { plan ->
                            if (targetFormFactor == "beam") {
                                BeamPlayerActivity.createIntentForSpatialItem(
                                    context = ctx.applicationContext,
                                    item = item,
                                    startFromBeginning = startMs == 0L,
                                    startPositionMs = plan.startPositionMs ?: startMs.takeIf { it > 0L },
                                    splitAvVideoRole = true,
                                    holdAtStart = plan.holdAtStart,
                                    mediaSourceIndex = plan.mediaSourceIndex,
                                    audioStreamIndex = plan.audioStreamIndex,
                                )
                            } else {
                                XrPlayerActivity.createIntentForItem(
                                    context = ctx.applicationContext,
                                    item = item,
                                    startFromBeginning = startMs == 0L,
                                    splitAvVideoRole = true,
                                    holdAtStart = plan.holdAtStart,
                                    mediaSourceIndex = plan.mediaSourceIndex,
                                    audioStreamIndex = plan.audioStreamIndex,
                                )
                            }
                        },
                    )
                    Timber.tag(TAG).i(
                        "debug broadcast: protocol=%s target=%s castSpatialItemSplitAv → %b",
                        protocol ?: "fcast",
                        targetFormFactor,
                        result,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "debug broadcast: castSpatialItemSplitAv threw")
                }
            }
        }
    }

    private fun parseFCastReceiverOrNull(intent: Intent): FCastReceiver? {
        val explicitHost = intent.getStringExtra(EXTRA_HOST)?.trim()?.takeIf { it.isNotEmpty() }
        val host = explicitHost ?: run {
            val a = intent.getIntExtra(EXTRA_HOST_A, -1)
            val b = intent.getIntExtra(EXTRA_HOST_B, -1)
            val c = intent.getIntExtra(EXTRA_HOST_C, -1)
            val d = intent.getIntExtra(EXTRA_HOST_D, -1)
            if (a < 0 || b < 0 || c < 0 || d < 0) return null
            "$a.$b.$c.$d"
        }
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
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
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_TARGET = "target"
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_USE_TLS = "use_tls"
        const val EXTRA_ACTION = "action"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_AUDIO_STREAM_INDEX = "audio_stream_index"
        const val EXTRA_CODEC = "codec"
        const val EXTRA_HOST_A = "host_a"
        const val EXTRA_HOST_B = "host_b"
        const val EXTRA_HOST_C = "host_c"
        const val EXTRA_HOST_D = "host_d"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 46899
    }
}
