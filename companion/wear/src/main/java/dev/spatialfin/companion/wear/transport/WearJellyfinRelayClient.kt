package dev.spatialfin.companion.wear.transport

import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearPlayerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A remotely controllable playback session reported by the Jellyfin server. */
data class RelaySession(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val nowPlayingTitle: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val isPaused: Boolean,
)

/**
 * Standalone control path: watch → Jellyfin server → target device.
 *
 * SpatialFin's network remote control is **not** peer-to-peer. Commands reach the
 * target through the server's own session feed, so this needs a reachable server plus
 * the credential bundle the host pushed while tethered (docs/wear.md §2.4). The FCast
 * LAN path in [WearDirectLanClient] needs none of that and stays the first fallback;
 * this one covers targets that aren't FCast receivers.
 */
@Singleton
class WearJellyfinRelayClient @Inject constructor(
    private val credentialsStore: WearCredentialsStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Sessions the server says accept remote control, host's own session first. */
    suspend fun listControllableSessions(): List<RelaySession> = withContext(Dispatchers.IO) {
        val creds = credentialsStore.getCredentials() ?: return@withContext emptyList()
        runCatching {
            val url = creds.serverUrl.trimEnd('/').toHttpUrlOrNull()
                ?.newBuilder()
                ?.addPathSegment("Sessions")
                ?.addQueryParameter("ControllableByUserId", creds.userId)
                ?.build()
                ?: return@withContext emptyList()

            val body = client.newCall(authorized(creds, Request.Builder().url(url)).build())
                .execute()
                .use { response -> if (response.isSuccessful) response.body?.string() else null }
                ?: return@withContext emptyList()

            // The paired host's own session first — it is the device the user was just
            // watching on — then anything else the server says we may control.
            json.parseToJsonElement(body).jsonArray
                .mapNotNull { it.toRelaySession() }
                .sortedWith(
                    compareByDescending<RelaySession> { it.deviceId == creds.deviceId }
                        .thenByDescending { it.nowPlayingTitle != null },
                )
        }.getOrElse {
            Timber.w(it, "WearJellyfinRelayClient: session listing failed")
            emptyList()
        }
    }

    fun nowPlayingFrom(session: RelaySession): WearNowPlayingState = WearNowPlayingState(
        isPlaying = !session.isPaused,
        positionSeconds = session.positionSeconds,
        durationSeconds = session.durationSeconds,
        title = session.nowPlayingTitle.orEmpty(),
        targetDeviceName = session.deviceName,
        timestampEpochMs = System.currentTimeMillis(),
    )

    suspend fun dispatch(sessionId: String, action: WearPlayerAction): Result<String> =
        withContext(Dispatchers.IO) {
            val creds = credentialsStore.getCredentials()
                ?: return@withContext Result.failure(IllegalStateException("Connect your phone once to finish setup"))

            when (action) {
                is WearPlayerAction.Play -> playstate(creds, sessionId, "Unpause", null, "Playing")
                is WearPlayerAction.Pause -> playstate(creds, sessionId, "Pause", null, "Paused")
                is WearPlayerAction.TogglePlayPause -> playstate(creds, sessionId, "PlayPause", null, "Toggled playback")
                is WearPlayerAction.SeekTo ->
                    playstate(creds, sessionId, "Seek", action.positionSeconds * TICKS_PER_SECOND, "Seeked")
                is WearPlayerAction.SeekForward -> playstate(creds, sessionId, "Fastforward", null, "Skipped forward")
                is WearPlayerAction.SeekBackward -> playstate(creds, sessionId, "Rewind", null, "Rewound")
                is WearPlayerAction.NextEpisode -> playstate(creds, sessionId, "NextTrack", null, "Next item")
                is WearPlayerAction.PreviousEpisode -> playstate(creds, sessionId, "PreviousTrack", null, "Previous item")
                is WearPlayerAction.StopFCastCasting -> playstate(creds, sessionId, "Stop", null, "Stopped")
                is WearPlayerAction.AdjustVolume -> {
                    val pct = action.percentage
                    if (pct == null) {
                        Result.success("Volume unchanged")
                    } else {
                        generalCommand(creds, sessionId, "SetVolume", mapOf("Volume" to (pct * 100).toInt().toString()), "Volume ${(pct * 100).toInt()}%")
                    }
                }
                else -> Result.success("Not available over the server relay")
            }
        }

    private fun playstate(
        creds: WearCredentials,
        sessionId: String,
        command: String,
        seekPositionTicks: Long?,
        feedback: String,
    ): Result<String> = runCatching {
        val builder = creds.serverUrl.trimEnd('/').toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("Sessions")
            ?.addPathSegment(sessionId)
            ?.addPathSegment("Playing")
            ?.addPathSegment(command)
            ?: error("Bad server URL")
        seekPositionTicks?.let { builder.addQueryParameter("seekPositionTicks", it.toString()) }

        val request = authorized(creds, Request.Builder().url(builder.build()))
            .post(ByteArray(0).toRequestBody())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server returned ${response.code}")
        }
        feedback
    }.onFailure { Timber.w(it, "WearJellyfinRelayClient: %s failed", command) }

    private fun generalCommand(
        creds: WearCredentials,
        sessionId: String,
        command: String,
        arguments: Map<String, String>,
        feedback: String,
    ): Result<String> = runCatching {
        val url = creds.serverUrl.trimEnd('/').toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegment("Sessions")
            ?.addPathSegment(sessionId)
            ?.addPathSegment("Command")
            ?.build()
            ?: error("Bad server URL")

        val payload = buildString {
            append("""{"Name":"""").append(command).append("""","Arguments":{""")
            append(arguments.entries.joinToString(",") { """"${it.key}":"${it.value}"""" })
            append("}}")
        }

        val request = authorized(creds, Request.Builder().url(url))
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server returned ${response.code}")
        }
        feedback
    }.onFailure { Timber.w(it, "WearJellyfinRelayClient: general command %s failed", command) }

    private fun authorized(creds: WearCredentials, builder: Request.Builder): Request.Builder =
        builder.header(
            "Authorization",
            """MediaBrowser Client="SpatialFin Watch", Device="Wear OS", DeviceId="${creds.deviceId}", Version="1", Token="${creds.accessToken}"""",
        )

    private fun kotlinx.serialization.json.JsonElement.toRelaySession(): RelaySession? = runCatching {
        val obj = jsonObject
        if (obj["SupportsRemoteControl"]?.jsonPrimitive?.boolean != true) return null
        val nowPlaying = obj["NowPlayingItem"]?.takeIf { it is JsonObject }?.jsonObject
        val playState = obj["PlayState"]?.takeIf { it is JsonObject }?.jsonObject
        RelaySession(
            sessionId = obj["Id"]?.jsonPrimitive?.contentOrNull ?: return null,
            deviceId = obj["DeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            deviceName = obj["DeviceName"]?.jsonPrimitive?.contentOrNull ?: "Unknown device",
            nowPlayingTitle = nowPlaying?.get("Name")?.jsonPrimitive?.contentOrNull,
            positionSeconds = (playState?.get("PositionTicks")?.jsonPrimitive?.long ?: 0L) / TICKS_PER_SECOND,
            durationSeconds = (nowPlaying?.get("RunTimeTicks")?.jsonPrimitive?.long ?: 0L) / TICKS_PER_SECOND,
            isPaused = playState?.get("IsPaused")?.jsonPrimitive?.boolean ?: true,
        )
    }.getOrNull()

    private companion object {
        const val TICKS_PER_SECOND = 10_000_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
