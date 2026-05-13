package dev.jdtech.jellyfin.cast.discovery

import android.content.Context
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.adapter.airplay.AirPlayDiscovery
import dev.jdtech.jellyfin.cast.adapter.googlecast.GoogleCastDiscovery
import dev.jdtech.jellyfin.cast.toCastReceiver
import dev.jdtech.jellyfin.fcast.discovery.FCastDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One-shot fan-out browser that runs every protocol's discovery in parallel and returns a
 * single de-duplicated list of [CastReceiver] entries — the picker calls this once per "open
 * picker" event and gets all available targets without caring which protocol they speak.
 *
 * Each protocol's browser uses its own multicast lock today. That's fine for PR 3 — the
 * Galaxy XR network stack tolerates two locks at once. When AirPlay lands in PR 4 we'll
 * consider hoisting the lock acquisition into this class.
 *
 * Result shape: [Result.fcast] + [Result.googleCast] split for callers (like the picker) that
 * want to render them in separate sections, plus [Result.unified] for callers that just want
 * the merged stream.
 */
class MultiProtocolDiscovery(private val context: Context) {

    data class Result(
        val fcast: List<CastReceiver>,
        val googleCast: List<CastReceiver>,
        val airPlay: List<CastReceiver> = emptyList(),
    ) {
        /** All discovered receivers, ordered FCast → GoogleCast → AirPlay. */
        val unified: List<CastReceiver> get() = fcast + googleCast + airPlay
    }

    /**
     * Browse all protocols in parallel. Returns when every browser has either yielded results
     * or hit [timeoutMs]. The mDNS timeout is per-browser, so the wall-clock wait stays close
     * to `timeoutMs` rather than scaling with the number of protocols.
     */
    suspend fun browse(timeoutMs: Long = 5_000L): Result = withContext(Dispatchers.IO) {
        coroutineScope {
            val fcastJob = async {
                runCatching { FCastDiscovery(context).browse(timeoutMs) }
                    .onFailure { Timber.tag(TAG).w(it, "FCast browse failed") }
                    .getOrDefault(emptyList())
                    .map { it.toCastReceiver() }
            }
            val googleCastJob = async {
                runCatching { GoogleCastDiscovery(context).browse(timeoutMs) }
                    .onFailure { Timber.tag(TAG).w(it, "Google Cast browse failed") }
                    .getOrDefault(emptyList())
            }
            val airPlayJob = async {
                runCatching { AirPlayDiscovery(context).browse(timeoutMs) }
                    .onFailure { Timber.tag(TAG).w(it, "AirPlay browse failed") }
                    .getOrDefault(emptyList())
            }
            val results = listOf(fcastJob, googleCastJob, airPlayJob).awaitAll()
            Result(
                fcast = results[0],
                googleCast = results[1],
                airPlay = results[2],
            )
        }
    }

    private companion object {
        const val TAG = "MultiProtoDiscovery"
    }
}
