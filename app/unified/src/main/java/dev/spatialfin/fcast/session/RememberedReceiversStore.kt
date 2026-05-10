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
    )

    suspend fun load(): List<RememberedReceiver> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_RECEIVERS, null) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<Entry>>(raw) }
            .onFailure { Timber.tag(TAG).w(it, "Failed to decode remembered receivers") }
            .getOrDefault(emptyList())
            .map { RememberedReceiver(it.host, it.port, it.name, it.lastSeenMs) }
    }

    suspend fun upsert(receiver: FCastReceiver, lastSeenMs: Long = System.currentTimeMillis()) =
        mutex.withLock {
            val existing = load().toMutableList()
            existing.removeAll { it.host == receiver.host && it.port == receiver.port }
            existing += RememberedReceiver(
                host = receiver.host,
                port = receiver.port,
                name = receiver.name.ifBlank { "${receiver.host}:${receiver.port}" },
                lastSeenMs = lastSeenMs,
            )
            saveLocked(existing)
        }

    suspend fun forget(host: String, port: Int) = mutex.withLock {
        saveLocked(load().filterNot { it.host == host && it.port == port })
    }

    private fun saveLocked(receivers: List<RememberedReceiver>) {
        val capped = receivers
            .sortedByDescending { it.lastSeenMs }
            .take(MAX_REMEMBERED)
            .map { Entry(it.host, it.port, it.name, it.lastSeenMs) }
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
)
