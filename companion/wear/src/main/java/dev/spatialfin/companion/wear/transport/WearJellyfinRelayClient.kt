package dev.spatialfin.companion.wear.transport

import dev.spatialfin.companion.protocol.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Device identity survives Jellyfin session restarts. Idle clients remain selectable for Resume.
 */
data class RelaySession(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val nowPlayingTitle: String?,
    val positionSeconds: Long,
    val durationSeconds: Long,
    val isPaused: Boolean,
    val itemId: String? = null,
    val volume: Float = 1f,
    val supportsWearCommands: Boolean = false,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val metadata: JsonObject? = null,
) {
    val hasPlayback: Boolean
        get() = itemId != null || !nowPlayingTitle.isNullOrBlank()
}

class RelayDiscoveryException(message: String) : IllegalStateException(message)

@Singleton
class WearJellyfinRelayClient
internal constructor(
    private val credentialsStore: WearCredentialsStore,
    private val client: OkHttpClient,
) {
    @Inject
    constructor(
        credentialsStore: WearCredentialsStore
    ) : this(
        credentialsStore,
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build(),
    )

    private val json = WearProtocolCodec.json

    /** Errors must remain distinguishable from a successful empty result. */
    suspend fun listControllableSessions(): List<RelaySession> =
        withContext(Dispatchers.IO) {
            val creds = credentialsStore.credentials.value ?: return@withContext emptyList()
            val url =
                endpoint(creds, "Sessions")
                    .newBuilder()
                    .addQueryParameter("ControllableByUserId", creds.userId)
                    .build()
            val body = get(creds, url)
            json
                .parseToJsonElement(body)
                .jsonArray
                .mapNotNull { toRelaySession(it) }
                .sortedWith(
                    compareByDescending<RelaySession> { it.hasPlayback }
                        .thenBy { it.isPaused }
                        .thenByDescending { it.deviceId == creds.deviceId }
                )
        }

    fun nowPlayingFrom(session: RelaySession): WearNowPlayingState {
        val item = session.metadata
        val streams = (item?.get("MediaStreams") as? JsonArray).orEmpty()
        fun tracks(type: String, selected: Int?): List<WearStreamInfo> = streams.mapNotNull { raw ->
            val stream = raw as? JsonObject ?: return@mapNotNull null
            if (stream.string("Type") != type) return@mapNotNull null
            val index = stream["Index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            WearStreamInfo(
                index,
                stream.string("DisplayTitle") ?: stream.string("Language") ?: "$type ${index + 1}",
                stream.string("Language"),
                index == selected,
                stream["IsForced"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        val audio = tracks("Audio", session.audioStreamIndex)
        val subs = tracks("Subtitle", session.subtitleStreamIndex)
        return WearNowPlayingState(
            isPlaying = session.hasPlayback && !session.isPaused,
            positionSeconds = session.positionSeconds,
            durationSeconds = session.durationSeconds,
            title = session.nowPlayingTitle.orEmpty(),
            itemId = session.itemId,
            volume = session.volume,
            targetDeviceName = session.deviceName,
            timestampEpochMs = System.currentTimeMillis(),
            audioTracks = audio,
            subtitleTracks = subs,
            currentAudioTrack = audio.firstOrNull { it.isSelected }?.name,
            currentSubtitleTrack = subs.firstOrNull { it.isSelected }?.name,
            chapters =
                (item?.get("Chapters") as? JsonArray).orEmpty().mapIndexedNotNull { index, raw ->
                    val chapter = raw as? JsonObject ?: return@mapIndexedNotNull null
                    WearChapterInfo(
                        chapter.string("Name") ?: "Chapter ${index + 1}",
                        (chapter["StartPositionTicks"]?.jsonPrimitive?.longOrNull ?: 0) /
                            TICKS_PER_SECOND,
                    )
                },
        )
    }

    suspend fun loadMetadata(session: RelaySession): RelaySession =
        withContext(Dispatchers.IO) {
            val creds = credentialsStore.credentials.value ?: return@withContext session
            val id = session.itemId ?: return@withContext session
            val item =
                json
                    .parseToJsonElement(
                        get(creds, endpoint(creds, "Users", creds.userId, "Items", id))
                    )
                    .jsonObject
            session.copy(metadata = item)
        }

    suspend fun continueWatching(): WearNextUpState =
        withContext(Dispatchers.IO) {
            val creds = credentialsStore.credentials.value ?: return@withContext WearNextUpState()
            val url =
                endpoint(creds, "Users", creds.userId, "Items", "Resume")
                    .newBuilder()
                    .addQueryParameter("Limit", "10")
                    .addQueryParameter("MediaTypes", "Video")
                    .build()
            val items =
                json.parseToJsonElement(get(creds, url)).jsonObject["Items"]?.jsonArray.orEmpty()
            WearNextUpState(
                items.mapNotNull { raw ->
                    val item = raw as? JsonObject ?: return@mapNotNull null
                    val id = item.string("Id") ?: return@mapNotNull null
                    val userData = item["UserData"] as? JsonObject
                    WearNextUpItem(
                        id,
                        item.string("Name").orEmpty(),
                        seriesName = item.string("SeriesName"),
                        mediaType = item.string("Type") ?: "Movie",
                        durationSeconds =
                            (item["RunTimeTicks"]?.jsonPrimitive?.longOrNull ?: 0) /
                                TICKS_PER_SECOND,
                        playbackPositionSeconds =
                            (userData?.get("PlaybackPositionTicks")?.jsonPrimitive?.longOrNull
                                ?: 0) / TICKS_PER_SECOND,
                    )
                },
                System.currentTimeMillis(),
            )
        }

    suspend fun dispatch(
        sessionId: String,
        action: WearPlayerAction,
        supportsExtensions: Boolean = false,
    ): Result<String> = ioResult {
        val creds =
            credentialsStore.credentials.value ?: error("Sync your account from the paired phone")
        when (action) {
            WearPlayerAction.Play -> playstate(creds, sessionId, "Unpause")
            WearPlayerAction.Pause -> playstate(creds, sessionId, "Pause")
            WearPlayerAction.TogglePlayPause -> playstate(creds, sessionId, "PlayPause")
            is WearPlayerAction.SeekTo ->
                playstate(
                    creds,
                    sessionId,
                    "Seek",
                    action.positionSeconds.coerceAtLeast(0) * TICKS_PER_SECOND,
                )
            is WearPlayerAction.SeekForward ->
                seekBy(creds, sessionId, action.seconds.toLong(), supportsExtensions, action)
            is WearPlayerAction.SeekBackward ->
                seekBy(creds, sessionId, -action.seconds.toLong(), supportsExtensions, action)
            WearPlayerAction.NextEpisode -> command(creds, sessionId, "PlayNext")
            is WearPlayerAction.PlayMediaItem ->
                command(
                    creds,
                    sessionId,
                    "PlayMediaSource",
                    mapOf(
                        "ItemId" to action.itemId,
                        "StartPositionTicks" to
                            (action.startPositionMs.coerceAtLeast(0) * 10_000L).toString(),
                    ),
                )
            is WearPlayerAction.SelectAudioTrack ->
                command(
                    creds,
                    sessionId,
                    "SetAudioStreamIndex",
                    mapOf(
                        "Index" to
                            (action.index ?: error("Choose an available audio track")).toString()
                    ),
                )
            is WearPlayerAction.SelectSubtitleTrack ->
                command(
                    creds,
                    sessionId,
                    "SetSubtitleStreamIndex",
                    mapOf(
                        "Index" to
                            (action.index ?: error("Choose an available subtitle track")).toString()
                    ),
                )
            WearPlayerAction.DisableSubtitles ->
                command(creds, sessionId, "SetSubtitleStreamIndex", mapOf("Index" to "-1"))
            is WearPlayerAction.AdjustVolume -> {
                val level =
                    action.percentage
                        ?: run {
                            val session =
                                listControllableSessions().firstOrNull { it.sessionId == sessionId }
                                    ?: error("Selected player is unavailable")
                            session.volume + (action.delta ?: error("Missing volume"))
                        }
                command(
                    creds,
                    sessionId,
                    "SetVolume",
                    mapOf("Volume" to (level.coerceIn(0f, 1f) * 100).toInt().toString()),
                )
            }
            else -> {
                check(supportsExtensions) { "Update SpatialFin on this device to use this control" }
                val payload = WearProtocolCodec.encodeAction(action).decodeToString()
                command(
                    creds,
                    sessionId,
                    "PlayState",
                    mapOf(WearProtocolPaths.RELAY_ACTION_ARGUMENT to payload),
                )
            }
        }
        // A successful HTTP response confirms server acceptance, not execution on the player.
        "Request sent to selected player"
    }

    suspend fun dispatchVoice(sessionId: String, transcript: String): Result<String> = ioResult {
        val creds =
            credentialsStore.credentials.value ?: error("Sync your account from the paired phone")
        command(
            creds,
            sessionId,
            "PlayState",
            mapOf(WearProtocolPaths.RELAY_VOICE_ARGUMENT to transcript),
        )
        "Voice request sent to selected player"
    }

    private suspend fun seekBy(
        creds: WearCredentials,
        sessionId: String,
        delta: Long,
        extensions: Boolean,
        action: WearPlayerAction,
    ) {
        if (extensions) {
            command(
                creds,
                sessionId,
                "PlayState",
                mapOf(
                    WearProtocolPaths.RELAY_ACTION_ARGUMENT to
                        WearProtocolCodec.encodeAction(action).decodeToString()
                ),
            )
            return
        }
        val session =
            listControllableSessions().firstOrNull { it.sessionId == sessionId && it.hasPlayback }
                ?: error("Playback session is no longer available")
        val position =
            (session.positionSeconds + delta).coerceIn(
                0L,
                session.durationSeconds.takeIf { it > 0 } ?: Long.MAX_VALUE,
            )
        playstate(creds, sessionId, "Seek", position * TICKS_PER_SECOND)
    }

    private fun playstate(
        creds: WearCredentials,
        sessionId: String,
        command: String,
        ticks: Long? = null,
    ) {
        val url = endpoint(creds, "Sessions", sessionId, "Playing", command).newBuilder()
        ticks?.let { url.addQueryParameter("seekPositionTicks", it.toString()) }
        post(creds, url.build(), "", false)
    }

    private fun command(
        creds: WearCredentials,
        sessionId: String,
        name: String,
        arguments: Map<String, String> = emptyMap(),
    ) {
        val payload = buildJsonObject {
            put("Name", name)
            put("ControllingUserId", creds.userId)
            putJsonObject("Arguments") { arguments.forEach { (key, value) -> put(key, value) } }
        }
            .toString()
        post(creds, endpoint(creds, "Sessions", sessionId, "Command"), payload, true)
    }

    private fun post(creds: WearCredentials, url: HttpUrl, body: String, isJson: Boolean) {
        val request =
            authorized(creds, Request.Builder().url(url))
                .post(body.toRequestBody(if (isJson) JSON_MEDIA_TYPE else null))
                .build()
        client.newCall(request).execute().use { checkStatus(it.code) }
    }

    private fun get(creds: WearCredentials, url: HttpUrl): String =
        client.newCall(authorized(creds, Request.Builder().url(url)).build()).execute().use {
            checkStatus(it.code)
            it.body.string()
        }

    private fun checkStatus(code: Int) {
        if (code in 200..299) return
        throw RelayDiscoveryException(
            when (code) {
                401 -> "Jellyfin sign-in expired. Sync from your phone."
                403 -> "This Jellyfin account cannot control the selected device."
                else -> "Jellyfin returned HTTP $code. Retry when the server is available."
            }
        )
    }

    private fun endpoint(creds: WearCredentials, vararg segments: String): HttpUrl {
        val builder =
            creds.serverUrl.trimEnd('/').toHttpUrlOrNull()?.newBuilder()
                ?: throw RelayDiscoveryException("Invalid Jellyfin address. Sync from your phone.")
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun authorized(creds: WearCredentials, builder: Request.Builder) =
        builder.header(
            "Authorization",
            """MediaBrowser Client="SpatialFin Watch", Device="Wear OS", DeviceId="${credentialsStore.watchDeviceId}", Version="1", Token="${creds.accessToken}"""",
        )

    private fun toRelaySession(raw: JsonElement): RelaySession? = runCatching {
        val obj = raw.jsonObject
        if (obj["SupportsRemoteControl"]?.jsonPrimitive?.booleanOrNull != true) return null
        if (
            obj.string("Client")?.contains("SpatialFin", true) != true ||
                obj.string("Client")?.contains("Watch", true) == true
        )
            return null
        val item = obj["NowPlayingItem"] as? JsonObject
        val play = obj["PlayState"] as? JsonObject
        RelaySession(
            sessionId = obj.string("Id")?.takeIf { it.isNotBlank() } ?: return null,
            deviceId = obj.string("DeviceId").orEmpty(),
            deviceName = obj.string("DeviceName") ?: "SpatialFin",
            nowPlayingTitle = item?.string("Name"),
            positionSeconds =
                (play?.get("PositionTicks")?.jsonPrimitive?.longOrNull ?: 0) / TICKS_PER_SECOND,
            durationSeconds =
                (item?.get("RunTimeTicks")?.jsonPrimitive?.longOrNull ?: 0) / TICKS_PER_SECOND,
            isPaused = play?.get("IsPaused")?.jsonPrimitive?.booleanOrNull ?: true,
            itemId = item?.string("Id"),
            volume =
                (play?.get("VolumeLevel")?.jsonPrimitive?.intOrNull ?: 100).coerceIn(0, 100) / 100f,
            supportsWearCommands = supportsWearExtensions(obj.string("ApplicationVersion")),
            audioStreamIndex = play?.get("AudioStreamIndex")?.jsonPrimitive?.intOrNull,
            subtitleStreamIndex = play?.get("SubtitleStreamIndex")?.jsonPrimitive?.intOrNull,
            metadata = item,
        )
    }
        .getOrNull()

    private suspend fun <T> ioResult(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private companion object {
        const val TICKS_PER_SECOND = 10_000_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** First host version implementing authenticated Wear extensions. Unknown versions stay basic. */
internal fun supportsWearExtensions(version: String?): Boolean {
    val parts =
        version?.substringBefore('-')?.split('.')?.map { it.toIntOrNull() ?: return false }
            ?: return false
    if (parts.size < 3) return false
    return parts[0] > 2 || (parts[0] == 2 && (parts[1] > 7 || parts[1] == 7 && parts[2] >= 62))
}
