package dev.spatialfin.fcast.session.calibration

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.cast.CastReceiver
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.AvrStatusResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationBundleResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationChannelResult
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationChimeRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepStartRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepUploadMetadata
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepUploadResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.DiscoveredSendspinSpeaker
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.LocalAudioOutputDevice
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.LocalOutputPairRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.LocalOutputProbeChimeRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.NoiseEndpointProvisionRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.PairedSpeakerEndpointInfo
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomProfileResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomSummary
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrJson
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SinkPskFileConfig
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SpeakerPairRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SpeakerProbeChimeRequest
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.UpdateRoomRequest
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.settings.domain.TheaterAvrTokenStore
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Pure-HTTP calibration controller for `spatialfin-avr` rooms (Tier 1 REW / Cavern QuickEQ bundle
 * upload + Tier 2 Beam OTG UMIK-1 sweep capture, acoustic lip-sync auto-calibration, speaker
 * chimes, SPL headroom check, manual speaker coordinates, single-channel re-measure, and
 * apply/revert).
 */
@HiltViewModel
class TheaterCalibrationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val httpClient: SendspinAvrHttpClient,
    private val tokenStore: TheaterAvrTokenStore,
    okHttpClient: OkHttpClient,
) : ViewModel() {

    data class UiState(
        val receiver: CastReceiver? = null,
        val rooms: List<RoomSummary> = emptyList(),
        val selectedRoomId: String? = null,
        val roomMutationBusy: Boolean = false,
        val isLoading: Boolean = false,
        val status: AvrStatusResponse? = null,
        val roomProfile: RoomProfileResponse? = null,
        val roomProfileEtag: String? = null,
        val pairedSpeakers: List<PairedSpeakerEndpointInfo> = emptyList(),
        val discoveredSpeakers: List<DiscoveredSendspinSpeaker> = emptyList(),
        val isScanningSpeakers: Boolean = false,
        val localOutputsSupported: Boolean = true,
        val localOutputs: List<LocalAudioOutputDevice> = emptyList(),
        val isScanningLocalOutputs: Boolean = false,
        val probingLocalDeviceId: String? = null,
        val lastProvisionedPskJson: String? = null,
        val speakerMutationBusy: Boolean = false,
        val probingSpeakerUrl: String? = null,
        val usbMicrophones: List<Umik1CaptureEngine.UsbMicDescriptor> = emptyList(),
        val selectedUsbMicId: Int? = null,
        val umikCalFileUri: String? = null,
        val umikCalFileName: String? = null,
        val umikMicSerial: String = "UMIK-1",
        val micAngleDeg: Int = 90,
        val selectedGainDbfs: Double = -24.0,
        val splMeterRunning: Boolean = false,
        val livePeakDbfs: Float = -96f,
        val sweepInProgress: Boolean = false,
        val activeSweepId: String? = null,
        val sweepWarning: String? = null,
        val latestSweepResult: CalibrationSweepUploadResponse? = null,
        val latestBundleResult: CalibrationBundleResponse? = null,
        val lipSyncRunning: Boolean = false,
        val lipSyncPreviewRunning: Boolean = false,
        val lipSyncFlashActive: Boolean = false,
        val lipSyncResult: TheaterLipSyncCalibrator.MeasurementResult? = null,
        val lipSyncPendingDelayMs: Long? = null,
        val activeCalibrationId: String? = null,
        val lastDspGroupDelayUs: Long? = null,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val errorCode: String? = null,
    )

    private val captureEngine = Umik1CaptureEngine(appContext)
    private val lipSyncOkHttpClient: OkHttpClient = okHttpClient
        .newBuilderWithSsrfGuard(
            policy = UrlTargetPolicy.LAN_ONLY,
            allowCrossOriginRedirects = false,
        )
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val lipSyncCalibrator = TheaterLipSyncCalibrator(appContext, lipSyncOkHttpClient)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        UiState(
            umikCalFileUri = prefs.getString(KEY_UMIK_CAL_URI, null),
            umikCalFileName = prefs.getString(KEY_UMIK_CAL_NAME, null),
            micAngleDeg = prefs.getInt(KEY_MIC_ANGLE_DEG, 90),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var splJob: Job? = null
    private var sweepJob: Job? = null
    private var lipSyncJob: Job? = null

    init {
        viewModelScope.launch {
            captureEngine.livePeakDbfs.collect { dbfs ->
                _uiState.update { it.copy(livePeakDbfs = dbfs) }
            }
        }
    }

    fun bindRoom(receiver: CastReceiver) {
        _uiState.update {
            it.copy(
                receiver = receiver,
                selectedRoomId = receiver.roomId,
                errorMessage = null,
                errorCode = null,
                lastProvisionedPskJson = null,
            )
        }
        refreshUsbMicrophones()
        refreshRoomState()
    }

    fun selectRoom(roomId: String) {
        if (_uiState.value.selectedRoomId == roomId) return
        _uiState.update {
            it.copy(
                selectedRoomId = roomId,
                latestSweepResult = null,
                latestBundleResult = null,
                lipSyncResult = null,
                lipSyncPendingDelayMs = null,
                lastProvisionedPskJson = null,
                statusMessage = null,
                errorMessage = null,
                errorCode = null,
            )
        }
        refreshRoomState()
    }

    fun createRoom(roomName: String, onSuccess: () -> Unit = {}) {
        val clean = roomName.trim()
        if (clean.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a room name (e.g. Living Room, Family Room).") }
            return
        }
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(roomMutationBusy = true, errorMessage = null, errorCode = null) }
            when (val res = httpClient.createRoom(endpoint, token, clean)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val created = res.value
                    _uiState.update {
                        it.copy(
                            roomMutationBusy = false,
                            selectedRoomId = created.roomId,
                            latestSweepResult = null,
                            lastProvisionedPskJson = null,
                            statusMessage = "Created room '${created.roomName}'. Assign speakers or HDMI outputs below.",
                        )
                    }
                    refreshRoomState()
                    onSuccess()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(roomMutationBusy = false), res)
                }
            }
        }
    }

    fun renameSelectedRoom(newName: String, onSuccess: () -> Unit = {}) {
        val clean = newName.trim()
        val roomId = _uiState.value.selectedRoomId
            ?: _uiState.value.rooms.firstOrNull { it.isDefault }?.roomId
            ?: return
        if (clean.isEmpty()) return
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(roomMutationBusy = true, errorMessage = null, errorCode = null) }
            when (val res = httpClient.updateRoom(endpoint, token, roomId, UpdateRoomRequest(roomName = clean))) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    _uiState.update {
                        it.copy(
                            roomMutationBusy = false,
                            statusMessage = "Renamed room to '${res.value.roomName}'.",
                        )
                    }
                    refreshRoomState()
                    onSuccess()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(roomMutationBusy = false), res)
                }
            }
        }
    }

    fun deleteSelectedRoom(onSuccess: () -> Unit = {}) {
        val roomId = _uiState.value.selectedRoomId ?: return
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(roomMutationBusy = true, errorMessage = null, errorCode = null) }
            when (val res = httpClient.deleteRoom(endpoint, token, roomId)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    _uiState.update {
                        it.copy(
                            roomMutationBusy = false,
                            selectedRoomId = res.value.activeRoomId,
                            latestSweepResult = null,
                            lastProvisionedPskJson = null,
                            statusMessage = "Deleted room.",
                        )
                    }
                    refreshRoomState()
                    onSuccess()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(roomMutationBusy = false), res)
                }
            }
        }
    }

    fun discoverLocalOutputs() {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanningLocalOutputs = true,
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Scanning local PC HDMI / ALSA audio outputs…",
                )
            }
            when (val res = httpClient.discoverLocalOutputs(endpoint, token)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val body = res.value
                    _uiState.update {
                        it.copy(
                            isScanningLocalOutputs = false,
                            localOutputsSupported = body.alsaSupported,
                            localOutputs = body.devices,
                            statusMessage = when {
                                !body.alsaSupported -> "Local ALSA outputs are disabled in this spatialfin-avr build."
                                body.devices.isEmpty() -> "No local ALSA playback devices found (check /dev/snd mount in Docker)."
                                else -> "Found ${body.devices.size} local HDMI / ALSA audio output(s)."
                            },
                        )
                    }
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(isScanningLocalOutputs = false), res)
                }
            }
        }
    }

    fun probeLocalOutput(deviceId: String, signal: String = "chime") {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val gain = _uiState.value.selectedGainDbfs
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    probingLocalDeviceId = deviceId,
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Sending identification chime to local output $deviceId…",
                )
            }
            val res = httpClient.probeLocalOutputChime(
                endpoint = endpoint,
                bearerToken = token,
                request = LocalOutputProbeChimeRequest(
                    deviceId = deviceId,
                    signal = signal,
                    gainDbfs = gain,
                    durationMs = 800,
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success -> _uiState.update {
                    it.copy(
                        probingLocalDeviceId = null,
                        statusMessage = "Identification chime played on local output $deviceId.",
                    )
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(probingLocalDeviceId = null), res)
                }
            }
        }
    }

    fun pairLocalOutput(
        deviceId: String,
        name: String?,
        roles: List<String>,
        fixedHwDelayUs: Long = 12_000L,
        onRoomsUpdated: () -> Unit = {},
    ) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val cleanRoles = roles.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (deviceId.isBlank() || cleanRoles.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(speakerMutationBusy = true, errorMessage = null, errorCode = null) }
            val res = httpClient.pairLocalOutput(
                endpoint = endpoint,
                bearerToken = token,
                request = LocalOutputPairRequest(
                    deviceId = deviceId.trim(),
                    name = name?.trim()?.takeIf { it.isNotEmpty() },
                    roles = cleanRoles,
                    fixedHwDelayUs = fixedHwDelayUs.coerceIn(0L, 250_000L),
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val prof = res.value.roomProfile
                    val paired = res.value.paired
                    _uiState.update {
                        it.copy(
                            speakerMutationBusy = false,
                            roomProfile = prof,
                            statusMessage = "Assigned ${paired.name} (${prof.layout}) to ${prof.roomName}.",
                        )
                    }
                    refreshRoomState()
                    discoverLocalOutputs()
                    onRoomsUpdated()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(speakerMutationBusy = false), res)
                }
            }
        }
    }

    fun provisionNoiseEndpoint(
        clientId: String,
        name: String?,
        roles: List<String>,
        transportTier: String = "wifi_esp32_noise",
        fixedHwDelayUs: Long = 14_000L,
        onRoomsUpdated: () -> Unit = {},
    ) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val cleanId = clientId.trim()
        val cleanRoles = roles.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (cleanId.isEmpty() || cleanRoles.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter an ESP32/sink client ID and select at least one role.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(speakerMutationBusy = true, errorMessage = null, errorCode = null) }
            val res = httpClient.provisionNoiseEndpoint(
                endpoint = endpoint,
                bearerToken = token,
                request = NoiseEndpointProvisionRequest(
                    clientId = cleanId,
                    name = name?.trim()?.takeIf { it.isNotEmpty() },
                    roles = cleanRoles,
                    transportTier = transportTier,
                    fixedHwDelayUs = fixedHwDelayUs.coerceIn(0L, 250_000L),
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val pskJson = SendspinAvrJson.codec.encodeToString(
                        SinkPskFileConfig.serializer(),
                        res.value.pskConfig,
                    )
                    _uiState.update {
                        it.copy(
                            speakerMutationBusy = false,
                            roomProfile = res.value.roomProfile,
                            lastProvisionedPskJson = pskJson,
                            statusMessage = "Provisioned ${res.value.paired.clientId} (${res.value.roomProfile.layout}). Copy PSK JSON to your ESP32 / spatialfin-sink.",
                        )
                    }
                    refreshRoomState()
                    onRoomsUpdated()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(speakerMutationBusy = false), res)
                }
            }
        }
    }

    fun refreshUsbMicrophones() {
        val mics = captureEngine.listUsbMicrophones()
        _uiState.update { state ->
            val selected = state.selectedUsbMicId
                ?.takeIf { id -> mics.any { it.id == id } }
                ?: mics.firstOrNull()?.id
            state.copy(usbMicrophones = mics, selectedUsbMicId = selected)
        }
    }

    fun selectUsbMicrophone(deviceId: Int) {
        _uiState.update { it.copy(selectedUsbMicId = deviceId) }
    }

    fun setMicAngleDeg(angleDeg: Int) {
        val sanitized = if (angleDeg == 0) 0 else 90
        prefs.edit().putInt(KEY_MIC_ANGLE_DEG, sanitized).apply()
        _uiState.update { it.copy(micAngleDeg = sanitized) }
    }

    fun setGainDbfs(gainDbfs: Double) {
        val clamped = gainDbfs.coerceIn(
            SendspinAvrRequestValidator.MIN_CHIME_GAIN_DBFS,
            SendspinAvrRequestValidator.MAX_CALIBRATION_GAIN_DBFS,
        )
        _uiState.update { it.copy(selectedGainDbfs = clamped) }
    }

    fun persistUmikCalibrationUri(uri: Uri, displayName: String?) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.tag(TAG).w(it, "Could not persist SAF URI permission") }
        val inferredSerial = displayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "UMIK-1"
        prefs.edit()
            .putString(KEY_UMIK_CAL_URI, uri.toString())
            .putString(KEY_UMIK_CAL_NAME, displayName ?: uri.lastPathSegment ?: "umik.txt")
            .apply()
        _uiState.update {
            it.copy(
                umikCalFileUri = uri.toString(),
                umikCalFileName = displayName ?: uri.lastPathSegment ?: "umik.txt",
                umikMicSerial = inferredSerial,
                statusMessage = "Loaded UMIK-1 calibration file.",
                errorMessage = null,
            )
        }
    }

    fun refreshRoomState() {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
            val roomsRes = httpClient.listRooms(endpoint, token)
            val effectiveRoomId = _uiState.value.selectedRoomId
                ?: (roomsRes as? SendspinAvrHttpClient.HttpResult.Success)?.value?.activeRoomId
            val scopedEndpoint = endpoint.copy(roomId = effectiveRoomId)
            val statusRes = httpClient.getStatus(scopedEndpoint, token)
            val profileRes = httpClient.getRoomProfile(scopedEndpoint, token)
            val speakersRes = httpClient.listSpeakers(scopedEndpoint, token)
            _uiState.update { state ->
                var next = state.copy(isLoading = false, selectedRoomId = effectiveRoomId)
                if (roomsRes is SendspinAvrHttpClient.HttpResult.Success) {
                    next = next.copy(rooms = roomsRes.value.rooms)
                }
                when (statusRes) {
                    is SendspinAvrHttpClient.HttpResult.Success -> next = next.copy(status = statusRes.value)
                    is SendspinAvrHttpClient.HttpResult.Error -> next = applyError(next, statusRes)
                }
                when (profileRes) {
                    is SendspinAvrHttpClient.HttpResult.Success -> next = next.copy(
                        roomProfile = profileRes.value,
                        roomProfileEtag = profileRes.etag,
                    )
                    is SendspinAvrHttpClient.HttpResult.Error -> next = applyError(next, profileRes)
                }
                if (speakersRes is SendspinAvrHttpClient.HttpResult.Success) {
                    next = next.copy(pairedSpeakers = speakersRes.value.paired)
                }
                next
            }
        }
    }

    fun discoverLanSpeakers() {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanningSpeakers = true,
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Scanning LAN for _sendspin._tcp speakers…",
                )
            }
            when (val res = httpClient.discoverSpeakers(endpoint, token)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val found = res.value.discovered
                    _uiState.update {
                        it.copy(
                            isScanningSpeakers = false,
                            discoveredSpeakers = found,
                            pairedSpeakers = res.value.paired,
                            statusMessage = if (found.isEmpty()) {
                                "No _sendspin._tcp speakers responded to mDNS scan. You can still pair by manual ws:// URL."
                            } else {
                                "Found ${found.size} Sendspin speaker(s) on the LAN."
                            },
                        )
                    }
                }
                is SendspinAvrHttpClient.HttpResult.Error -> {
                    _uiState.update { applyError(it.copy(isScanningSpeakers = false), res) }
                }
            }
        }
    }

    fun probeDiscoveredSpeaker(
        wsUrl: String,
        clientId: String? = null,
        signal: String = "chime",
    ) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val gain = _uiState.value.selectedGainDbfs
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    probingSpeakerUrl = wsUrl,
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Sending identification chime to ${clientId ?: wsUrl}…",
                )
            }
            val res = httpClient.probeSpeakerChime(
                endpoint = endpoint,
                bearerToken = token,
                request = SpeakerProbeChimeRequest(
                    wsUrl = wsUrl,
                    clientId = clientId,
                    signal = signal,
                    gainDbfs = gain,
                    durationMs = 800,
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success -> _uiState.update {
                    it.copy(
                        probingSpeakerUrl = null,
                        statusMessage = "Identification chime played on ${clientId ?: wsUrl}.",
                    )
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(probingSpeakerUrl = null), res)
                }
            }
        }
    }

    fun pairOrUpdateSpeaker(
        clientId: String,
        name: String?,
        wsUrl: String,
        roles: List<String>,
        fixedHwDelayUs: Long = 15_000L,
        onRoomsUpdated: () -> Unit = {},
    ) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val cleanId = clientId.trim()
        val cleanUrl = wsUrl.trim()
        val cleanRoles = roles.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (cleanId.isEmpty() || cleanUrl.isEmpty() || cleanRoles.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Speaker ID, WebSocket URL, and at least one role are required.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(speakerMutationBusy = true, errorMessage = null, errorCode = null) }
            val res = httpClient.pairSpeaker(
                endpoint = endpoint,
                bearerToken = token,
                request = SpeakerPairRequest(
                    clientId = cleanId,
                    name = name?.trim()?.takeIf { it.isNotEmpty() },
                    wsUrl = cleanUrl,
                    roles = cleanRoles,
                    fixedHwDelayUs = fixedHwDelayUs.coerceIn(0L, 250_000L),
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val updatedProfile = res.value.roomProfile
                    val pairedInfo = res.value.paired
                    val listRes = httpClient.listSpeakers(endpoint, token)
                    val statusRes = httpClient.getStatus(endpoint, token)
                    val roomsRes = httpClient.listRooms(endpoint, token)
                    _uiState.update { state ->
                        val nextPaired = (listRes as? SendspinAvrHttpClient.HttpResult.Success)?.value?.paired
                            ?: (state.pairedSpeakers.filterNot { it.clientId == pairedInfo.clientId } + pairedInfo)
                        val nextDiscovered = state.discoveredSpeakers.map { d ->
                            if (d.clientId.equals(pairedInfo.clientId, ignoreCase = true) ||
                                pairedInfo.wsUrl?.equals(d.wsUrl, ignoreCase = true) == true
                            ) {
                                d.copy(alreadyPaired = true, assignedRoles = pairedInfo.roles)
                            } else {
                                d
                            }
                        }
                        state.copy(
                            speakerMutationBusy = false,
                            rooms = (roomsRes as? SendspinAvrHttpClient.HttpResult.Success)?.value?.rooms ?: state.rooms,
                            roomProfile = updatedProfile,
                            status = (statusRes as? SendspinAvrHttpClient.HttpResult.Success)?.value ?: state.status,
                            pairedSpeakers = nextPaired,
                            discoveredSpeakers = nextDiscovered,
                            statusMessage = "Paired ${pairedInfo.name} as ${pairedInfo.roles.joinToString("/")} (layout ${updatedProfile.layout}).",
                        )
                    }
                    onRoomsUpdated()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(speakerMutationBusy = false), res)
                }
            }
        }
    }

    fun unpairSpeaker(clientId: String, onRoomsUpdated: () -> Unit = {}) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(speakerMutationBusy = true, errorMessage = null, errorCode = null) }
            when (val res = httpClient.unpairSpeaker(endpoint, token, clientId)) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    val updatedProfile = res.value.roomProfile
                    val listRes = httpClient.listSpeakers(endpoint, token)
                    val statusRes = httpClient.getStatus(endpoint, token)
                    val roomsRes = httpClient.listRooms(endpoint, token)
                    _uiState.update { state ->
                        val nextPaired = (listRes as? SendspinAvrHttpClient.HttpResult.Success)?.value?.paired
                            ?: state.pairedSpeakers.filterNot { it.clientId == clientId }
                        val nextDiscovered = state.discoveredSpeakers.map { d ->
                            if (d.clientId.equals(clientId, ignoreCase = true)) {
                                d.copy(alreadyPaired = false, assignedRoles = emptyList())
                            } else {
                                d
                            }
                        }
                        state.copy(
                            speakerMutationBusy = false,
                            rooms = (roomsRes as? SendspinAvrHttpClient.HttpResult.Success)?.value?.rooms ?: state.rooms,
                            roomProfile = updatedProfile,
                            status = (statusRes as? SendspinAvrHttpClient.HttpResult.Success)?.value ?: state.status,
                            pairedSpeakers = nextPaired,
                            discoveredSpeakers = nextDiscovered,
                            statusMessage = "Removed output $clientId (layout ${updatedProfile.layout}).",
                        )
                    }
                    onRoomsUpdated()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(speakerMutationBusy = false), res)
                }
            }
        }
    }

    fun playSpeakerChime(
        role: String,
        durationMs: Int = 800,
        signal: String = "chime",
    ) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val gain = _uiState.value.selectedGainDbfs
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, errorCode = null) }
            val res = httpClient.playChime(
                endpoint = endpoint,
                bearerToken = token,
                request = CalibrationChimeRequest(
                    role = role,
                    signal = signal,
                    gainDbfs = gain,
                    durationMs = durationMs.coerceIn(
                        SendspinAvrRequestValidator.MIN_CHIME_DURATION_MS,
                        SendspinAvrRequestValidator.MAX_CHIME_DURATION_MS,
                    ),
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success ->
                    _uiState.update {
                        val label = if (signal.startsWith("pink_noise")) "Pink noise" else "Chime"
                        it.copy(statusMessage = "$label played on $role at ${gain.toInt()} dBFS.")
                    }
                is SendspinAvrHttpClient.HttpResult.Error ->
                    _uiState.update { applyError(it, res) }
            }
        }
    }

    fun toggleSplHeadroomMeter() {
        if (_uiState.value.splMeterRunning) {
            stopSplHeadroomMeter()
            return
        }
        val selectedMic = _uiState.value.usbMicrophones
            .firstOrNull { it.id == _uiState.value.selectedUsbMicId }
            ?.deviceInfo
        splJob?.cancel()
        _uiState.update { it.copy(splMeterRunning = true, errorMessage = null) }
        splJob = viewModelScope.launch {
            try {
                captureEngine.monitorLiveHeadroom(preferredDevice = selectedMic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "SPL meter stopped.") }
            } finally {
                _uiState.update { it.copy(splMeterRunning = false) }
            }
        }
    }

    fun stopSplHeadroomMeter() {
        splJob?.cancel()
        splJob = null
        _uiState.update { it.copy(splMeterRunning = false) }
    }

    fun updateSpeakerCoordinate(role: String, xMeters: Double, yMeters: Double, zMeters: Double) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val profile = _uiState.value.roomProfile ?: return
        val updated = profile.copy(
            speakers = profile.speakers.map { sp ->
                if (sp.role == role) sp.copy(coordM = listOf(xMeters, yMeters, zMeters)) else sp
            },
        )
        viewModelScope.launch {
            val res = httpClient.putRoomProfile(
                endpoint = endpoint,
                bearerToken = token,
                ifMatch = _uiState.value.roomProfileEtag,
                profile = updated,
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success -> _uiState.update {
                    it.copy(
                        roomProfile = res.value,
                        roomProfileEtag = res.etag,
                        statusMessage = "Saved speaker coordinates for $role.",
                        errorMessage = null,
                    )
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update { applyError(it, res) }
            }
        }
    }

    /**
     * Runs a full Tier 2 UMIK-1 sweep (all configured roles) or a single-channel re-measure when
     * [rolesOverride] + [existingCalibrationId] are provided.
     */
    fun runTier2Sweep(
        rolesOverride: List<String>? = null,
        existingCalibrationId: String? = null,
    ) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val state = _uiState.value
        val profile = state.roomProfile ?: run {
            _uiState.update { it.copy(errorMessage = "Load room profile before starting a sweep.") }
            return
        }
        val roles = rolesOverride?.takeIf { it.isNotEmpty() }
            ?: profile.speakers.map { it.role }.distinct()
        if (roles.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No speakers configured in room profile.") }
            return
        }
        val calUriRaw = state.umikCalFileUri
        val selectedMic = state.usbMicrophones
            .firstOrNull { it.id == state.selectedUsbMicId }
            ?.deviceInfo

        stopSplHeadroomMeter()
        sweepJob?.cancel()
        sweepJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    sweepInProgress = true,
                    sweepWarning = if (calUriRaw.isNullOrBlank()) {
                        "No UMIK-1 .txt file selected — measuring with uncalibrated microphone."
                    } else {
                        null
                    },
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Pre-opening microphone and starting sweep…",
                )
            }
            try {
                val calText = if (!calUriRaw.isNullOrBlank()) readTextFromUri(Uri.parse(calUriRaw)) else null
                val uploadRes = captureEngine.recordAndProcessSweep(
                    preferredDevice = selectedMic,
                    startServerSweep = {
                        val startRes = httpClient.startSweep(
                            endpoint = endpoint,
                            bearerToken = token,
                            request = CalibrationSweepStartRequest(
                                roles = roles,
                                refRole = profile.speakers.firstOrNull { it.role == "FL" }?.role ?: roles.first(),
                                sweepDurationS = 3.0,
                                gainDbfs = state.selectedGainDbfs,
                                sampleRateHz = Umik1CaptureEngine.DEFAULT_SAMPLE_RATE_HZ,
                                calibrationId = existingCalibrationId,
                            ),
                        )
                        when (startRes) {
                            is SendspinAvrHttpClient.HttpResult.Success -> {
                                _uiState.update {
                                    it.copy(
                                        activeSweepId = startRes.value.sweepId,
                                        statusMessage = "Recording ${roles.size} channel(s)… keep room quiet.",
                                    )
                                }
                                startRes.value
                            }
                            is SendspinAvrHttpClient.HttpResult.Error -> {
                                throw HttpErrorException(startRes)
                            }
                        }
                    },
                    onAbortSweep = { sweepId ->
                        httpClient.abortSweep(endpoint, token, sweepId)
                    },
                    onCapturedWavReady = { sweepResp, outcome ->
                        if (!outcome.usedUnprocessedSource) {
                            _uiState.update {
                                it.copy(
                                    sweepWarning = "Device lacks UNPROCESSED mic source; OEM DSP may affect phase.",
                                )
                            }
                        }
                        _uiState.update { it.copy(statusMessage = "Uploading 24-bit WAV sweep…") }
                        httpClient.uploadSweep(
                            endpoint = endpoint,
                            bearerToken = token,
                            sweepId = sweepResp.sweepId,
                            recordingWavFile = outcome.wavFile,
                            umikCalText = calText,
                            metadata = CalibrationSweepUploadMetadata(
                                micAngleDeg = state.micAngleDeg,
                                micSerial = state.umikMicSerial,
                                capturePlatform = "android_beam_otg",
                            ),
                        )
                    },
                )
                when (uploadRes) {
                    is SendspinAvrHttpClient.HttpResult.Success -> {
                        val body = uploadRes.value
                        _uiState.update {
                            it.copy(
                                sweepInProgress = false,
                                activeSweepId = null,
                                latestSweepResult = body,
                                lastDspGroupDelayUs = body.dspGroupDelayUs,
                                statusMessage = if (body.readyToApply) {
                                    "Calibration staged (${body.calibrationId}). Ready to apply."
                                } else {
                                    "Sweep uploaded — check channels flagged for re-measure."
                                },
                            )
                        }
                    }
                    is SendspinAvrHttpClient.HttpResult.Error -> {
                        _uiState.update { applyError(it.copy(sweepInProgress = false, activeSweepId = null), uploadRes) }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(sweepInProgress = false, activeSweepId = null, statusMessage = "Sweep cancelled.") }
            } catch (e: HttpErrorException) {
                _uiState.update { applyError(it.copy(sweepInProgress = false, activeSweepId = null), e.error) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        sweepInProgress = false,
                        activeSweepId = null,
                        errorMessage = e.message ?: "Sweep capture failed.",
                    )
                }
            }
        }
    }

    fun cancelActiveSweep() {
        sweepJob?.cancel()
        sweepJob = null
    }

    fun runLipSyncAutoCalibration() {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val profile = _uiState.value.roomProfile ?: run {
            _uiState.update { it.copy(errorMessage = "Load room profile before calibrating lip-sync.") }
            return
        }
        stopSplHeadroomMeter()
        lipSyncJob?.cancel()
        lipSyncJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lipSyncRunning = true,
                    lipSyncPreviewRunning = false,
                    lipSyncFlashActive = false,
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Starting acoustic lip-sync auto-calibration…",
                )
            }
            val result = lipSyncCalibrator.measureRoomHwDelay(
                endpoint = endpoint,
                bearerToken = token,
                clientId = tokenStore.getOrCreateClientId(),
                roomProfile = profile,
                gainDbfs = _uiState.value.selectedGainDbfs,
                onStatus = { msg -> _uiState.update { it.copy(statusMessage = msg) } },
            )
            result.onSuccess { measured ->
                val delayMs = (measured.medianHwDelayUs / 1_000L).coerceIn(0L, 500L)
                _uiState.update {
                    it.copy(
                        lipSyncRunning = false,
                        lipSyncResult = measured,
                        lipSyncPendingDelayMs = delayMs,
                        statusMessage = "Measured ${delayMs} ms pure audio hardware delay (${measured.validPulseCount}/5 chirps, IQR ${String.format(java.util.Locale.US, "%.1f", measured.iqrUs / 1_000.0)} ms).",
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) {
                    _uiState.update {
                        it.copy(lipSyncRunning = false, statusMessage = "Lip-sync calibration cancelled.")
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            lipSyncRunning = false,
                            errorMessage = err.message ?: "Lip-sync calibration failed.",
                        )
                    }
                }
            }
        }
    }

    fun cancelLipSyncCalibration() {
        lipSyncJob?.cancel()
        lipSyncJob = null
        _uiState.update {
            it.copy(
                lipSyncRunning = false,
                lipSyncPreviewRunning = false,
                lipSyncFlashActive = false,
            )
        }
    }

    fun nudgeLipSyncDelayMs(deltaMs: Long) {
        _uiState.update { state ->
            val current = state.lipSyncPendingDelayMs
                ?: state.lipSyncResult?.let { it.medianHwDelayUs / 1_000L }
                ?: return@update state
            state.copy(lipSyncPendingDelayMs = (current + deltaMs).coerceIn(0L, 500L))
        }
    }

    fun playLipSyncConfirmationPreview() {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val profile = _uiState.value.roomProfile ?: return
        lipSyncJob?.cancel()
        lipSyncJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    lipSyncPreviewRunning = true,
                    lipSyncFlashActive = false,
                    errorMessage = null,
                    statusMessage = "Playing 3 confirmation flash + chirp pulses…",
                )
            }
            val res = lipSyncCalibrator.playConfirmationPreview(
                endpoint = endpoint,
                bearerToken = token,
                clientId = tokenStore.getOrCreateClientId(),
                roomProfile = profile,
                gainDbfs = _uiState.value.selectedGainDbfs,
                onFlashPulse = { lit -> _uiState.update { it.copy(lipSyncFlashActive = lit) } },
            )
            res.onSuccess {
                _uiState.update {
                    it.copy(
                        lipSyncPreviewRunning = false,
                        lipSyncFlashActive = false,
                        statusMessage = "Confirmation preview finished. Adjust ±5 ms if needed and save.",
                    )
                }
            }.onFailure { err ->
                if (err !is CancellationException) {
                    _uiState.update {
                        it.copy(
                            lipSyncPreviewRunning = false,
                            lipSyncFlashActive = false,
                            errorMessage = err.message ?: "Confirmation preview failed.",
                        )
                    }
                }
            }
        }
    }

    fun saveLipSyncDelayToProfile(onSaved: () -> Unit = {}) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val profile = _uiState.value.roomProfile ?: return
        val pendingMs = _uiState.value.lipSyncPendingDelayMs ?: return
        val delayUs = (pendingMs * 1_000L).coerceIn(
            TheaterLipSyncCalibrator.MIN_HW_DELAY_US,
            TheaterLipSyncCalibrator.MAX_HW_DELAY_US,
        )
        val updated = profile.copy(
            speakers = profile.speakers.map { sp -> sp.copy(fixedHwDelayUs = delayUs) },
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
            when (
                val res = httpClient.putRoomProfile(
                    endpoint = endpoint,
                    bearerToken = token,
                    ifMatch = _uiState.value.roomProfileEtag,
                    profile = updated,
                )
            ) {
                is SendspinAvrHttpClient.HttpResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            roomProfile = res.value,
                            roomProfileEtag = res.etag,
                            statusMessage = "Saved ${pendingMs} ms hardware delay to ${res.value.roomName}.",
                        )
                    }
                    onSaved()
                }
                is SendspinAvrHttpClient.HttpResult.Error -> _uiState.update {
                    applyError(it.copy(isLoading = false), res)
                }
            }
        }
    }

    /** Tier 1 REW / Cavern QuickEQ CamillaDSP bundle upload (`POST /api/v1/calibration/bundles`). */
    fun uploadTier1Bundle(configYamlUri: Uri, firWavUris: List<Uri> = emptyList()) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
            val tempFirFiles = mutableListOf<File>()
            try {
                val yamlText = readTextFromUri(configYamlUri)
                firWavUris.forEachIndexed { idx, uri ->
                    val temp = copyUriToTempFile(uri, "tier1_fir_$idx.wav")
                    tempFirFiles += temp
                }
                when (val res = httpClient.uploadBundle(endpoint, token, yamlText, tempFirFiles)) {
                    is SendspinAvrHttpClient.HttpResult.Success -> _uiState.update {
                        it.copy(
                            isLoading = false,
                            latestBundleResult = res.value,
                            lastDspGroupDelayUs = res.value.dspGroupDelayUs,
                            statusMessage = "Tier 1 CamillaDSP bundle staged (${res.value.calibrationId}).",
                        )
                    }
                    is SendspinAvrHttpClient.HttpResult.Error ->
                        _uiState.update { applyError(it.copy(isLoading = false), res) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message ?: "Bundle upload failed.")
                }
            } finally {
                tempFirFiles.forEach { runCatching { it.delete() } }
            }
        }
    }

    fun applyCalibration(calibrationId: String) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
            when (val res = httpClient.applyCalibration(endpoint, token, calibrationId)) {
                is SendspinAvrHttpClient.HttpResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        activeCalibrationId = res.value.activeCalibrationId,
                        lastDspGroupDelayUs = res.value.dspGroupDelayUs,
                        statusMessage = "Active calibration updated (${res.value.activeCalibrationId}).",
                    )
                }
                is SendspinAvrHttpClient.HttpResult.Error ->
                    _uiState.update { applyError(it.copy(isLoading = false), res) }
            }
        }
    }

    fun revertCalibration() {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, errorCode = null) }
            when (val res = httpClient.revertCalibration(endpoint, token)) {
                is SendspinAvrHttpClient.HttpResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        activeCalibrationId = res.value.activeCalibrationId,
                        lastDspGroupDelayUs = res.value.dspGroupDelayUs,
                        statusMessage = "Reverted calibration to ${res.value.activeCalibrationId}.",
                    )
                }
                is SendspinAvrHttpClient.HttpResult.Error ->
                    _uiState.update { applyError(it.copy(isLoading = false), res) }
            }
        }
    }

    private fun activeEndpointAndToken(): Pair<SendspinAvrHttpClient.Endpoint, String>? {
        val receiver = _uiState.value.receiver ?: return null
        val stored = tokenStore.getToken(receiver.id)
        if (stored == null) {
            _uiState.update {
                it.copy(
                    errorCode = "auth_invalid",
                    errorMessage = "Room token missing or revoked — please re-pair this room.",
                )
            }
            return null
        }
        return SendspinAvrHttpClient.Endpoint(
            host = receiver.host,
            port = if (stored.requireTls && !receiver.useTls) stored.port else receiver.port,
            useTls = receiver.useTls || stored.requireTls,
            roomId = _uiState.value.selectedRoomId ?: receiver.roomId,
            tlsSpkiSha256 = stored.tlsSpkiSha256,
        ) to stored.avrToken
    }

    private fun applyError(state: UiState, error: SendspinAvrHttpClient.HttpResult.Error): UiState {
        val guidance = when {
            error.httpStatus == 409 && error.errorCode == "speaker_busy" ->
                "Audio output is currently in use by another playing room (409). Stop that room first."
            (error.httpStatus == 409 || error.httpStatus == 412) && error.errorCode == "version_conflict" ->
                "Room profile changed on server (${error.httpStatus}). Tap Refresh and retry."
            error.httpStatus == 409 && error.errorCode == "calibration_in_progress" ->
                "Another calibration sweep is already running on this room."
            error.httpStatus == 413 || error.errorCode == "payload_too_large" ->
                "Upload exceeded the 64 MiB limit — reduce sweep duration or channels."
            error.httpStatus == 422 && error.errorCode == "calibration_clipped" ->
                "Microphone clipped (422) — lower gain preset and re-measure."
            error.httpStatus == 422 && error.errorCode == "calibration_low_snr" ->
                "Low SNR (422) — raise gain preset or quiet room noise and re-measure."
            error.httpStatus == 422 && error.errorCode == "calibration_delay_exceeded" ->
                "Filter group delay exceeded safe limit (422)."
            else -> error.message.ifBlank { "Calibration request failed (HTTP ${error.httpStatus})." }
        }
        return state.copy(errorMessage = guidance, errorCode = error.errorCode)
    }

    private suspend fun readTextFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("Unable to open selected file")
    }

    private suspend fun copyUriToTempFile(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val outFile = File(appContext.cacheDir, name)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Unable to read FIR WAV file")
        outFile
    }

    override fun onCleared() {
        stopSplHeadroomMeter()
        cancelActiveSweep()
        cancelLipSyncCalibration()
        super.onCleared()
    }

    private class HttpErrorException(val error: SendspinAvrHttpClient.HttpResult.Error) :
        Exception(error.message)

    companion object {
        private const val TAG = "TheaterCalibrationVM"
        private const val PREFS_NAME = "spatialfin_theater_calibration"
        private const val KEY_UMIK_CAL_URI = "umik_cal_uri"
        private const val KEY_UMIK_CAL_NAME = "umik_cal_name"
        private const val KEY_MIC_ANGLE_DEG = "mic_angle_deg"
        val GAIN_PRESETS_DBFS: List<Double> = listOf(-30.0, -24.0, -18.0, -12.0)
        val AVAILABLE_SPEAKER_ROLES: List<String> = listOf(
            "MONO", "FL", "FR", "FC", "LFE1", "SL", "SR", "BL", "BR", "TFL", "TFR", "STEREO_L", "STEREO_R",
        )
        val LOCAL_LAYOUT_PRESETS: List<Pair<String, List<String>>> = listOf(
            "2.0 Stereo" to listOf("FL", "FR"),
            "3.1 LCR+Sub" to listOf("FL", "FR", "FC", "LFE1"),
            "5.1 Surround" to listOf("FL", "FR", "FC", "LFE1", "SL", "SR"),
            "7.1 Surround" to listOf("FL", "FR", "FC", "LFE1", "SL", "SR", "BL", "BR"),
        )
    }
}
