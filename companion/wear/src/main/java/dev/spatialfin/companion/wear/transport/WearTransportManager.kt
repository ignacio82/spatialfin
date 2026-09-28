package dev.spatialfin.companion.wear.transport

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.spatialfin.companion.protocol.WearNextUpState
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearProtocolPaths
import dev.spatialfin.companion.protocol.WearVitalsState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

sealed interface TransportState {
    data class ConnectedViaDataLayer(val nodeId: String, val deviceName: String) : TransportState

    data class ConnectedViaFCastLan(val host: String, val port: Int, val deviceName: String) :
        TransportState

    data class ConnectedViaJellyfinRelay(
        val serverUrl: String,
        val sessionId: String,
        val deviceName: String,
    ) : TransportState

    data object Disconnected : TransportState
}

@Singleton
class WearTransportManager
internal constructor(
    @ApplicationContext private val context: Context,
    val dataClientRepo: WearDataClientRepository,
    val messageClientRepo: WearMessageClientRepository,
    val directLanClient: WearDirectLanClient,
    val credentialsStore: WearCredentialsStore,
    val relayClient: WearJellyfinRelayClient,
    private val scope: CoroutineScope,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        dataClientRepo: WearDataClientRepository,
        messageClientRepo: WearMessageClientRepository,
        directLanClient: WearDirectLanClient,
        credentialsStore: WearCredentialsStore,
        relayClient: WearJellyfinRelayClient,
    ) : this(
        context,
        dataClientRepo,
        messageClientRepo,
        directLanClient,
        credentialsStore,
        relayClient,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val refreshMutex = Mutex()
    private var pollingJob: Job? = null
    private var selectedRemoteSessionId: String? = null
    private var selectedDeviceId: String? = credentialsStore.selectedDeviceId
    private var selectedLan: dev.jdtech.jellyfin.fcast.sender.FCastReceiver? = null
    private var accountKey = accountKey()
    private val _targetAvailable = MutableStateFlow(false)
    val targetAvailability = _targetAvailable.asStateFlow()
    private var targetAvailable: Boolean
        get() = _targetAvailable.value
        set(value) {
            _targetAvailable.value = value
        }

    val isTargetAvailable: Boolean
        get() = targetAvailable

    private val snapshotClock = MutableStateFlow(System.currentTimeMillis())
    private var lastRefreshAt = 0L
    private var metadataJob: Job? = null
    private var metadataKey: String? = null
    private var metadata: kotlinx.serialization.json.JsonObject? = null
    private val _connectionStatus = MutableStateFlow("Looking for playback devices…")
    val connectionStatus = _connectionStatus.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback = _feedback.asStateFlow()
    private val _relayNextUp = MutableStateFlow<WearNextUpState?>(null)
    private var lastNextUpAt = 0L

    fun showFeedback(message: String) {
        _feedback.value = message
    }

    fun clearFeedback() {
        _feedback.value = null
    }

    fun supportsExtendedControls(): Boolean =
        targetAvailable &&
            when (val state = _transportState.value) {
                is TransportState.ConnectedViaDataLayer -> true
                is TransportState.ConnectedViaJellyfinRelay ->
                    _remoteSessions.value.any {
                        it.sessionId == state.sessionId && it.supportsWearCommands
                    }
                else -> false
            }

    private fun accountKey(): String? =
        credentialsStore.credentials.value?.let { "${it.serverId}/${it.userId}/${it.serverUrl}" }

    private val _remoteSessions = MutableStateFlow<List<RelaySession>>(emptyList())
    val remoteSessions: StateFlow<List<RelaySession>> = _remoteSessions.asStateFlow()

    private val _transportState = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _relayPlaybackState = MutableStateFlow<WearNowPlayingState?>(null)

    val nowPlaying: StateFlow<WearNowPlayingState?> =
        combine(
                _transportState,
                dataClientRepo.nowPlayingState,
                directLanClient.lanPlaybackState,
                _relayPlaybackState,
                snapshotClock,
            ) { transport, dataLayerState, lanState, relayState, now ->
                when (transport) {
                    is TransportState.ConnectedViaDataLayer ->
                        dataLayerState.takeIf { it.hasFreshPlayback(now) }
                    is TransportState.ConnectedViaFCastLan -> lanState
                    is TransportState.ConnectedViaJellyfinRelay -> relayState
                    TransportState.Disconnected -> null
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val vitals: StateFlow<WearVitalsState?> =
        combine(_transportState, dataClientRepo.vitalsState, _targetAvailable) {
                state,
                value,
                available ->
                value.takeIf { available && state is TransportState.ConnectedViaDataLayer }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)
    val nextUp: StateFlow<WearNextUpState?> =
        combine(dataClientRepo.nextUpState, _relayNextUp) { phone, relay -> relay ?: phone }
            .stateIn(scope, SharingStarted.Eagerly, null)
    val coverArt: StateFlow<Bitmap?> =
        combine(_transportState, dataClientRepo.coverArtBitmap) { transport, art ->
                art.takeIf { transport is TransportState.ConnectedViaDataLayer }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        dataClientRepo.startListening()
        scope.launch {
            combine(
                    credentialsStore.credentials,
                    dataClientRepo.nowPlayingState
                        .map { it.hasFreshPlayback() }
                        .distinctUntilChanged(),
                    directLanClient.connectedReceiver,
                ) { _, _, _ ->
                    Unit
                }
                .collect { refreshConnectivity() }
        }
        observeCapabilities()
        scope.launch {
            var previous: WearNowPlayingState? = null
            var lastSurfaceAt = 0L
            nowPlaying.collect { state ->
                val significant =
                    previous?.title != state?.title ||
                        previous?.isPlaying != state?.isPlaying ||
                        previous?.targetDeviceName != state?.targetDeviceName
                if (significant || System.currentTimeMillis() - lastSurfaceAt > 60_000) {
                    dataClientRepo.requestSurfaceUpdate()
                    lastSurfaceAt = System.currentTimeMillis()
                }
                previous = state
            }
        }
    }

    /**
     * A connection to the paired phone is not evidence of playback on that phone. Keep an
     * explicitly connected LAN receiver, otherwise prefer an active Data Layer player and then
     * discover playback (including XR) through Jellyfin.
     */
    fun checkConnectivity() {
        scope.launch { refreshConnectivity() }
    }

    /** Poll only while the remote is visible, including when no video has started yet. */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                refreshConnectivity()
                delay(5_000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun selectRemoteSession(sessionId: String) {
        scope.launch {
            refreshMutex.withLock {
                val session =
                    _remoteSessions.value.firstOrNull { it.sessionId == sessionId }
                        ?: return@withLock
                selectedRemoteSessionId = sessionId
                selectedDeviceId = session.deviceId.takeIf { it.isNotBlank() }
                credentialsStore.selectedDeviceId = selectedDeviceId
                selectedLan = null
                directLanClient.disconnect()
                val phone = messageClientRepo.getConnectedHostNode()
                if (
                    phone != null &&
                        session.deviceId == credentialsStore.credentials.value?.deviceId
                ) {
                    _transportState.value =
                        TransportState.ConnectedViaDataLayer(phone.id, phone.displayName)
                    targetAvailable = true
                } else useRelaySession(session)
            }
        }
    }

    suspend fun selectLanReceiver(receiver: dev.jdtech.jellyfin.fcast.sender.FCastReceiver) {
        refreshMutex.withLock {
            selectedLan = receiver
            selectedDeviceId = null
            selectedRemoteSessionId = null
            credentialsStore.selectedDeviceId = null
            targetAvailable = directLanClient.connectToReceiver(receiver)
            _transportState.value =
                TransportState.ConnectedViaFCastLan(receiver.host, receiver.port, receiver.name)
            _connectionStatus.value =
                if (targetAvailable) "Connected to cast receiver ${receiver.name}"
                else "Cast receiver unavailable. Select it to reconnect."
            if (!targetAvailable) showFeedback(_connectionStatus.value)
        }
    }

    private fun useRelaySession(session: RelaySession) {
        targetAvailable = true
        selectedRemoteSessionId = session.sessionId
        selectedDeviceId = session.deviceId.takeIf { it.isNotBlank() }
        credentialsStore.selectedDeviceId = selectedDeviceId
        _transportState.value =
            TransportState.ConnectedViaJellyfinRelay(
                credentialsStore.credentials.value?.serverUrl.orEmpty(),
                session.sessionId,
                session.deviceName,
            )
        val key = "${accountKey()}/${session.itemId}"
        _relayPlaybackState.value =
            relayClient.nowPlayingFrom(
                if (key == metadataKey && metadata != null) session.copy(metadata = metadata)
                else session
            )
        _connectionStatus.value =
            if (session.hasPlayback) "Connected to ${session.deviceName}"
            else
                "${session.deviceName} connected. Start playback or choose Continue. Local playback details may be unavailable."
        if (session.itemId != null && metadataKey != key) {
            metadataJob?.cancel()
            metadataKey = key
            metadata = null
            metadataJob = scope.launch {
                try {
                    val enriched = relayClient.loadMetadata(session)
                    if (
                        metadataKey == key &&
                            accountKey() == accountKey &&
                            selectedRemoteSessionId == session.sessionId
                    ) {
                        metadata = enriched.metadata
                        // Retain newer position/pause values if discovery finished during this
                        // request.
                        val current =
                            _remoteSessions.value.firstOrNull { it.sessionId == session.sessionId }
                                ?: session
                        _relayPlaybackState.value =
                            relayClient.nowPlayingFrom(current.copy(metadata = metadata))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (metadataKey == key) metadataKey = null
                }
            }
        }
    }

    suspend fun refreshForSurface() {
        refreshConnectivity(maxAgeMs = 15_000)
        yield() // let the derived snapshot flows publish before a tile reads them
    }

    suspend fun refreshNextUp() {
        if (
            credentialsStore.credentials.value == null ||
                System.currentTimeMillis() - lastNextUpAt < 60_000
        )
            return
        val key = accountKey()
        try {
            val items = relayClient.continueWatching()
            if (accountKey() == key) {
                _relayNextUp.value = items
                lastNextUpAt = System.currentTimeMillis()
                dataClientRepo.requestUpNextUpdate()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showFeedback(error.message ?: "Could not load Continue Watching")
        }
    }

    fun syncFromPhone() {
        scope.launch {
            val result = messageClientRepo.requestCredentialRefresh()
            showFeedback(result.getOrElse { it.message ?: "Account sync failed" })
            refreshConnectivity()
        }
    }

    private suspend fun refreshConnectivity(maxAgeMs: Long = 0) = refreshMutex.withLock {
        // A cold tile may arrive while startup discovery is already in flight.
        // Check freshness inside the lock to avoid a second identical network round trip.
        if (maxAgeMs > 0 && System.currentTimeMillis() - lastRefreshAt < maxAgeMs) return@withLock
        val key = accountKey()
        if (key != accountKey) {
            accountKey = key
            selectedDeviceId = null
            selectedRemoteSessionId = null
            _remoteSessions.value = emptyList()
            _relayPlaybackState.value = null
            _relayNextUp.value = null
            metadataJob?.cancel()
            metadataKey = null
            metadata = null
            lastNextUpAt = 0
        }
        val node = messageClientRepo.getConnectedHostNode()
        var discoveryError: Exception? = null
        val sessions =
            try {
                relayClient.listControllableSessions()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                discoveryError = error
                null
            }
        if (accountKey() != key) return@withLock
        if (sessions != null) _remoteSessions.value = sessions
        lastRefreshAt = System.currentTimeMillis()
        snapshotClock.value = lastRefreshAt
        val receiver = directLanClient.connectedReceiver.value
        if (receiver != null) {
            selectedLan = receiver
            targetAvailable = true
            _transportState.value =
                TransportState.ConnectedViaFCastLan(receiver.host, receiver.port, receiver.name)
            _connectionStatus.value =
                "Connected to cast receiver ${receiver.name}. Controls apply to cast playback."
            return@withLock
        }
        if (selectedLan != null) {
            targetAvailable = false
            _connectionStatus.value =
                "Cast receiver disconnected. Select it to reconnect or choose another target."
            return@withLock
        }
        // A selected device is never replaced because discovery failed or its session restarted.
        if (selectedDeviceId != null || selectedRemoteSessionId != null) {
            if (node != null && selectedDeviceId == credentialsStore.credentials.value?.deviceId) {
                targetAvailable = true
                _transportState.value =
                    TransportState.ConnectedViaDataLayer(node.id, node.displayName)
                _connectionStatus.value = "Connected to ${node.displayName}"
                return@withLock
            }
            val selected = sessions?.firstOrNull {
                if (selectedDeviceId != null) it.deviceId == selectedDeviceId
                else it.sessionId == selectedRemoteSessionId
            }
            if (selected != null) useRelaySession(selected)
            else {
                targetAvailable = false
                _connectionStatus.value =
                    discoveryError?.message
                        ?: "Selected player unavailable. Retry or choose another target."
            }
            return@withLock
        }
        if (node != null && dataClientRepo.nowPlayingState.value.hasFreshPlayback()) {
            targetAvailable = true
            _relayPlaybackState.value = null
            _transportState.value = TransportState.ConnectedViaDataLayer(node.id, node.displayName)
            _connectionStatus.value = "Connected to ${node.displayName}"
            return@withLock
        }
        val active = sessions?.firstOrNull { it.hasPlayback }
        if (active != null) {
            useRelaySession(active)
            return@withLock
        }
        _relayPlaybackState.value = null
        _transportState.value =
            if (node != null) TransportState.ConnectedViaDataLayer(node.id, node.displayName)
            else TransportState.Disconnected
        targetAvailable = node != null
        _connectionStatus.value =
            when {
                discoveryError != null ->
                    discoveryError.message ?: "Cannot reach Jellyfin. Check the watch connection."
                credentialsStore.credentials.value == null ->
                    "Sync your account from the paired phone. Nearby cast receivers need no account."
                else ->
                    "No active Jellyfin playback. Open Target to choose an idle player or cast receiver."
            }
    }

    private fun observeCapabilities() {
        Wearable.getCapabilityClient(context)
            .addListener(
                { checkConnectivity() },
                WearProtocolPaths.CAPABILITY_HOST,
            )
    }

    suspend fun dispatchAction(action: WearPlayerAction): Result<String> {
        if (
            _transportState.value == TransportState.Disconnected ||
                System.currentTimeMillis() - lastRefreshAt > 15_000
        )
            refreshConnectivity()
        val result =
            if (!targetAvailable) Result.failure(IllegalStateException(_connectionStatus.value))
            else
                when (val state = _transportState.value) {
                    is TransportState.ConnectedViaDataLayer ->
                        messageClientRepo.sendAction(action, state.nodeId)
                    is TransportState.ConnectedViaFCastLan -> directLanClient.dispatch(action)
                    is TransportState.ConnectedViaJellyfinRelay ->
                        relayClient.dispatch(state.sessionId, action, supportsExtendedControls())
                    TransportState.Disconnected ->
                        Result.failure(IllegalStateException("Choose a playback device in Target"))
                }
        showFeedback(result.getOrElse { it.message ?: "Command failed" })
        if (result.isSuccess) checkConnectivity()
        return result
    }

    suspend fun dispatchVoice(transcript: String): Result<String> {
        if (!targetAvailable) return Result.failure(IllegalStateException(_connectionStatus.value))
        return when (val state = _transportState.value) {
            is TransportState.ConnectedViaDataLayer ->
                messageClientRepo.sendVoice(transcript, state.nodeId)
            is TransportState.ConnectedViaJellyfinRelay ->
                if (supportsExtendedControls())
                    relayClient.dispatchVoice(state.sessionId, transcript)
                else
                    Result.failure(
                        IllegalStateException(
                            "Update SpatialFin on the selected device to use voice"
                        )
                    )
            else ->
                Result.failure(IllegalStateException("Voice is unavailable for this cast receiver"))
        }
    }
}

internal fun WearNowPlayingState?.hasFreshPlayback(
    now: Long = System.currentTimeMillis()
): Boolean =
    this != null &&
        timestampEpochMs > 0 &&
        now - timestampEpochMs in -5_000L..30_000L &&
        (isPlaying || title.isNotBlank() || !itemId.isNullOrBlank() || durationSeconds > 0)
