package dev.jdtech.jellyfin.sendspin.receiver

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Transport for Music Assistant JSON-RPC commands. The REST path (default) POSTs to `/api`;
 * the WebRTC remote path ([dev.jdtech.jellyfin.sendspin.receiver.remote.MaApiChannelClient])
 * sends the same `{command, args, message_id}` over the `ma-api` data channel and returns the
 * `{message_id, result}` response. `execute` returns the raw response JSON (a bare array or a
 * `{result|data: [...]}` object — both handled by `asArrayResult`).
 */
internal fun interface MaRpcTransport {
    fun execute(command: String, args: JSONObject?): String
}

internal class MusicAssistantGroupClient(
    private val httpClient: OkHttpClient,
) {
    /**
     * When non-null, JSON-RPC commands route through this transport (off-LAN WebRTC remote)
     * instead of REST `/api`. `login` / `fetchInfo` always use REST — they are LAN-only
     * discovery/token acquisition and are not used in remote mode. Set/cleared by the service.
     */
    @Volatile
    var rpcTransport: MaRpcTransport? = null
    fun fetchInfo(baseUrl: String): MusicAssistantServerInfo? {
        val request =
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/info")
                .get()
                .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body.string()
            val json = JSONObject(body)
            return MusicAssistantServerInfo(
                serverId = json.optStringOrNull("server_id"),
                baseUrl = json.optStringOrNull("base_url") ?: baseUrl.trimEnd('/'),
                status = json.optStringOrNull("status"),
            )
        }
    }

    fun login(baseUrl: String, username: String, password: String): String {
        val credentials =
            JSONObject()
                .put("username", username)
                .put("password", password)
        val payload = JSONObject().put("credentials", credentials)
        val request =
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/auth/login")
                .post(payload.toString().toRequestBody(JSON))
                .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException("Music Assistant login failed (${response.code})")
            }
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                throw IOException(json.optStringOrNull("error") ?: "Music Assistant login failed")
            }
            return json.optStringOrNull("token")?.takeIf { it.isNotBlank() }
                ?: throw IOException("Music Assistant did not return an access token")
        }
    }

    fun fetchPlayers(baseUrl: String, token: String): List<MusicAssistantPlayerState> {
        val args =
            JSONObject()
                .put("return_unavailable", false)
                .put("return_disabled", false)
                .put("return_protocol_players", true)
        val response = executeApiCommand(baseUrl, token, "players/all", args)
        val players = response.asArrayResult()
        return List(players.length()) { index ->
            players.getJSONObject(index).toPlayerState()
        }
    }

    fun setMembers(
        baseUrl: String,
        token: String,
        targetPlayer: String,
        playerIdsToAdd: List<String>,
        playerIdsToRemove: List<String>,
    ) {
        val args =
            JSONObject()
                .put("target_player", targetPlayer)
                .put("player_ids_to_add", JSONArray(playerIdsToAdd))
                .put("player_ids_to_remove", JSONArray(playerIdsToRemove))
        executeApiCommand(baseUrl, token, "players/cmd/set_members", args)
    }

    private fun executeApiCommand(
        baseUrl: String,
        token: String,
        command: String,
        args: JSONObject? = null,
    ): String {
        // WebRTC remote: tunnel the same command over the ma-api data channel. baseUrl/token
        // are unused here (auth is connection-level on the channel).
        rpcTransport?.let { return it.execute(command, args) }
        val payload = JSONObject()
            .put("message_id", java.util.UUID.randomUUID().toString())
            .put("command", command)
        if (args != null) payload.put("args", args)
        val payloadStr = payload.toString().replace("\\/", "/")
        timber.log.Timber.tag("MAGroupClient").d("→ %s %s", command, payloadStr)
        val request =
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api")
                .addHeader("Authorization", "Bearer $token")
                .post(payloadStr.toRequestBody(JSON))
                .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw MusicAssistantApiException(response.code, body)
            }
            return body
        }
    }

    private fun String.asArrayResult(): JSONArray {
        val trimmed = trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val obj = JSONObject(trimmed)
        obj.optJSONArray("result")?.let { return it }
        obj.optJSONArray("data")?.let { return it }
        throw JSONException("Music Assistant response did not contain an array result")
    }

    fun playMedia(
        baseUrl: String,
        token: String,
        queueId: String,
        mediaUri: String
    ) {
        val args =
            JSONObject()
                .put("queue_id", queueId)
                .put("media", JSONArray().put(mediaUri))
                .put("option", "play")
                .put("radio_mode", false)
        executeApiCommand(baseUrl, token, "player_queues/play_media", args)
    }

    private fun JSONObject.toPlayerState(): MusicAssistantPlayerState {
        val playerId = getString("player_id")
        return MusicAssistantPlayerState(
            id = playerId,
            name = optString("name", playerId),
            type = optString("type", "unknown"),
            provider = optStringOrNull("provider") ?: "",
            available = optBoolean("available", false),
            enabled = optBoolean("enabled", true),
            hideInUi = optBoolean("hide_in_ui", false),
            powered = optBooleanOrNull("powered"),
            playbackState = optStringOrNull("playback_state"),
            syncedTo = optStringOrNull("synced_to"),
            activeGroup = optStringOrNull("active_group"),
            activeSource = optStringOrNull("active_source"),
            protocolParentId = optStringOrNull("protocol_parent_id"),
            linkedOutputProtocols = optStringSet("linked_output_protocols"),
            groupMembers = optStringSet("group_members"),
            canGroupWith = optStringSet("can_group_with"),
            supportedFeatures = optStringSet("supported_features"),
        )
    }

    private fun JSONObject.optStringSet(name: String): Set<String> {
        val array = optJSONArray(name) ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                val value = array.optString(index).takeIf { it.isNotBlank() }
                if (value != null) add(value)
            }
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal data class MusicAssistantServerInfo(
    val serverId: String?,
    val baseUrl: String,
    val status: String?,
)

internal data class MusicAssistantPlayerState(
    val id: String,
    val name: String,
    val type: String,
    val provider: String,
    val available: Boolean,
    val enabled: Boolean,
    val hideInUi: Boolean,
    val powered: Boolean?,
    val playbackState: String?,
    val syncedTo: String?,
    val activeGroup: String?,
    val activeSource: String?,
    val protocolParentId: String?,
    val linkedOutputProtocols: Set<String>,
    val groupMembers: Set<String>,
    val canGroupWith: Set<String>,
    val supportedFeatures: Set<String>,
)

internal class MusicAssistantApiException(
    val statusCode: Int,
    val responseBody: String,
) : IOException("Music Assistant API request failed ($statusCode): $responseBody")
