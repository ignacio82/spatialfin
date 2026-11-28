package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Shared kotlinx.serialization JSON configuration for Theater Audio v1 messages. */
object SendspinAvrJson {
    @OptIn(ExperimentalSerializationApi::class)
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Canonical wire constants for Spec 0 (`theater-audio-protocol-v1`). */
object TheaterAudioProtocol {
    const val VERSION: Int = 1
    const val MAX_WS_FRAME_BYTES: Int = 256 * 1024
    val SERVER_ID_REGEX: Regex = Regex("^avr-[0-9a-f-]{8,64}$")
    const val VALID_SOURCE_TYPE: String = "jellyfin"
    val VALID_STREAM_LAYOUTS: Set<String> = setOf("multi_stream_container", "single_audio_stream")

    val ERROR_CODES: Set<String> = setOf(
        "auth_required",
        "auth_invalid",
        "pairing_disabled",
        "pin_expired",
        "rate_limited",
        "protocol_version_unsupported",
        "invalid_request",
        "url_rejected",
        "source_ineligible",
        "session_busy",
        "session_not_found",
        "version_conflict",
        "calibration_in_progress",
        "sweep_not_found",
        "jellyfin_auth_failed",
        "jellyfin_unreachable",
        "codec_unsupported",
        "seek_timeout",
        "speaker_offline_critical",
        "calibration_clipped",
        "calibration_low_snr",
        "calibration_delay_exceeded",
        "payload_too_large",
        "internal_error",
    )
}

/** WebSocket frame envelope carrying `v`, `type`, optional `id`/`session_id`/`ts_us`, and `payload`. */
@Serializable
data class SendspinAvrEnvelope<T>(
    val v: Int = TheaterAudioProtocol.VERSION,
    val type: String,
    val id: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("ts_us") val tsUs: Long? = null,
    val payload: T,
)

@Serializable
data class HelloRequestPayload(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("form_factor") val formFactor: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("min_protocol_version") val minProtocolVersion: Int,
    @SerialName("max_protocol_version") val maxProtocolVersion: Int,
)

@Serializable
data class AvrCapabilities(
    @SerialName("truehd_atmos_vbap") val truehdAtmosVbap: Boolean = false,
    @SerialName("dts_height_upmix") val dtsHeightUpmix: Boolean = false,
)

@Serializable
data class HelloOkPayload(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("server_id") val serverId: String,
    @SerialName("server_name") val serverName: String,
    @SerialName("software_version") val softwareVersion: String,
    val layout: String,
    @SerialName("bus_sample_rate_hz") val busSampleRateHz: Int,
    @SerialName("sendspin_buffer_us") val sendspinBufferUs: Long,
    @SerialName("max_safe_volume") val maxSafeVolume: Double,
    @SerialName("heartbeat_ttl_ms") val heartbeatTtlMs: Long,
    @SerialName("pause_idle_release_ms") val pauseIdleReleaseMs: Long,
    @SerialName("video_timing_cadence_hz") val videoTimingCadenceHz: Int,
    @SerialName("supported_audio_codecs") val supportedAudioCodecs: List<String>,
    val capabilities: AvrCapabilities,
)

@Serializable
data class ClockPingPayload(
    @SerialName("t1_client_us") val t1ClientUs: Long,
)

@Serializable
data class ClockPingOkPayload(
    @SerialName("t1_client_us") val t1ClientUs: Long,
    @SerialName("t2_avr_rx_us") val t2AvrRxUs: Long,
    @SerialName("t3_avr_tx_us") val t3AvrTxUs: Long,
)

@Serializable
data class SessionStartPayload(
    @SerialName("source_type") val sourceType: String,
    @SerialName("stream_url") val streamUrl: String,
    @SerialName("stream_layout") val streamLayout: String,
    @SerialName("media_source_id") val mediaSourceId: String,
    @SerialName("audio_stream_index") val audioStreamIndex: Int,
    @SerialName("source_codec") val sourceCodec: String,
    @SerialName("target_pts_us") val targetPtsUs: Long,
    @SerialName("jellyfin_token") val jellyfinToken: String,
    @SerialName("force_takeover") val forceTakeover: Boolean = false,
)

@Serializable
data class SessionStartOkPayload(
    val accepted: Boolean,
    @SerialName("barrier_seq") val barrierSeq: Long,
)

@Serializable
data class SessionHeartbeatPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    val state: String,
)

@Serializable
data class SessionStopPayload(
    val reason: String,
)

@Serializable
data class SessionStopOkPayload(
    val stopped: Boolean,
)

@Serializable
data class SessionReleasedPayload(
    val reason: String,
)

@Serializable
data class PlaybackLoadPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    @SerialName("audio_stream_index") val audioStreamIndex: Int,
    @SerialName("target_pts_us") val targetPtsUs: Long,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("stream_layout") val streamLayout: String? = null,
    @SerialName("media_source_id") val mediaSourceId: String? = null,
    @SerialName("source_codec") val sourceCodec: String? = null,
    @SerialName("jellyfin_token") val jellyfinToken: String? = null,
)

@Serializable
data class PlaybackLoadOkPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
)

@Serializable
data class PlaybackReadyPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    @SerialName("actual_pts_us") val actualPtsUs: Long,
)

@Serializable
data class LeadBreakdownUs(
    @SerialName("decode_prime_us") val decodePrimeUs: Long,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
    @SerialName("max_air_flight_us") val maxAirFlightUs: Long,
    @SerialName("sendspin_buffer_us") val sendspinBufferUs: Long,
    @SerialName("max_uncompensated_hw_us") val maxUncompensatedHwUs: Long,
    @SerialName("launch_margin_us") val launchMarginUs: Long,
)

@Serializable
data class PlaybackReadyOkPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    @SerialName("actual_pts_us") val actualPtsUs: Long,
    @SerialName("t_play_avr_us") val tPlayAvrUs: Long,
    @SerialName("lead_breakdown_us") val leadBreakdownUs: LeadBreakdownUs,
    @SerialName("codec_active") val codecActive: String,
    @SerialName("renderer_mode") val rendererMode: String,
    @SerialName("active_decode_chain") val activeDecodeChain: String,
)

@Serializable
data class PlaybackPausePayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    val flush: Boolean,
)

@Serializable
data class PlaybackPauseOkPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    val flushed: Boolean,
)

@Serializable
data class PlaybackStallPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
)

@Serializable
data class PlaybackVideoTimingPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    @SerialName("media_pts_us") val mediaPtsUs: Long,
    @SerialName("visible_avr_us") val visibleAvrUs: Long,
    @SerialName("clock_uncertainty_us") val clockUncertaintyUs: Long,
)

@Serializable
data class PlaybackResyncRequiredPayload(
    @SerialName("barrier_seq") val barrierSeq: Long,
    @SerialName("drift_us") val driftUs: Long,
)

@Serializable
data class RoomSetVolumePayload(
    val volume: Double,
    val muted: Boolean,
)

@Serializable
data class RoomSetVolumeOkPayload(
    @SerialName("master_volume") val masterVolume: Double,
    val muted: Boolean,
)

@Serializable
data class RoomSetEqBypassPayload(
    val bypass: Boolean,
)

@Serializable
data class RoomSetEqBypassOkPayload(
    @SerialName("eq_bypass") val eqBypass: Boolean,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
)

@Serializable
data class RoomSetUpmixModePayload(
    val mode: String,
)

@Serializable
data class RoomSetUpmixModeOkPayload(
    @SerialName("upmix_mode") val upmixMode: String,
)

@Serializable
data class RoomStateChangedPayload(
    @SerialName("master_volume") val masterVolume: Double,
    val muted: Boolean,
    @SerialName("eq_bypass") val eqBypass: Boolean,
    @SerialName("upmix_mode") val upmixMode: String,
)

@Serializable
data class LcrScreenPlane(
    @SerialName("center_m") val centerM: List<Double>,
    @SerialName("width_m") val widthM: Double,
    @SerialName("height_m") val heightM: Double,
    @SerialName("normal_vec") val normalVec: List<Double>,
)

@Serializable
data class RoomSpeakerConfig(
    val role: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("channel_index") val channelIndex: Int,
    @SerialName("transport_tier") val transportTier: String,
    @SerialName("coord_m") val coordM: List<Double>,
    @SerialName("fixed_hw_delay_us") val fixedHwDelayUs: Long,
    @SerialName("room_geom_delay_us") val roomGeomDelayUs: Long,
    @SerialName("trim_db") val trimDb: Double,
    @SerialName("polarity_invert") val polarityInvert: Boolean,
    @SerialName("crossover_hz") val crossoverHz: Int,
    @SerialName("crossover_slope_db_oct") val crossoverSlopeDbOct: Int,
)

@Serializable
data class RoomProfileResponse(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("room_profile_version") val roomProfileVersion: Int,
    @SerialName("room_name") val roomName: String,
    val layout: String,
    @SerialName("lcr_screen_plane") val lcrScreenPlane: LcrScreenPlane? = null,
    val speakers: List<RoomSpeakerConfig>,
)

@Serializable
data class RoomProfileUpdatedPayload(
    @SerialName("room_profile_version") val roomProfileVersion: Int,
    val layout: String,
)

@Serializable
data class CalibrationChimeRequest(
    val role: String,
    val signal: String,
    @SerialName("gain_dbfs") val gainDbfs: Double,
    @SerialName("duration_ms") val durationMs: Int,
)

@Serializable
data class CalibrationCenterSnapPayload(
    @SerialName("duration_ms") val durationMs: Int,
    @SerialName("gain_dbfs") val gainDbfs: Double,
)

@Serializable
data class CalibrationCenterSnapOkPayload(
    @SerialName("scheduled_avr_us") val scheduledAvrUs: Long,
)

@Serializable
data class CalibrationLipSyncChirpPayload(
    val roles: List<String>,
    val mode: String,
    @SerialName("start_avr_us") val startAvrUs: Long,
    @SerialName("repeat_count") val repeatCount: Int,
    @SerialName("interval_ms") val intervalMs: Int,
    @SerialName("gain_dbfs") val gainDbfs: Double,
    @SerialName("bypass_hw_delay") val bypassHwDelay: Boolean = false,
    @SerialName("lead_in_ms") val leadInMs: Int? = null,
)

@Serializable
data class CalibrationLipSyncChirpOkPayload(
    @SerialName("scheduled_avr_us") val scheduledAvrUs: List<Long>,
)

@Serializable
data class CalibrationSweepStartRequest(
    val roles: List<String>,
    @SerialName("ref_role") val refRole: String,
    @SerialName("sweep_duration_s") val sweepDurationS: Double,
    @SerialName("gain_dbfs") val gainDbfs: Double,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
    @SerialName("calibration_id") val calibrationId: String? = null,
)

@Serializable
data class CalibrationSweepChannelSchedule(
    val role: String,
    @SerialName("start_avr_us") val startAvrUs: Long,
    @SerialName("end_avr_us") val endAvrUs: Long,
)

@Serializable
data class CalibrationSweepStartResponse(
    @SerialName("sweep_id") val sweepId: String,
    @SerialName("calibration_id") val calibrationId: String,
    @SerialName("t0_ref_avr_us") val t0RefAvrUs: Long,
    @SerialName("channel_schedule") val channelSchedule: List<CalibrationSweepChannelSchedule>,
    @SerialName("tend_ref_avr_us") val tendRefAvrUs: Long,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
)

@Serializable
data class CalibrationSweepAbortResponse(
    val aborted: Boolean,
)

@Serializable
data class CalibrationSweepUploadMetadata(
    @SerialName("mic_angle_deg") val micAngleDeg: Int,
    @SerialName("mic_serial") val micSerial: String,
    @SerialName("capture_platform") val capturePlatform: String,
)

@Serializable
data class CalibrationChannelResult(
    val role: String,
    @SerialName("snr_db") val snrDb: Double,
    val clipped: Boolean,
    @SerialName("total_delay_us") val totalDelayUs: Long,
    @SerialName("polarity_invert") val polarityInvert: Boolean,
    @SerialName("trim_db") val trimDb: Double,
    @SerialName("needs_remeasure") val needsRemeasure: Boolean,
)

@Serializable
data class CalibrationSweepUploadResponse(
    @SerialName("calibration_id") val calibrationId: String,
    @SerialName("ready_to_apply") val readyToApply: Boolean,
    @SerialName("clock_ratio_ppm") val clockRatioPpm: Double,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
    val channels: List<CalibrationChannelResult>,
)

@Serializable
data class CalibrationBundleResponse(
    @SerialName("calibration_id") val calibrationId: String,
    @SerialName("ready_to_apply") val readyToApply: Boolean,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
)

@Serializable
data class CalibrationApplyRequest(
    @SerialName("calibration_id") val calibrationId: String,
)

@Serializable
data class CalibrationApplyResponse(
    @SerialName("active_calibration_id") val activeCalibrationId: String,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
)

@Serializable
data class CalibrationRevertResponse(
    @SerialName("active_calibration_id") val activeCalibrationId: String,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
)

@Serializable
data class SpeakerTelemetry(
    val role: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("channel_index") val channelIndex: Int,
    val online: Boolean,
    @SerialName("degraded_crossfade") val degradedCrossfade: Boolean,
    @SerialName("clock_skew_us") val clockSkewUs: Long,
    val underruns: Int,
    @SerialName("wifi_rssi_dbm") val wifiRssiDbm: Int? = null,
    val codec: String,
    @SerialName("bits_per_sample") val bitsPerSample: Int,
    @SerialName("sample_rate_hz") val sampleRateHz: Int,
)

@Serializable
data class TelemetrySessionPayload(
    val state: String,
    @SerialName("codec_active") val codecActive: String,
    @SerialName("renderer_mode") val rendererMode: String,
    @SerialName("active_decode_chain") val activeDecodeChain: String,
    @SerialName("active_oamd_objects") val activeOamdObjects: Int? = null,
    @SerialName("fallback_active") val fallbackActive: Boolean,
    @SerialName("fallback_reason") val fallbackReason: String? = null,
    @SerialName("drift_us") val driftUs: Long,
    @SerialName("resampler_ppm") val resamplerPpm: Double,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
    @SerialName("master_volume") val masterVolume: Double,
    val muted: Boolean,
    @SerialName("eq_bypass") val eqBypass: Boolean,
    @SerialName("upmix_mode") val upmixMode: String,
    @SerialName("ma_zone_state") val maZoneState: String,
    val speakers: List<SpeakerTelemetry>,
)

@Serializable
data class ConnectedEndpointStatus(
    @SerialName("client_id") val clientId: String,
    val name: String,
    @SerialName("channel_count") val channelCount: Int,
    @SerialName("transport_tier") val transportTier: String,
    val online: Boolean,
)

@Serializable
data class DiscoveredSendspinSpeaker(
    @SerialName("client_id") val clientId: String,
    val name: String,
    @SerialName("ws_url") val wsUrl: String,
    val host: String,
    val port: Int,
    val path: String,
    @SerialName("service_type") val serviceType: String,
    @SerialName("already_paired") val alreadyPaired: Boolean = false,
    @SerialName("assigned_roles") val assignedRoles: List<String> = emptyList(),
)

@Serializable
data class PairedSpeakerEndpointInfo(
    @SerialName("client_id") val clientId: String,
    val name: String,
    @SerialName("ws_url") val wsUrl: String? = null,
    @SerialName("transport_tier") val transportTier: String,
    val roles: List<String>,
    @SerialName("fixed_hw_delay_us") val fixedHwDelayUs: Long,
    val online: Boolean,
)

@Serializable
data class SpeakersListResponse(
    val layout: String,
    @SerialName("room_profile_version") val roomProfileVersion: Int,
    val paired: List<PairedSpeakerEndpointInfo>,
)

@Serializable
data class SpeakersDiscoverResponse(
    val discovered: List<DiscoveredSendspinSpeaker>,
    val paired: List<PairedSpeakerEndpointInfo>,
)

@Serializable
data class SpeakerPairRequest(
    @SerialName("client_id") val clientId: String,
    val name: String? = null,
    @SerialName("ws_url") val wsUrl: String,
    val roles: List<String>,
    @SerialName("fixed_hw_delay_us") val fixedHwDelayUs: Long? = null,
    @SerialName("coord_m") val coordM: List<Double>? = null,
)

@Serializable
data class SpeakerPairResponse(
    val paired: PairedSpeakerEndpointInfo,
    @SerialName("room_profile") val roomProfile: RoomProfileResponse,
)

@Serializable
data class SpeakerUnpairResponse(
    val removed: Boolean,
    @SerialName("client_id") val clientId: String,
    @SerialName("room_profile") val roomProfile: RoomProfileResponse,
)

@Serializable
data class SpeakerProbeChimeRequest(
    @SerialName("ws_url") val wsUrl: String,
    @SerialName("client_id") val clientId: String? = null,
    val signal: String? = null,
    @SerialName("gain_dbfs") val gainDbfs: Double? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
)

@Serializable
data class RoomSummary(
    @SerialName("room_id") val roomId: String,
    @SerialName("room_name") val roomName: String,
    val layout: String,
    @SerialName("speaker_count") val speakerCount: Int,
    val state: String,
    @SerialName("active_session_id") val activeSessionId: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("output_kinds") val outputKinds: List<String> = emptyList(),
)

@Serializable
data class RoomsListResponse(
    @SerialName("server_id") val serverId: String,
    @SerialName("active_room_id") val activeRoomId: String,
    val rooms: List<RoomSummary>,
)

@Serializable
data class CreateRoomRequest(
    @SerialName("room_name") val roomName: String,
)

@Serializable
data class UpdateRoomRequest(
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("make_default") val makeDefault: Boolean? = null,
)

@Serializable
data class DeleteRoomResponse(
    val deleted: Boolean,
    @SerialName("room_id") val roomId: String,
    @SerialName("active_room_id") val activeRoomId: String,
)

@Serializable
data class LocalAudioOutputDevice(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    @SerialName("card_index") val cardIndex: Int,
    @SerialName("device_index") val deviceIndex: Int,
    @SerialName("min_channels") val minChannels: Int,
    @SerialName("max_channels") val maxChannels: Int,
    val kind: String,
    @SerialName("assigned_room_id") val assignedRoomId: String? = null,
    @SerialName("assigned_roles") val assignedRoles: List<String> = emptyList(),
    @SerialName("supported_formats") val supportedFormats: List<String> = emptyList(),
    @SerialName("supported_channel_counts") val supportedChannelCounts: List<Int> = emptyList(),
    @SerialName("in_use") val inUse: Boolean = false,
    @SerialName("capability_warning") val capabilityWarning: String? = null,
)

@Serializable
data class LocalOutputsDiscoverResponse(
    @SerialName("alsa_supported") val alsaSupported: Boolean,
    val devices: List<LocalAudioOutputDevice>,
)

@Serializable
data class LocalOutputPairRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("client_id") val clientId: String? = null,
    val name: String? = null,
    val roles: List<String>,
    @SerialName("fixed_hw_delay_us") val fixedHwDelayUs: Long? = null,
)

@Serializable
data class LocalOutputProbeChimeRequest(
    @SerialName("device_id") val deviceId: String,
    val signal: String? = null,
    @SerialName("gain_dbfs") val gainDbfs: Double? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
)

@Serializable
data class NoiseEndpointProvisionRequest(
    @SerialName("client_id") val clientId: String,
    val name: String? = null,
    val roles: List<String>,
    @SerialName("transport_tier") val transportTier: String? = null,
    @SerialName("fixed_hw_delay_us") val fixedHwDelayUs: Long? = null,
)

@Serializable
data class SinkPskFileConfig(
    @SerialName("client_id") val clientId: String,
    @SerialName("server_noise_pub_b64") val serverNoisePubB64: String,
    @SerialName("client_noise_priv_b64") val clientNoisePrivB64: String,
    @SerialName("client_noise_pub_b64") val clientNoisePubB64: String,
    @SerialName("psk_b64") val pskB64: String,
)

@Serializable
data class NoiseEndpointProvisionResponse(
    val paired: PairedSpeakerEndpointInfo,
    @SerialName("psk_config") val pskConfig: SinkPskFileConfig,
    @SerialName("room_profile") val roomProfile: RoomProfileResponse,
)

@Serializable
data class AvrStatusResponse(
    @SerialName("session_id") val sessionId: String? = null,
    val state: String,
    @SerialName("codec_active") val codecActive: String? = null,
    @SerialName("renderer_mode") val rendererMode: String? = null,
    @SerialName("active_decode_chain") val activeDecodeChain: String? = null,
    @SerialName("active_oamd_objects") val activeOamdObjects: Int? = null,
    @SerialName("fallback_active") val fallbackActive: Boolean? = null,
    @SerialName("fallback_reason") val fallbackReason: String? = null,
    @SerialName("drift_us") val driftUs: Long,
    @SerialName("resampler_ppm") val resamplerPpm: Double,
    @SerialName("dsp_group_delay_us") val dspGroupDelayUs: Long,
    @SerialName("master_volume") val masterVolume: Double,
    val muted: Boolean,
    @SerialName("eq_bypass") val eqBypass: Boolean,
    @SerialName("upmix_mode") val upmixMode: String,
    @SerialName("ma_zone_state") val maZoneState: String,
    val speakers: List<SpeakerTelemetry>,
    @SerialName("connected_endpoints") val connectedEndpoints: List<ConnectedEndpointStatus>,
)

@Serializable
data class RoomControlsUpdateRequest(
    @SerialName("master_volume") val masterVolume: Double? = null,
    val muted: Boolean? = null,
    @SerialName("eq_bypass") val eqBypass: Boolean? = null,
    @SerialName("upmix_mode") val upmixMode: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("uptime_s") val uptimeS: Long,
)

@Serializable
data class SpeakerDegradedPayload(
    val role: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("rerouted_to_roles") val reroutedToRoles: List<String>,
    val reason: String,
)

@Serializable
data class SpeakerRestoredPayload(
    val role: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class DecoderFallbackPayload(
    @SerialName("from_chain") val fromChain: String,
    @SerialName("to_chain") val toChain: String,
    @SerialName("codec_active") val codecActive: String,
    @SerialName("renderer_mode") val rendererMode: String,
    val reason: String,
)

@Serializable
data class MaStateChangedPayload(
    @SerialName("ma_zone_state") val maZoneState: String,
)

@Serializable
data class PairPinResponse(
    val pin: String,
    @SerialName("qr_uri") val qrUri: String,
    @SerialName("expires_at_s") val expiresAtS: Long,
    @SerialName("qr_svg_b64") val qrSvgB64: String? = null,
    @SerialName("tls_spki_sha256") val tlsSpkiSha256: String? = null,
    @SerialName("tls_sas") val tlsSas: String? = null,
)

@Serializable
data class PairClaimRequest(
    val pin: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String,
)

@Serializable
data class PairClaimResponse(
    @SerialName("avr_token") val avrToken: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
)

@Serializable
data class ClientRevokeResponse(
    val revoked: Boolean,
)

@Serializable
data class RpcErrorPayload(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)

@Serializable
data class HttpErrorResponse(
    @SerialName("error_code") val errorCode: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject? = null,
)
