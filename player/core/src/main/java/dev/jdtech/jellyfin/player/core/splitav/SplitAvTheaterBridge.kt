package dev.jdtech.jellyfin.player.core.splitav

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TheaterHudBanner(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val requiresTakeoverConfirm: Boolean = false,
    val requiresRepair: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
        put("retryable", retryable)
        put("requires_takeover_confirm", requiresTakeoverConfirm)
        put("requires_repair", requiresRepair)
    }

    companion object {
        fun fromJson(obj: JSONObject): TheaterHudBanner = TheaterHudBanner(
            code = obj.optString("code"),
            message = obj.optString("message"),
            retryable = obj.optBoolean("retryable", false),
            requiresTakeoverConfirm = obj.optBoolean("requires_takeover_confirm", false),
            requiresRepair = obj.optBoolean("requires_repair", false),
        )
    }
}

data class TheaterRoomSummaryContract(
    val receiverId: String,
    val roomId: String,
    val roomName: String,
    val layout: String,
    val isCurrent: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("receiver_id", receiverId)
        put("room_id", roomId)
        put("room_name", roomName)
        put("layout", layout)
        put("is_current", isCurrent)
    }

    companion object {
        fun fromJson(obj: JSONObject): TheaterRoomSummaryContract = TheaterRoomSummaryContract(
            receiverId = obj.optString("receiver_id"),
            roomId = obj.optString("room_id"),
            roomName = obj.optString("room_name"),
            layout = obj.optString("layout"),
            isCurrent = obj.optBoolean("is_current", false),
        )
    }
}

data class TheaterSessionSnapshot(
    val active: Boolean = false,
    val serverId: String? = null,
    val serverName: String? = null,
    val sessionId: String? = null,
    val masterVolume: Double = 1.0,
    val muted: Boolean = false,
    val eqBypass: Boolean = false,
    val upmixMode: String = "native",
    val maZoneState: String = "idle",
    val lcrScreenLockEnabled: Boolean = false,
    val anchorUuid: String? = null,
    val anchorToMlpTransform: List<Float>? = null,
    val roomGeometry: RoomGeometryContract? = null,
    val availableRooms: List<TheaterRoomSummaryContract> = emptyList(),
    val telemetry: TheaterAudioTelemetry = TheaterAudioTelemetry(),
    val banner: TheaterHudBanner? = null,
    val lastToast: String? = null,
) {
    fun toJsonString(): String = JSONObject().apply {
        put("active", active)
        put("server_id", serverId ?: JSONObject.NULL)
        put("server_name", serverName ?: JSONObject.NULL)
        put("session_id", sessionId ?: JSONObject.NULL)
        put("master_volume", masterVolume)
        put("muted", muted)
        put("eq_bypass", eqBypass)
        put("upmix_mode", upmixMode)
        put("ma_zone_state", maZoneState)
        put("lcr_screen_lock_enabled", lcrScreenLockEnabled)
        put("anchor_uuid", anchorUuid ?: JSONObject.NULL)
        if (anchorToMlpTransform != null) {
            put(
                "anchor_to_mlp_transform",
                JSONArray().apply { anchorToMlpTransform.forEach { put(it.toDouble()) } },
            )
        } else {
            put("anchor_to_mlp_transform", JSONObject.NULL)
        }
        put("room_geometry", roomGeometry?.toJson() ?: JSONObject.NULL)
        put(
            "available_rooms",
            JSONArray().apply { availableRooms.forEach { put(it.toJson()) } },
        )
        put("telemetry", telemetry.toJson())
        put("banner", banner?.toJson() ?: JSONObject.NULL)
        put("last_toast", lastToast ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): TheaterSessionSnapshot {
            val obj = JSONObject(raw)
            val transform = if (!obj.has("anchor_to_mlp_transform") || obj.isNull("anchor_to_mlp_transform")) {
                null
            } else {
                obj.optJSONArray("anchor_to_mlp_transform")?.let { arr ->
                    List(arr.length()) { i -> arr.optDouble(i, 0.0).toFloat() }
                }
            }
            val roomGeometry = if (!obj.has("room_geometry") || obj.isNull("room_geometry")) {
                null
            } else {
                obj.optJSONObject("room_geometry")?.let(RoomGeometryContract::fromJson)
            }
            val availableRooms = obj.optJSONArray("available_rooms")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { add(TheaterRoomSummaryContract.fromJson(it)) }
                    }
                }
            }.orEmpty()
            val telemetry = obj.optJSONObject("telemetry")?.let(TheaterAudioTelemetry::fromJson)
                ?: TheaterAudioTelemetry()
            val banner = if (!obj.has("banner") || obj.isNull("banner")) {
                null
            } else {
                obj.optJSONObject("banner")?.let(TheaterHudBanner::fromJson)
            }
            return TheaterSessionSnapshot(
                active = obj.optBoolean("active", false),
                serverId = obj.optNullableString("server_id"),
                serverName = obj.optNullableString("server_name"),
                sessionId = obj.optNullableString("session_id"),
                masterVolume = obj.optDouble("master_volume", 1.0),
                muted = obj.optBoolean("muted", false),
                eqBypass = obj.optBoolean("eq_bypass", false),
                upmixMode = obj.optString("upmix_mode", "native"),
                maZoneState = obj.optString("ma_zone_state", "idle"),
                lcrScreenLockEnabled = obj.optBoolean("lcr_screen_lock_enabled", false),
                anchorUuid = obj.optNullableString("anchor_uuid"),
                anchorToMlpTransform = transform,
                roomGeometry = roomGeometry,
                availableRooms = availableRooms,
                telemetry = telemetry,
                banner = banner,
                lastToast = obj.optNullableString("last_toast"),
            )
        }
    }
}

sealed interface TheaterClientEvent {
    data class BufferingStall(val barrierSeq: Long, val stalled: Boolean) : TheaterClientEvent
    data class AudioTrackChanged(val mediaStreamIndex: Int, val codec: String?) : TheaterClientEvent
    data class MediaItemChanged(
        val itemId: String,
        val mediaSourceId: String?,
        val audioStreamIndex: Int,
    ) : TheaterClientEvent

    data class SetVolume(val volume: Double, val muted: Boolean) : TheaterClientEvent
    data class SetMuted(val muted: Boolean) : TheaterClientEvent
    data class SetEqBypass(val bypass: Boolean) : TheaterClientEvent
    data class SetUpmixMode(val mode: String) : TheaterClientEvent
    data class SetLcrScreenLockPref(val enabled: Boolean) : TheaterClientEvent
    data class CenterSnap(val durationMs: Int = 200, val gainDbfs: Double = -18.0) : TheaterClientEvent
    data class ConfirmTakeover(val confirm: Boolean) : TheaterClientEvent
    data class SwitchRoom(val receiverId: String) : TheaterClientEvent
    data object ReconnectRequested : TheaterClientEvent
    data object FoldBackRequested : TheaterClientEvent

    fun toJsonString(): String = JSONObject().apply {
        when (this@TheaterClientEvent) {
            is BufferingStall -> {
                put("type", TYPE_BUFFERING_STALL)
                put("barrier_seq", barrierSeq)
                put("stalled", stalled)
            }
            is AudioTrackChanged -> {
                put("type", TYPE_AUDIO_TRACK_CHANGED)
                put("media_stream_index", mediaStreamIndex)
                put("codec", codec ?: JSONObject.NULL)
            }
            is MediaItemChanged -> {
                put("type", TYPE_MEDIA_ITEM_CHANGED)
                put("item_id", itemId)
                put("media_source_id", mediaSourceId ?: JSONObject.NULL)
                put("audio_stream_index", audioStreamIndex)
            }
            is SetVolume -> {
                put("type", TYPE_SET_VOLUME)
                put("volume", volume)
                put("muted", muted)
            }
            is SetMuted -> {
                put("type", TYPE_SET_MUTED)
                put("muted", muted)
            }
            is SetEqBypass -> {
                put("type", TYPE_SET_EQ_BYPASS)
                put("bypass", bypass)
            }
            is SetUpmixMode -> {
                put("type", TYPE_SET_UPMIX_MODE)
                put("mode", mode)
            }
            is SetLcrScreenLockPref -> {
                put("type", TYPE_SET_LCR_SCREEN_LOCK_PREF)
                put("enabled", enabled)
            }
            is CenterSnap -> {
                put("type", TYPE_CENTER_SNAP)
                put("duration_ms", durationMs)
                put("gain_dbfs", gainDbfs)
            }
            is ConfirmTakeover -> {
                put("type", TYPE_CONFIRM_TAKEOVER)
                put("confirm", confirm)
            }
            is SwitchRoom -> {
                put("type", TYPE_SWITCH_ROOM)
                put("receiver_id", receiverId)
            }
            ReconnectRequested -> put("type", TYPE_RECONNECT_REQUESTED)
            FoldBackRequested -> put("type", TYPE_FOLD_BACK_REQUESTED)
        }
    }.toString()

    companion object {
        const val LOCAL_AUDIO_RECEIVER_ID: String = "__local_audio__"

        private const val TYPE_BUFFERING_STALL = "buffering_stall"
        private const val TYPE_AUDIO_TRACK_CHANGED = "audio_track_changed"
        private const val TYPE_MEDIA_ITEM_CHANGED = "media_item_changed"
        private const val TYPE_SET_VOLUME = "set_volume"
        private const val TYPE_SET_MUTED = "set_muted"
        private const val TYPE_SET_EQ_BYPASS = "set_eq_bypass"
        private const val TYPE_SET_UPMIX_MODE = "set_upmix_mode"
        private const val TYPE_SET_LCR_SCREEN_LOCK_PREF = "set_lcr_screen_lock_pref"
        private const val TYPE_CENTER_SNAP = "center_snap"
        private const val TYPE_CONFIRM_TAKEOVER = "confirm_takeover"
        private const val TYPE_SWITCH_ROOM = "switch_room"
        private const val TYPE_RECONNECT_REQUESTED = "reconnect_requested"
        private const val TYPE_FOLD_BACK_REQUESTED = "fold_back_requested"

        fun fromJsonString(raw: String): TheaterClientEvent {
            val obj = JSONObject(raw)
            return when (val type = obj.getString("type")) {
                TYPE_BUFFERING_STALL -> BufferingStall(
                    barrierSeq = obj.optLong("barrier_seq", 0L),
                    stalled = obj.optBoolean("stalled", true),
                )
                TYPE_AUDIO_TRACK_CHANGED -> AudioTrackChanged(
                    mediaStreamIndex = obj.getInt("media_stream_index"),
                    codec = obj.optNullableString("codec"),
                )
                TYPE_MEDIA_ITEM_CHANGED -> MediaItemChanged(
                    itemId = obj.getString("item_id"),
                    mediaSourceId = obj.optNullableString("media_source_id"),
                    audioStreamIndex = obj.getInt("audio_stream_index"),
                )
                TYPE_SET_VOLUME -> SetVolume(
                    volume = obj.getDouble("volume"),
                    muted = obj.optBoolean("muted", false),
                )
                TYPE_SET_MUTED -> SetMuted(muted = obj.getBoolean("muted"))
                TYPE_SET_EQ_BYPASS -> SetEqBypass(bypass = obj.getBoolean("bypass"))
                TYPE_SET_UPMIX_MODE -> SetUpmixMode(mode = obj.getString("mode"))
                TYPE_SET_LCR_SCREEN_LOCK_PREF -> SetLcrScreenLockPref(
                    enabled = obj.getBoolean("enabled"),
                )
                TYPE_CENTER_SNAP -> CenterSnap(
                    durationMs = obj.optInt("duration_ms", 200),
                    gainDbfs = obj.optDouble("gain_dbfs", -18.0),
                )
                TYPE_CONFIRM_TAKEOVER -> ConfirmTakeover(
                    confirm = obj.optBoolean("confirm", false),
                )
                TYPE_SWITCH_ROOM -> SwitchRoom(
                    receiverId = obj.getString("receiver_id"),
                )
                TYPE_RECONNECT_REQUESTED -> ReconnectRequested
                TYPE_FOLD_BACK_REQUESTED -> FoldBackRequested
                else -> throw IllegalArgumentException("Unknown TheaterClientEvent type: $type")
            }
        }
    }
}

/** Shared Theater Audio HUD helpers used across XR, Beam, and TV player overlays. */
object TheaterHudHelpers {
    val UPMIX_CYCLE: List<String> = listOf(
        "native",
        "all_channel_stereo",
        "dts_height_upmix",
        "neural_height",
    )

    fun nextUpmixMode(current: String): String {
        val idx = UPMIX_CYCLE.indexOf(current)
        return UPMIX_CYCLE[(idx + 1) % UPMIX_CYCLE.size]
    }

    fun upmixModeLabel(mode: String): String = when (mode) {
        "all_channel_stereo" -> "Upmix: All-Ch"
        "dts_height_upmix" -> "Upmix: Height"
        "neural_height" -> "Upmix: Neural"
        else -> "Upmix: Native"
    }

    /** Formats live Atmos/OAMD or bed-fallback status for the player HUD and Stats for Nerds. */
    fun formatAtmosBadge(telemetry: TheaterAudioTelemetry): String? = when {
        telemetry.fallbackActive -> {
            val reason = telemetry.fallbackReason?.takeIf { it.isNotBlank() }
            if (reason != null) "Bed Fallback ($reason)" else "Bed Fallback"
        }
        telemetry.rendererMode == "truehd_vbap" -> {
            val objs = telemetry.activeOamdObjects
            if (objs != null && objs > 0) "Atmos VBAP · $objs obj" else "Atmos VBAP"
        }
        else -> null
    }
}

/**
 * Shared Theater Audio snapshot/event bridge. Runs directly in-process for Beam and is mirrored
 * across `SplitAvBridgeIpc` (`MSG_THEATER_SNAPSHOT` / `MSG_THEATER_EVENT`) for `:xrplayer`.
 */
object SplitAvTheaterBridge {
    private val _snapshot = MutableStateFlow<TheaterSessionSnapshot?>(null)
    val snapshot: StateFlow<TheaterSessionSnapshot?> = _snapshot.asStateFlow()

    private val _clientEvents = MutableSharedFlow<TheaterClientEvent>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    val clientEvents: SharedFlow<TheaterClientEvent> = _clientEvents.asSharedFlow()

    @Volatile
    private var outboundEventForwarder: ((TheaterClientEvent) -> Unit)? = null

    fun updateSnapshot(snapshot: TheaterSessionSnapshot?) {
        _snapshot.value = snapshot
    }

    fun setOutboundEventForwarder(forwarder: ((TheaterClientEvent) -> Unit)?) {
        outboundEventForwarder = forwarder
    }

    fun emitClientEvent(event: TheaterClientEvent) {
        val forwarder = outboundEventForwarder
        if (forwarder != null) {
            forwarder(event)
        } else {
            _clientEvents.tryEmit(event)
        }
    }
}
