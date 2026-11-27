package dev.spatialfin.fcast.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.cast.CastProtocol
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.fcast.protocol.AudioRouteInfo
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.settings.domain.TheaterAvrTokenStore
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
 * Persists remembered cast targets (legacy FCast devices + paired `SendspinAvr` Theater rooms),
 * cached audio codecs, calibration offsets, and per-room XR anchors.
 */
@Singleton
class RememberedReceiversStore internal constructor(
    private val prefsProvider: () -> SharedPreferences,
    private val hasPairedAvrToken: (String) -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        theaterAvrTokenStore: TheaterAvrTokenStore,
    ) : this(
        prefsProvider = { context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE) },
        hasPairedAvrToken = { id -> theaterAvrTokenStore.hasToken(id) },
    )

    constructor(context: Context) : this(
        prefsProvider = { context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE) },
        hasPairedAvrToken = { false },
    )

    private val prefs: SharedPreferences by lazy(prefsProvider)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(
        val host: String,
        val port: Int,
        val name: String,
        val lastSeenMs: Long,
        val appName: String? = null,
        val appVersion: String? = null,
        val audioLatencyMs: Int? = null,
        val audioLatencyCalibratedAtMs: Long? = null,
        val supportedAudioCodecs: List<String>? = null,
        val routes: List<RouteEntry> = emptyList(),
        // Additive Spec 1 fields (backward-compatible with older FCast JSON blobs):
        val id: String? = null,
        val protocol: String? = null,
        val useTls: Boolean = false,
        val anchorUuid: String? = null,
        val anchorToMlpTransform: List<Float>? = null,
    )

    @Serializable
    private data class RouteEntry(
        val fingerprint: String,
        val label: String? = null,
        val baseLatencyMs: Int? = null,
        val manualVideoOffsetMs: Int = 0,
        val learnedCodecCorrections: Map<String, Int> = emptyMap(),
        val calibratedAtMs: Long? = null,
        val supportedAudioCodecs: List<String>? = null,
        val maxChannelCount: Int? = null,
        val lastSeenMs: Long? = null,
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
                    appName = it.appName,
                    appVersion = it.appVersion,
                    audioLatencyMs = it.audioLatencyMs,
                    audioLatencyCalibratedAtMs = it.audioLatencyCalibratedAtMs,
                    supportedAudioCodecs = it.supportedAudioCodecs,
                    routes = it.routes.map { route ->
                        RememberedReceiverRoute(
                            fingerprint = route.fingerprint,
                            label = route.label,
                            baseLatencyMs = route.baseLatencyMs,
                            manualVideoOffsetMs = route.manualVideoOffsetMs,
                            learnedCodecCorrections = route.learnedCodecCorrections,
                            calibratedAtMs = route.calibratedAtMs,
                            supportedAudioCodecs = route.supportedAudioCodecs,
                            maxChannelCount = route.maxChannelCount,
                            lastSeenMs = route.lastSeenMs,
                        )
                    },
                    id = it.id ?: "FCast:${it.host}:${it.port}",
                    protocol = it.protocol ?: CastProtocol.FCast.name,
                    useTls = it.useTls,
                    anchorUuid = it.anchorUuid,
                    anchorToMlpTransform = it.anchorToMlpTransform,
                )
            }
    }

    suspend fun upsert(receiver: FCastReceiver, lastSeenMs: Long = System.currentTimeMillis()) =
        mutex.withLock {
            val existing = load().toMutableList()
            val fcastId = "FCast:${receiver.host}:${receiver.port}"
            val prior = existing.firstOrNull {
                it.resolvedId == fcastId || (it.host == receiver.host && it.port == receiver.port)
            }
            existing.removeAll {
                it.resolvedId == fcastId || (it.host == receiver.host && it.port == receiver.port)
            }
            existing += RememberedReceiver(
                host = receiver.host,
                port = receiver.port,
                name = receiver.name.ifBlank { "${receiver.host}:${receiver.port}" },
                lastSeenMs = lastSeenMs,
                appName = receiver.appName?.takeIf { it.isNotBlank() } ?: prior?.appName,
                appVersion = receiver.appVersion?.takeIf { it.isNotBlank() } ?: prior?.appVersion,
                audioLatencyMs = prior?.audioLatencyMs,
                audioLatencyCalibratedAtMs = prior?.audioLatencyCalibratedAtMs,
                supportedAudioCodecs = prior?.supportedAudioCodecs,
                routes = prior?.routes.orEmpty(),
                id = fcastId,
                protocol = CastProtocol.FCast.name,
                useTls = false,
                anchorUuid = prior?.anchorUuid,
                anchorToMlpTransform = prior?.anchorToMlpTransform,
            )
            saveLocked(existing)
        }

    suspend fun upsertCastReceiver(
        receiver: CastReceiver,
        lastSeenMs: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val existing = load().toMutableList()
        val prior = existing.firstOrNull { it.resolvedId == receiver.id }
            ?: if (receiver.protocol == CastProtocol.FCast) {
                existing.firstOrNull { it.host == receiver.host && it.port == receiver.port }
            } else {
                null
            }
        existing.removeAll { it === prior || it.resolvedId == receiver.id }
        existing += RememberedReceiver(
            host = receiver.host,
            port = receiver.port,
            name = receiver.name.ifBlank { "${receiver.host}:${receiver.port}" },
            lastSeenMs = lastSeenMs,
            appName = receiver.appName?.takeIf { it.isNotBlank() } ?: prior?.appName,
            appVersion = receiver.appVersion?.takeIf { it.isNotBlank() } ?: prior?.appVersion,
            audioLatencyMs = prior?.audioLatencyMs,
            audioLatencyCalibratedAtMs = prior?.audioLatencyCalibratedAtMs,
            supportedAudioCodecs = prior?.supportedAudioCodecs,
            routes = prior?.routes.orEmpty(),
            id = receiver.id,
            protocol = receiver.protocol.name,
            useTls = receiver.useTls,
            anchorUuid = prior?.anchorUuid,
            anchorToMlpTransform = prior?.anchorToMlpTransform,
        )
        saveLocked(existing)
    }

    suspend fun forget(host: String, port: Int) = mutex.withLock {
        saveLocked(load().filterNot { it.host == host && it.port == port })
    }

    suspend fun forgetById(id: String) = mutex.withLock {
        val normalized = id.removePrefix("SendspinAvr:")
        saveLocked(
            load().filterNot {
                it.resolvedId == id || it.resolvedId.removePrefix("SendspinAvr:") == normalized
            },
        )
    }

    suspend fun pruneStale(
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = STALE_AGE_MS,
    ): Int = mutex.withLock {
        val existing = load()
        val (keep, drop) = existing.partition { entry ->
            entry.audioLatencyMs != null ||
                (entry.castProtocol == CastProtocol.SendspinAvr && hasPairedAvrToken(entry.resolvedId)) ||
                (now - entry.lastSeenMs) <= maxAgeMs
        }
        if (drop.isEmpty()) return@withLock 0
        Timber.tag(TAG).i(
            "pruneStale: dropping %d entries older than %d days",
            drop.size, maxAgeMs / (24L * 60 * 60 * 1000),
        )
        saveLocked(keep)
        drop.size
    }

    suspend fun setAudioLatency(
        host: String,
        port: Int,
        audioLatencyMs: Int?,
        calibratedAtMs: Long = System.currentTimeMillis(),
        route: AudioRouteInfo? = null,
    ) = mutex.withLock {
        val sanitized = audioLatencyMs?.coerceAtLeast(0)
        val existing = load()
        val target = existing.firstOrNull { it.host == host && it.port == port } ?: return@withLock
        val updated = existing.map {
            if (it === target) {
                val routeFingerprint = route?.fingerprint?.takeIf { fp -> fp.isNotBlank() }
                it.copy(
                    audioLatencyMs = sanitized,
                    audioLatencyCalibratedAtMs = if (sanitized == null) null else calibratedAtMs,
                    routes = if (routeFingerprint == null) {
                        it.routes
                    } else {
                        it.routes.upsertRoute(
                            fingerprint = routeFingerprint,
                            label = route.label,
                            supportedAudioCodecs = route.supportedAudioCodecs,
                            maxChannelCount = route.maxChannelCount,
                            baseLatencyMs = sanitized,
                            calibratedAtMs = if (sanitized == null) null else calibratedAtMs,
                            lastSeenMs = calibratedAtMs,
                        )
                    },
                )
            } else it
        }
        saveLocked(updated)
    }

    suspend fun setSupportedAudioCodecs(
        host: String,
        port: Int,
        codecs: List<String>,
    ) = mutex.withLock {
        val existing = load()
        val target = existing.firstOrNull { it.host == host && it.port == port } ?: return@withLock
        saveLocked(applyCodecs(existing, target, codecs))
    }

    suspend fun setSupportedAudioCodecsById(
        id: String,
        codecs: List<String>,
    ) = mutex.withLock {
        val existing = load()
        val target = existing.firstOrNull { it.resolvedId == id } ?: return@withLock
        saveLocked(applyCodecs(existing, target, codecs))
    }

    suspend fun setRoomAnchor(
        serverId: String,
        anchorUuid: String?,
        anchorToMlpTransform: List<Float>?,
    ) = mutex.withLock {
        val normalized = serverId.removePrefix("SendspinAvr:")
        val existing = load()
        val target = existing.firstOrNull {
            it.resolvedId == serverId || it.resolvedId.removePrefix("SendspinAvr:") == normalized
        } ?: return@withLock
        val updated = existing.map {
            if (it === target) {
                it.copy(
                    anchorUuid = anchorUuid,
                    anchorToMlpTransform = anchorToMlpTransform,
                )
            } else it
        }
        saveLocked(updated)
    }

    private fun applyCodecs(
        existing: List<RememberedReceiver>,
        target: RememberedReceiver,
        codecs: List<String>,
    ): List<RememberedReceiver> {
        val sanitized = codecs.map { it.lowercase().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return existing.map {
            if (it === target) {
                it.copy(
                    supportedAudioCodecs = sanitized,
                    routes = it.routes.map { route ->
                        if (route.supportedAudioCodecs.isNullOrEmpty()) {
                            route.copy(supportedAudioCodecs = sanitized)
                        } else {
                            route
                        }
                    },
                )
            } else it
        }
    }

    suspend fun ensureRouteRecord(
        host: String,
        port: Int,
        route: AudioRouteInfo,
        fallbackBaseLatencyMs: Int?,
        fallbackManualVideoOffsetMs: Int = 0,
        fallbackSupportedAudioCodecs: List<String>? = null,
        seenAtMs: Long = System.currentTimeMillis(),
    ) = mutex.withLock {
        val fingerprint = route.fingerprint?.takeIf { it.isNotBlank() } ?: return@withLock
        val existing = load()
        val target = existing.firstOrNull { it.host == host && it.port == port } ?: return@withLock
        val updated = existing.map {
            if (it === target) {
                it.copy(
                    routes = it.routes.upsertRoute(
                        fingerprint = fingerprint,
                        label = route.label,
                        supportedAudioCodecs = route.supportedAudioCodecs ?: fallbackSupportedAudioCodecs,
                        maxChannelCount = route.maxChannelCount,
                        baseLatencyMs = fallbackBaseLatencyMs,
                        manualVideoOffsetMs = fallbackManualVideoOffsetMs,
                        lastSeenMs = seenAtMs,
                    ),
                )
            } else it
        }
        saveLocked(updated)
    }

    suspend fun setManualVideoOffset(
        host: String,
        port: Int,
        routeFingerprint: String,
        offsetMs: Int,
    ) = mutex.withLock {
        val sanitized = offsetMs.coerceIn(-200, 200)
        val existing = load()
        val target = existing.firstOrNull { it.host == host && it.port == port } ?: return@withLock
        val updated = existing.map {
            if (it === target) {
                it.copy(
                    routes = it.routes.upsertRoute(
                        fingerprint = routeFingerprint,
                        manualVideoOffsetMs = sanitized,
                        lastSeenMs = System.currentTimeMillis(),
                    ),
                )
            } else it
        }
        saveLocked(updated)
    }

    suspend fun setLearnedCodecCorrection(
        host: String,
        port: Int,
        routeFingerprint: String,
        codecKey: String,
        correctionMs: Int,
    ) = mutex.withLock {
        val sanitizedCodec = codecKey.lowercase().trim().takeIf { it.isNotBlank() } ?: return@withLock
        val sanitizedCorrection = correctionMs.coerceIn(-120, 120)
        val existing = load()
        val target = existing.firstOrNull { it.host == host && it.port == port } ?: return@withLock
        val updated = existing.map {
            if (it === target) {
                it.copy(
                    routes = it.routes.upsertRoute(
                        fingerprint = routeFingerprint,
                        learnedCodecCorrections = mapOf(sanitizedCodec to sanitizedCorrection),
                        lastSeenMs = System.currentTimeMillis(),
                    ),
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
                    appName = it.appName,
                    appVersion = it.appVersion,
                    audioLatencyMs = it.audioLatencyMs,
                    audioLatencyCalibratedAtMs = it.audioLatencyCalibratedAtMs,
                    supportedAudioCodecs = it.supportedAudioCodecs,
                    routes = it.routes.map { route ->
                        RouteEntry(
                            fingerprint = route.fingerprint,
                            label = route.label,
                            baseLatencyMs = route.baseLatencyMs,
                            manualVideoOffsetMs = route.manualVideoOffsetMs,
                            learnedCodecCorrections = route.learnedCodecCorrections,
                            calibratedAtMs = route.calibratedAtMs,
                            supportedAudioCodecs = route.supportedAudioCodecs,
                            maxChannelCount = route.maxChannelCount,
                            lastSeenMs = route.lastSeenMs,
                        )
                    },
                    id = it.resolvedId,
                    protocol = it.castProtocol.name,
                    useTls = it.useTls,
                    anchorUuid = it.anchorUuid,
                    anchorToMlpTransform = it.anchorToMlpTransform,
                )
            }
        prefs.edit { putString(KEY_RECEIVERS, json.encodeToString(capped)) }
    }

    private fun List<RememberedReceiverRoute>.upsertRoute(
        fingerprint: String,
        label: String? = null,
        baseLatencyMs: Int? = null,
        manualVideoOffsetMs: Int? = null,
        learnedCodecCorrections: Map<String, Int> = emptyMap(),
        calibratedAtMs: Long? = null,
        supportedAudioCodecs: List<String>? = null,
        maxChannelCount: Int? = null,
        lastSeenMs: Long? = null,
    ): List<RememberedReceiverRoute> {
        val prior = firstOrNull { it.fingerprint == fingerprint }
        val sanitizedCorrections = learnedCodecCorrections
            .mapKeys { it.key.lowercase().trim() }
            .filterKeys { it.isNotBlank() }
            .mapValues { it.value.coerceIn(-120, 120) }
        val merged = RememberedReceiverRoute(
            fingerprint = fingerprint,
            label = label ?: prior?.label,
            baseLatencyMs = baseLatencyMs ?: prior?.baseLatencyMs,
            manualVideoOffsetMs = manualVideoOffsetMs ?: prior?.manualVideoOffsetMs ?: 0,
            learnedCodecCorrections = prior?.learnedCodecCorrections.orEmpty() + sanitizedCorrections,
            calibratedAtMs = calibratedAtMs ?: prior?.calibratedAtMs,
            supportedAudioCodecs = supportedAudioCodecs ?: prior?.supportedAudioCodecs,
            maxChannelCount = maxChannelCount ?: prior?.maxChannelCount,
            lastSeenMs = lastSeenMs ?: prior?.lastSeenMs,
        )
        return (filterNot { it.fingerprint == fingerprint } + merged)
            .sortedByDescending { it.lastSeenMs ?: 0L }
    }

    private companion object {
        const val TAG = "FCastRemembered"
        const val PREFS_FILE = "fcast_remembered_receivers"
        const val KEY_RECEIVERS = "receivers"
        const val MAX_REMEMBERED = 16
        const val STALE_AGE_MS = 90L * 24 * 60 * 60 * 1000
    }
}

data class RememberedReceiver(
    val host: String,
    val port: Int,
    val name: String,
    val lastSeenMs: Long,
    val appName: String? = null,
    val appVersion: String? = null,
    val audioLatencyMs: Int? = null,
    val audioLatencyCalibratedAtMs: Long? = null,
    val supportedAudioCodecs: List<String>? = null,
    val routes: List<RememberedReceiverRoute> = emptyList(),
    val id: String? = null,
    val protocol: String? = null,
    val useTls: Boolean = false,
    val anchorUuid: String? = null,
    val anchorToMlpTransform: List<Float>? = null,
) {
    val resolvedId: String
        get() = id?.takeIf { it.isNotBlank() } ?: "FCast:$host:$port"

    val castProtocol: CastProtocol
        get() = protocol?.let { p ->
            CastProtocol.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
        } ?: CastProtocol.FCast
}

data class RememberedReceiverRoute(
    val fingerprint: String,
    val label: String? = null,
    val baseLatencyMs: Int? = null,
    val manualVideoOffsetMs: Int = 0,
    val learnedCodecCorrections: Map<String, Int> = emptyMap(),
    val calibratedAtMs: Long? = null,
    val supportedAudioCodecs: List<String>? = null,
    val maxChannelCount: Int? = null,
    val lastSeenMs: Long? = null,
)
