package dev.jdtech.jellyfin.player.core.splitav

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-Kotlin geometry contract mirroring Spec 0's canonical MLP-rooted Room Coordinate Frame
 * for cross-process delivery (`:main -> :xrplayer`) and XR cinema screen anchoring.
 */
data class TheaterScreenPlaneContract(
    val centerM: List<Double>,
    val widthM: Double,
    val heightM: Double,
    val normalVec: List<Double>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("center_m", JSONArray().apply { centerM.forEach { put(it) } })
        put("width_m", widthM)
        put("height_m", heightM)
        put("normal_vec", JSONArray().apply { normalVec.forEach { put(it) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): TheaterScreenPlaneContract = TheaterScreenPlaneContract(
            centerM = obj.optJSONArray("center_m").toDoubleList(),
            widthM = obj.optDouble("width_m", 2.4),
            heightM = obj.optDouble("height_m", 1.35),
            normalVec = obj.optJSONArray("normal_vec").toDoubleList(),
        )
    }
}

data class TheaterSpeakerContract(
    val role: String,
    val clientId: String,
    val channelIndex: Int,
    val transportTier: String,
    val coordM: List<Double>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("client_id", clientId)
        put("channel_index", channelIndex)
        put("transport_tier", transportTier)
        put("coord_m", JSONArray().apply { coordM.forEach { put(it) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): TheaterSpeakerContract = TheaterSpeakerContract(
            role = obj.optString("role"),
            clientId = obj.optString("client_id"),
            channelIndex = obj.optInt("channel_index", 0),
            transportTier = obj.optString("transport_tier"),
            coordM = obj.optJSONArray("coord_m").toDoubleList(),
        )
    }
}

data class RoomGeometryContract(
    val roomProfileVersion: Int,
    val roomName: String,
    val layout: String,
    val lcrScreenPlane: TheaterScreenPlaneContract? = null,
    val speakers: List<TheaterSpeakerContract> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("room_profile_version", roomProfileVersion)
        put("room_name", roomName)
        put("layout", layout)
        if (lcrScreenPlane != null) {
            put("lcr_screen_plane", lcrScreenPlane.toJson())
        } else {
            put("lcr_screen_plane", JSONObject.NULL)
        }
        put(
            "speakers",
            JSONArray().apply { speakers.forEach { put(it.toJson()) } },
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): RoomGeometryContract {
            val planeObj = if (!obj.has("lcr_screen_plane") || obj.isNull("lcr_screen_plane")) {
                null
            } else {
                obj.optJSONObject("lcr_screen_plane")?.let(TheaterScreenPlaneContract::fromJson)
            }
            val speakerArr = obj.optJSONArray("speakers")
            val speakers = buildList {
                if (speakerArr != null) {
                    for (i in 0 until speakerArr.length()) {
                        speakerArr.optJSONObject(i)?.let { add(TheaterSpeakerContract.fromJson(it)) }
                    }
                }
            }
            return RoomGeometryContract(
                roomProfileVersion = obj.optInt("room_profile_version", 0),
                roomName = obj.optString("room_name"),
                layout = obj.optString("layout"),
                lcrScreenPlane = planeObj,
                speakers = speakers,
            )
        }
    }
}

private fun JSONArray?.toDoubleList(): List<Double> {
    if (this == null) return emptyList()
    return List(length()) { i -> optDouble(i, 0.0) }
}
