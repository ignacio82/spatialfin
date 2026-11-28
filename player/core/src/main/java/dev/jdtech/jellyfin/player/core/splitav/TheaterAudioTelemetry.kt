package dev.jdtech.jellyfin.player.core.splitav

import org.json.JSONObject

/**
 * Live Theater Audio telemetry surfaced inside XR `PlaybackStatsOverlay` and diagnostic logs.
 */
data class TheaterAudioTelemetry(
    val sessionId: String? = null,
    val state: String = "idle",
    val driftUs: Long = 0L,
    val resamplerPpm: Double = 0.0,
    val clockUncertaintyUs: Long = 0L,
    val tPlayLaunchErrorUs: Long? = null,
    val dspGroupDelayUs: Long = 0L,
    val codecActive: String? = null,
    val rendererMode: String? = null,
    val activeDecodeChain: String? = null,
    val activeOamdObjects: Int? = null,
    val eqBypass: Boolean = false,
    val degradedSpeaker: String? = null,
    val fallbackActive: Boolean = false,
    val fallbackReason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("session_id", sessionId ?: JSONObject.NULL)
        put("state", state)
        put("drift_us", driftUs)
        put("resampler_ppm", resamplerPpm)
        put("clock_uncertainty_us", clockUncertaintyUs)
        put("t_play_launch_error_us", tPlayLaunchErrorUs ?: JSONObject.NULL)
        put("dsp_group_delay_us", dspGroupDelayUs)
        put("codec_active", codecActive ?: JSONObject.NULL)
        put("renderer_mode", rendererMode ?: JSONObject.NULL)
        put("active_decode_chain", activeDecodeChain ?: JSONObject.NULL)
        put("active_oamd_objects", activeOamdObjects ?: JSONObject.NULL)
        put("eq_bypass", eqBypass)
        put("degraded_speaker", degradedSpeaker ?: JSONObject.NULL)
        put("fallback_active", fallbackActive)
        put("fallback_reason", fallbackReason ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): TheaterAudioTelemetry = TheaterAudioTelemetry(
            sessionId = obj.optNullableString("session_id"),
            state = obj.optString("state", "idle"),
            driftUs = obj.optLong("drift_us", 0L),
            resamplerPpm = obj.optDouble("resampler_ppm", 0.0),
            clockUncertaintyUs = obj.optLong("clock_uncertainty_us", 0L),
            tPlayLaunchErrorUs = obj.optNullableLong("t_play_launch_error_us"),
            dspGroupDelayUs = obj.optLong("dsp_group_delay_us", 0L),
            codecActive = obj.optNullableString("codec_active"),
            rendererMode = obj.optNullableString("renderer_mode"),
            activeDecodeChain = obj.optNullableString("active_decode_chain"),
            activeOamdObjects = obj.optNullableInt("active_oamd_objects"),
            eqBypass = obj.optBoolean("eq_bypass", false),
            degradedSpeaker = obj.optNullableString("degraded_speaker"),
            fallbackActive = obj.optBoolean("fallback_active", false),
            fallbackReason = obj.optNullableString("fallback_reason"),
        )
    }
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

internal fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
