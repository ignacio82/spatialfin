package dev.spatialfin.fcast.session

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persists the list of FCast receivers the user has seen, so the picker can show them
 * immediately on next open without waiting for a full mDNS scan. Keeps a cap of
 * [MAX_REMEMBERED] entries, evicting the least-recently-seen.
 *
 * Storage: a single JSON blob in a dedicated SharedPreferences file. Small, opaque, and
 * survives uninstall-with-keep-data. `lastSeenMs` is wall-clock millis from when the entry
 * was last upserted (mDNS scan or successful TCP probe).
 */
@Singleton
class RememberedReceiversStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(
        val host: String,
        val port: Int,
        val name: String,
        val lastSeenMs: Long,
        // Optional fields are additive — older blobs without these decode cleanly because
        // kotlinx.serialization respects default values for missing JSON properties.
        val audioLatencyMs: Int? = null,
        val audioLatencyCalibratedAtMs: Long? = null,
    )

    suspend fun load(): List<RememberedReceiver> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_RECEIVERS, null) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<Entry>>(raw) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to decode remembered receivers") }
            .getOrDefault(emptyList())
            .map {
                RememberedReceiver(
                    host = it.host,
                    port = it.port,
                    name = it.name,
                    lastSeenMs = it.lastSeenMs,
                    audioLatencyMs = it.audioLatencyMs,
                    audioLatencyCalibratedAtMs = it.audioLatencyCalibratedAtMs,
                )
            }
    }

    suspend fun upsert(receiver: FCastReceiver, lastSeenMs: Long = System.currentTimeMillis()) =
        mutex.withLock {
            val existing = load().toMutableList()
            // Preserve any prior calibration data — upsert is for "saw it on the network again,"
            // not "lose its measured audio offset just because mDNS bumped lastSeenMs."
            val prior = existing.firstOrNull { it.host == receiver.host && it.port == receiver.port }
            existing.removeAll { it.host == receiver.host && it.port == receiver.port }
            existing += RememberedReceiver(
                host = receiver.host,
                port = receiver.port,
                name = receiver.name.ifBlank { "${receiver.host}:${receiver.port}" },
                lastSeenMs = lastSeenMs,
                audioLatencyMs = prior?.audioLatencyMs,
                audioLatencyCalibratedAtMs = prior?.audioLatencyCalibratedAtMs,
            )
            saveLocked(existing)
        }

    suspend fun forget(host: String, port: Int) = mutex.withLock {
        saveLocked(load().filterNot { it.host == host && it.port == port })
    }

    /**
     * Persist a calibrated audio-path latency for this receiver. Used by split-A/V mode so
     * the XR side can delay its video to line up with what the user actually hears coming
     * out of the AVR/soundbar tail.
     *
     * Negative values are rejected; null clears the calibration. No-op if the receiver isn't
     * already remembered — call [upsert] first when needed.
     */
    suspend fun setAudioLatency(
        host: String,
        port: Int,
        audioLatencyMs: Int?,
        calibratedAtMs: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val sanitized = audioLatencyMs?.coerceAtLeast(0)
        val existing = load()
        val target = existing.firstOrNull { it.host == host && it.port == port } ?: return@withLock
        val updated = existing.map {
            if (it === target) {
                it.copy(
                    audioLatencyMs = sanitized,
                    audioLatencyCalibratedAtMs = if (sanitized == null) null else calibratedAtMs,
                )
            } else it
        }
        saveLocked(updated)
    }

    private fun saveLocked(receivers: List<RememberedReceiver>) {
        val capped = receivers
            .sortedByDescending { it.lastSeenMs }
            .take(MAX_REMEMBERED)
            .map {
                Entry(
                    host = it.host,
                    port = it.port,
                    name = it.name,
                    lastSeenMs = it.lastSeenMs,
                    audioLatencyMs = it.audioLatencyMs,
                    audioLatencyCalibratedAtMs = it.audioLatencyCalibratedAtMs,
                )
            }
        prefs.edit { putString(KEY_RECEIVERS, json.encodeToString(capped)) }
    }

    private companion object {
        const val TAG = "FCastRemembered"
        const val PREFS_FILE = "fcast_remembered_receivers"
        const val KEY_RECEIVERS = "receivers"
        const val MAX_REMEMBERED = 16
    }
}

data class RememberedReceiver(
    val host: String,
    val port: Int,
    val name: String,
    val lastSeenMs: Long,
    /**
     * One-way audio path latency from the moment ExoPlayer hands a sample to the AudioTrack
     * to the moment the user hears it through the AVR/soundbar/HDMI chain. Required for
     * split-A/V playback to line up XR video with TV audio. Null = uncalibrated.
     */
    val audioLatencyMs: Int? = null,
    val audioLatencyCalibratedAtMs: Long? = null,
)
