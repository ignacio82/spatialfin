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
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomProfileResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator
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
import timber.log.Timber

/**
 * Pure-HTTP calibration controller for `spatialfin-avr` rooms (Tier 1 REW / Cavern QuickEQ bundle
 * upload + Tier 2 Beam OTG UMIK-1 sweep capture, speaker chimes, SPL headroom check, manual
 * speaker coordinates, single-channel re-measure, and apply/revert).
 */
@HiltViewModel
class TheaterCalibrationViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val httpClient: SendspinAvrHttpClient,
    private val tokenStore: TheaterAvrTokenStore,
) : ViewModel() {

    data class UiState(
        val receiver: CastReceiver? = null,
        val isLoading: Boolean = false,
        val status: AvrStatusResponse? = null,
        val roomProfile: RoomProfileResponse? = null,
        val roomProfileEtag: String? = null,
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
        val activeCalibrationId: String? = null,
        val lastDspGroupDelayUs: Long? = null,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val errorCode: String? = null,
    )

    private val captureEngine = Umik1CaptureEngine(appContext)
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

    init {
        viewModelScope.launch {
            captureEngine.livePeakDbfs.collect { dbfs ->
                _uiState.update { it.copy(livePeakDbfs = dbfs) }
            }
        }
    }

    fun bindRoom(receiver: CastReceiver) {
        _uiState.update { it.copy(receiver = receiver, errorMessage = null, errorCode = null) }
        refreshUsbMicrophones()
        refreshRoomState()
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
            val statusRes = httpClient.getStatus(endpoint, token)
            val profileRes = httpClient.getRoomProfile(endpoint, token)
            _uiState.update { state ->
                var next = state.copy(isLoading = false)
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
                next
            }
        }
    }

    fun playSpeakerChime(role: String, durationMs: Int = 800) {
        val (endpoint, token) = activeEndpointAndToken() ?: return
        val gain = _uiState.value.selectedGainDbfs
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, errorCode = null) }
            val res = httpClient.playChime(
                endpoint = endpoint,
                bearerToken = token,
                request = CalibrationChimeRequest(
                    role = role,
                    signal = "pink_noise_burst",
                    gainDbfs = gain,
                    durationMs = durationMs.coerceIn(
                        SendspinAvrRequestValidator.MIN_CHIME_DURATION_MS,
                        SendspinAvrRequestValidator.MAX_CHIME_DURATION_MS,
                    ),
                ),
            )
            when (res) {
                is SendspinAvrHttpClient.HttpResult.Success ->
                    _uiState.update { it.copy(statusMessage = "Chime played on $role at ${gain.toInt()} dBFS.") }
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
        if (calUriRaw.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Select your UMIK-1 .txt calibration file first.") }
            return
        }
        val selectedMic = state.usbMicrophones
            .firstOrNull { it.id == state.selectedUsbMicId }
            ?.deviceInfo

        stopSplHeadroomMeter()
        sweepJob?.cancel()
        sweepJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    sweepInProgress = true,
                    sweepWarning = null,
                    errorMessage = null,
                    errorCode = null,
                    statusMessage = "Pre-opening USB microphone and starting sweep…",
                )
            }
            try {
                val calText = readTextFromUri(Uri.parse(calUriRaw))
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
            port = receiver.port,
            useTls = receiver.useTls,
        ) to stored.avrToken
    }

    private fun applyError(state: UiState, error: SendspinAvrHttpClient.HttpResult.Error): UiState {
        val guidance = when {
            error.httpStatus == 409 && error.errorCode == "version_conflict" ->
                "Room profile changed on server (409). Tap Refresh and retry."
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
    }
}
