package dev.spatialfin.companion.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.player.session.voice.ActivePlayerSessionHolder
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the live player session onto the paired watch.
 *
 * Everything here is gated on [watchConnected]: with no watch paired this class does
 * no periodic work at all, because the host is an XR headset, a phone, or a TV and
 * none of them should burn wakeups replicating state nobody reads.
 */
@Singleton
class WearStatePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vitalsCollector: WearVitalsCollector,
    private val nextUpPublisher: WearNextUpPublisher,
    private val repository: JellyfinRepository,
) {

    private val publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observationJob: Job? = null

    private val watchConnected = MutableStateFlow(false)

    private var lastPublished: WearNowPlayingState? = null
    private var lastPublishAtMs: Long = 0L

    /** Cover art is fetched once per item, not once per state tick. */
    private var coverArtItemId: String? = null
    private var coverArtAsset: Asset? = null

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    fun startObserving() {
        if (observationJob != null) return
        Timber.i("WearStatePublisher: starting active session state observation")
        observationJob = publisherScope.launch {
            launch { refreshWatchPresenceLoop() }
            launch { pumpActiveSession() }
            launch { publishNowPlayingUpdates() }
            launch { vitalsLoop() }
            launch { nextUpLoop() }
        }
    }

    fun stopObserving() {
        observationJob?.cancel()
        observationJob = null
    }

    /** Called by [WearHostDataLayerService] on peer connect/disconnect. */
    fun onPeerPresenceChanged() {
        publisherScope.launch { refreshWatchPresence() }
    }

    private suspend fun refreshWatchPresence(): Boolean {
        val connected = runCatching {
            Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
        }.getOrDefault(false)
        if (watchConnected.value != connected) {
            Timber.i("WearStatePublisher: watch presence -> %b", connected)
        }
        watchConnected.value = connected
        return connected
    }

    private suspend fun refreshWatchPresenceLoop() {
        while (currentCoroutineContext().isActive) {
            refreshWatchPresence()
            // Peer callbacks drive the fast path; this is just a slow correction for
            // presence changes the Data Layer never reported.
            delay(PRESENCE_RECHECK_MS)
        }
    }

    /**
     * The holder only rebuilds its snapshot on bind and on dispatch, so without this
     * pump the watch's scrubber would freeze at whatever the position was when the
     * player bound. Ticks only while a session exists *and* a watch is listening.
     */
    private suspend fun pumpActiveSession() {
        ActivePlayerSessionHolder.activeSession.collectLatest { session ->
            if (session == null) return@collectLatest
            while (currentCoroutineContext().isActive) {
                if (watchConnected.value) {
                    runCatching { ActivePlayerSessionHolder.refresh() }
                        .onFailure { Timber.w(it, "WearStatePublisher: session refresh failed") }
                    delay(if (ActivePlayerSessionHolder.nowPlayingState.value?.isPlaying == true) PLAYING_TICK_MS else IDLE_TICK_MS)
                } else {
                    delay(IDLE_TICK_MS)
                }
            }
        }
    }

    private suspend fun publishNowPlayingUpdates() {
        // Conflated by StateFlow semantics: while a put is in flight, intermediate
        // ticks collapse into the newest one rather than queueing behind it.
        ActivePlayerSessionHolder.nowPlayingState.collect { state ->
            if (!watchConnected.value) return@collect
            if (state == null) {
                if (lastPublished != null) {
                    lastPublished = null
                    publishDisconnectedState()
                }
                return@collect
            }
            if (!shouldPublish(state)) return@collect
            publishNowPlayingState(state)
        }
    }

    /** Significant changes go out immediately; position ticks are rate-limited. */
    private fun shouldPublish(state: WearNowPlayingState): Boolean {
        val previous = lastPublished ?: return true
        val significant = state.isPlaying != previous.isPlaying ||
            state.title != previous.title ||
            state.itemId != previous.itemId ||
            state.durationSeconds != previous.durationSeconds ||
            state.currentAudioTrack != previous.currentAudioTrack ||
            state.currentSubtitleTrack != previous.currentSubtitleTrack ||
            state.segmentType != previous.segmentType ||
            kotlin.math.abs(state.positionSeconds - previous.positionSeconds) > POSITION_JUMP_SECONDS
        if (significant) return true
        return System.currentTimeMillis() - lastPublishAtMs >= MIN_PUBLISH_INTERVAL_MS
    }

    suspend fun publishNowPlaying() {
        val state = ActivePlayerSessionHolder.nowPlayingState.value
        if (state != null) publishNowPlayingState(state) else publishDisconnectedState()
    }

    suspend fun publishVitals() {
        runCatching {
            val vitals = vitalsCollector.collectVitals()
            val payload = WearProtocolCodec.encodeVitals(vitals)
            val putDataMap = PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_VITALS).apply {
                dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
                dataMap.putLong(WearProtocolPaths.DATA_KEY_TIMESTAMP, System.currentTimeMillis())
            }
            Wearable.getDataClient(context).putDataItem(putDataMap.asPutDataRequest()).await()
            Timber.d("WearStatePublisher: published vitals (battery=%d%%)", vitals.batteryPercent)
        }.onFailure {
            Timber.w(it, "WearStatePublisher: failed to publish vitals")
        }
    }

    suspend fun publishNextUp() {
        nextUpPublisher.publishNextUp()
    }

    private suspend fun vitalsLoop() {
        while (currentCoroutineContext().isActive) {
            if (watchConnected.value) publishVitals()
            delay(VITALS_INTERVAL_MS)
        }
    }

    private suspend fun nextUpLoop() {
        while (currentCoroutineContext().isActive) {
            if (watchConnected.value) nextUpPublisher.publishNextUp()
            delay(NEXT_UP_INTERVAL_MS)
        }
    }

    private suspend fun publishNowPlayingState(state: WearNowPlayingState) {
        runCatching {
            val asset = resolveCoverArt(state.itemId)
            val outgoing = state.copy(hasCoverArtAsset = asset != null)
            val payload = WearProtocolCodec.encodeNowPlaying(outgoing)
            val putDataMap = PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_NOW_PLAYING).apply {
                dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
                dataMap.putLong(WearProtocolPaths.DATA_KEY_TIMESTAMP, System.currentTimeMillis())
                if (asset != null) {
                    dataMap.putAsset(WearProtocolPaths.ASSET_KEY_COVER_ART, asset)
                }
            }

            val request = putDataMap.asPutDataRequest()
            if (state.isPlaying) request.setUrgent()

            Wearable.getDataClient(context).putDataItem(request).await()
            lastPublished = outgoing
            lastPublishAtMs = System.currentTimeMillis()
            Timber.d(
                "WearStatePublisher: published now playing '%s' (playing=%b, pos=%ds)",
                state.title, state.isPlaying, state.positionSeconds,
            )
        }.onFailure {
            Timber.w(it, "WearStatePublisher: failed to put now playing data item")
        }
    }

    private suspend fun publishDisconnectedState() {
        runCatching {
            coverArtItemId = null
            coverArtAsset = null
            val emptyState = WearNowPlayingState(timestampEpochMs = System.currentTimeMillis())
            val payload = WearProtocolCodec.encodeNowPlaying(emptyState)
            val putDataMap = PutDataMapRequest.create(WearProtocolPaths.PATH_STATE_NOW_PLAYING).apply {
                dataMap.putByteArray(WearProtocolPaths.DATA_KEY_PAYLOAD, payload)
                dataMap.putLong(WearProtocolPaths.DATA_KEY_TIMESTAMP, System.currentTimeMillis())
            }
            Wearable.getDataClient(context).putDataItem(putDataMap.asPutDataRequest()).await()
            lastPublishAtMs = System.currentTimeMillis()
            Timber.d("WearStatePublisher: published idle/disconnected state")
        }.onFailure {
            Timber.w(it, "WearStatePublisher: failed to publish idle state")
        }
    }

    /**
     * The watch cannot resolve a Jellyfin image URL — it has no credentials while
     * tethered and no route to the server at all when the phone is the only link —
     * so the poster travels as a downscaled `Asset`.
     */
    private suspend fun resolveCoverArt(itemId: String?): Asset? {
        if (itemId.isNullOrBlank()) {
            coverArtItemId = null
            coverArtAsset = null
            return null
        }
        if (itemId == coverArtItemId) return coverArtAsset

        coverArtItemId = itemId
        coverArtAsset = runCatching {
            withContext(Dispatchers.IO) {
                val uuid = UUID.fromString(itemId)
                val item = repository.getItem(uuid) ?: return@withContext null
                val url = (item.images.primary ?: item.images.showPrimary)?.toString()
                    ?: return@withContext null
                val bytes = httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    response.body?.bytes()
                } ?: return@withContext null
                Asset.createFromBytes(downscaleImage(bytes))
            }
        }.getOrElse {
            Timber.w(it, "WearStatePublisher: cover art fetch failed for %s", itemId)
            null
        }
        return coverArtAsset
    }

    private fun downscaleImage(rawBytes: ByteArray, maxDimension: Int = 240, quality: Int = 75): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return rawBytes

        val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height, 1.0f)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)

        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        bitmap.recycle()
        return stream.toByteArray()
    }

    private companion object {
        /**
         * Position-only replication cadence. Anything the user would notice — play/pause,
         * a track switch, a seek — bypasses this and goes out immediately, so the only
         * thing paying the 2 s granularity is the scrubber creeping forward. At 1 Hz a
         * two-hour film would be ~7 200 Bluetooth puts against a watch battery budget of
         * a few percentage points per hour; refreshing faster than we publish is pure waste.
         */
        const val PLAYING_TICK_MS = 2_000L
        const val IDLE_TICK_MS = 5_000L
        const val MIN_PUBLISH_INTERVAL_MS = 2_000L
        const val POSITION_JUMP_SECONDS = 2L
        const val VITALS_INTERVAL_MS = 60_000L
        const val NEXT_UP_INTERVAL_MS = 15 * 60_000L
        const val PRESENCE_RECHECK_MS = 5 * 60_000L
    }
}
