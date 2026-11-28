package dev.jdtech.jellyfin.cast.adapter.sendspinavr

import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrRequestValidator.ValidationResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pure-HTTP REST client for `spatialfin-avr` (`/health` and `/api/v1/` endpoints).
 *
 * Uses a caller-injected [OkHttpClient] (expected to carry an SSRF guard in production) and
 * forcefully disables HTTP redirects so `Authorization: Bearer` tokens can never follow a 3xx.
 */
class SendspinAvrHttpClient(okHttpClient: OkHttpClient) {

    data class Endpoint(
        val host: String,
        val port: Int = SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
        val useTls: Boolean = false,
        val roomId: String? = null,
        val tlsSpkiSha256: String? = null,
    ) {
        fun normalized(): Endpoint = parse(host, port, useTls).copy(
            roomId = roomId?.trim()?.takeIf { it.isNotEmpty() },
            tlsSpkiSha256 = tlsSpkiSha256?.trim()?.takeIf { it.isNotEmpty() },
        )

        fun baseHttpUrl(): String {
            val clean = normalized()
            val scheme = if (clean.useTls) "https" else "http"
            val formattedHost = formatHostForUrl(clean.host)
            return "$scheme://$formattedHost:${clean.port}"
        }

        fun baseWsUrl(): String {
            val clean = normalized()
            val scheme = if (clean.useTls) "wss" else "ws"
            val formattedHost = formatHostForUrl(clean.host)
            val base = "$scheme://$formattedHost:${clean.port}/ws/v1/control"
            val rid = clean.roomId
            return if (rid.isNullOrBlank()) {
                base
            } else {
                "$base?room_id=${java.net.URLEncoder.encode(rid, "UTF-8")}"
            }
        }

        companion object {
            /**
             * Gracefully parses user-entered host strings such as `192.168.1.90`,
             * `192.168.1.90:1986`, `http://192.168.1.90:1986`, `https://avr.local`, `[::1]:1986`,
             * or bare IPv6 `fe80::1` without misinterpreting `host:port` as an IPv6 literal.
             */
            fun parse(
                rawHost: String,
                fallbackPort: Int = SendspinAvrDiscoveryBrowser.SENDSPIN_AVR_DEFAULT_PORT,
                fallbackUseTls: Boolean = false,
            ): Endpoint {
                var text = rawHost.trim()
                var tls = fallbackUseTls
                when {
                    text.startsWith("https://", ignoreCase = true) -> {
                        tls = true
                        text = text.substring(8)
                    }
                    text.startsWith("http://", ignoreCase = true) -> {
                        tls = false
                        text = text.substring(7)
                    }
                    text.startsWith("wss://", ignoreCase = true) -> {
                        tls = true
                        text = text.substring(6)
                    }
                    text.startsWith("ws://", ignoreCase = true) -> {
                        tls = false
                        text = text.substring(5)
                    }
                }
                text = text.substringBefore('/').substringBefore('?').substringBefore('#').trim()

                var resolvedHost = text
                var resolvedPort = fallbackPort
                if (text.startsWith('[')) {
                    val closing = text.indexOf(']')
                    if (closing > 1) {
                        resolvedHost = text.substring(1, closing)
                        val tail = text.substring(closing + 1)
                        if (tail.startsWith(':')) {
                            tail.substring(1).toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                                resolvedPort = it
                            }
                        }
                    }
                } else {
                    val firstColon = text.indexOf(':')
                    val lastColon = text.lastIndexOf(':')
                    if (firstColon > 0 && firstColon == lastColon) {
                        val candidatePort = text.substring(lastColon + 1).toIntOrNull()
                        if (candidatePort != null && candidatePort in 1..65535) {
                            resolvedHost = text.substring(0, lastColon).trim()
                            resolvedPort = candidatePort
                        }
                    }
                }
                return Endpoint(host = resolvedHost, port = resolvedPort, useTls = tls)
            }

            private fun formatHostForUrl(host: String): String {
                val trimmed = host.trim().removePrefix("[").removeSuffix("]")
                return if (trimmed.count { it == ':' } >= 2) "[$trimmed]" else trimmed
            }
        }
    }

    sealed interface HttpResult<out T> {
        data class Success<T>(val value: T, val etag: String? = null) : HttpResult<T>
        data class Error(
            val httpStatus: Int,
            val errorCode: String,
            val message: String,
            val retryable: Boolean = false,
            val retryAfterSeconds: Long? = null,
            val details: JsonObject? = null,
        ) : HttpResult<Nothing>
    }

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val json = SendspinAvrJson.codec

    private fun requestBuilder(endpoint: Endpoint): Request.Builder =
        Request.Builder().tag(Endpoint::class.java, endpoint)

    suspend fun health(endpoint: Endpoint): HttpResult<HealthResponse> =
        executeJson(
            request = requestBuilder(endpoint).url("${endpoint.baseHttpUrl()}/health").get().build(),
            deserializer = HealthResponse.serializer(),
        )

    suspend fun pairClaim(endpoint: Endpoint, request: PairClaimRequest): HttpResult<PairClaimResponse> {
        val httpReq = runCatching {
            requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/pair/claim")
                .post(json.encodeToString(PairClaimRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.getOrElse { err ->
            return HttpResult.Error(
                httpStatus = 0,
                errorCode = "invalid_request",
                message = err.message ?: "Invalid server host or port.",
            )
        }
        return executeJson(
            request = httpReq,
            deserializer = PairClaimResponse.serializer(),
        )
    }

    suspend fun armPairPin(endpoint: Endpoint, bearerToken: String): HttpResult<PairPinResponse> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = requestBuilder(endpoint)
                    .url("${endpoint.baseHttpUrl()}/api/v1/pair/pin")
                    .header("Authorization", "Bearer $token")
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
                deserializer = PairPinResponse.serializer(),
            )
        }

    suspend fun revokeClient(
        endpoint: Endpoint,
        bearerToken: String,
        clientId: String,
        force: Boolean = false,
    ): HttpResult<ClientRevokeResponse> = withBearer(bearerToken) { token ->
        val query = if (force) "?force=true" else ""
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/clients/${encodePathSegment(clientId)}$query")
                .header("Authorization", "Bearer $token")
                .delete()
                .build(),
            deserializer = ClientRevokeResponse.serializer(),
        )
    }

    suspend fun updateRoomControls(
        endpoint: Endpoint,
        bearerToken: String,
        request: RoomControlsUpdateRequest,
    ): HttpResult<AvrStatusResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/room/controls")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(RoomControlsUpdateRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = AvrStatusResponse.serializer(),
        )
    }

    suspend fun getInfo(endpoint: Endpoint, bearerToken: String): HttpResult<HelloOkPayload> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = requestBuilder(endpoint)
                    .url("${endpoint.baseHttpUrl()}/api/v1/info")
                    .header("Authorization", "Bearer $token")
                    .withRoomHeader(endpoint)
                    .get()
                    .build(),
                deserializer = HelloOkPayload.serializer(),
            )
        }

    suspend fun getStatus(endpoint: Endpoint, bearerToken: String): HttpResult<AvrStatusResponse> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = requestBuilder(endpoint)
                    .url("${endpoint.baseHttpUrl()}/api/v1/status")
                    .header("Authorization", "Bearer $token")
                    .withRoomHeader(endpoint)
                    .get()
                    .build(),
                deserializer = AvrStatusResponse.serializer(),
            )
        }

    suspend fun getRoomProfile(endpoint: Endpoint, bearerToken: String): HttpResult<RoomProfileResponse> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = requestBuilder(endpoint)
                    .url("${endpoint.baseHttpUrl()}/api/v1/room/profile")
                    .header("Authorization", "Bearer $token")
                    .withRoomHeader(endpoint)
                    .get()
                    .build(),
                deserializer = RoomProfileResponse.serializer(),
            )
        }

    suspend fun putRoomProfile(
        endpoint: Endpoint,
        bearerToken: String,
        ifMatch: String?,
        profile: RoomProfileResponse,
    ): HttpResult<RoomProfileResponse> = withBearer(bearerToken) { token ->
        val builder = requestBuilder(endpoint)
            .url("${endpoint.baseHttpUrl()}/api/v1/room/profile")
            .header("Authorization", "Bearer $token")
            .withRoomHeader(endpoint)
            .put(json.encodeToString(RoomProfileResponse.serializer(), profile).toRequestBody(JSON_MEDIA_TYPE))
        if (!ifMatch.isNullOrBlank()) {
            builder.header("If-Match", ifMatch)
        }
        executeJson(builder.build(), RoomProfileResponse.serializer())
    }

    suspend fun playChime(
        endpoint: Endpoint,
        bearerToken: String,
        request: CalibrationChimeRequest,
    ): HttpResult<Unit> {
        val check = SendspinAvrRequestValidator.validateCalibrationChime(request)
        if (check is ValidationResult.Invalid) {
            return HttpResult.Error(httpStatus = 0, errorCode = check.errorCode, message = check.reason)
        }
        return withBearer(bearerToken) { token ->
            executeUnit(
                requestBuilder(endpoint)
                    .url("${endpoint.baseHttpUrl()}/api/v1/calibration/chime")
                    .header("Authorization", "Bearer $token")
                    .withRoomHeader(endpoint)
                    .post(json.encodeToString(CalibrationChimeRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                    .build(),
            )
        }
    }

    suspend fun playCenterSnap(
        endpoint: Endpoint,
        bearerToken: String,
        payload: CalibrationCenterSnapPayload,
    ): HttpResult<CalibrationCenterSnapOkPayload> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/center-snap")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(CalibrationCenterSnapPayload.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CalibrationCenterSnapOkPayload.serializer(),
        )
    }

    suspend fun startSweep(
        endpoint: Endpoint,
        bearerToken: String,
        request: CalibrationSweepStartRequest,
    ): HttpResult<CalibrationSweepStartResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/sweeps")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(CalibrationSweepStartRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CalibrationSweepStartResponse.serializer(),
        )
    }

    suspend fun abortSweep(
        endpoint: Endpoint,
        bearerToken: String,
        sweepId: String,
    ): HttpResult<CalibrationSweepAbortResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/sweeps/${encodePathSegment(sweepId)}")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .delete()
                .build(),
            deserializer = CalibrationSweepAbortResponse.serializer(),
        )
    }

    suspend fun uploadSweep(
        endpoint: Endpoint,
        bearerToken: String,
        sweepId: String,
        recordingWavFile: File,
        umikCalText: String? = null,
        metadata: CalibrationSweepUploadMetadata,
    ): HttpResult<CalibrationSweepUploadResponse> = withBearer(bearerToken) { token ->
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "recording_wav",
                recordingWavFile.name,
                recordingWavFile.asRequestBody(WAV_MEDIA_TYPE),
            )
            .apply {
                if (!umikCalText.isNullOrBlank()) {
                    addFormDataPart(
                        "umik_cal_txt",
                        "umik.txt",
                        umikCalText.toRequestBody(TEXT_MEDIA_TYPE),
                    )
                }
            }
            .addFormDataPart(
                "metadata_json",
                "metadata.json",
                json.encodeToString(CalibrationSweepUploadMetadata.serializer(), metadata)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/sweeps/${encodePathSegment(sweepId)}/upload")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(multipart)
                .build(),
            deserializer = CalibrationSweepUploadResponse.serializer(),
        )
    }

    suspend fun uploadBundle(
        endpoint: Endpoint,
        bearerToken: String,
        configYaml: String,
        firWavFiles: List<File> = emptyList(),
    ): HttpResult<CalibrationBundleResponse> = withBearer(bearerToken) { token ->
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("config_yaml", "camilladsp.yml", configYaml.toRequestBody(YAML_MEDIA_TYPE))
        for (wav in firWavFiles) {
            multipart.addFormDataPart("fir_wavs[]", wav.name, wav.asRequestBody(WAV_MEDIA_TYPE))
        }
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/bundles")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(multipart.build())
                .build(),
            deserializer = CalibrationBundleResponse.serializer(),
        )
    }

    suspend fun applyCalibration(
        endpoint: Endpoint,
        bearerToken: String,
        calibrationId: String,
    ): HttpResult<CalibrationApplyResponse> = withBearer(bearerToken) { token ->
        val body = CalibrationApplyRequest(calibrationId)
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/apply")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(CalibrationApplyRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CalibrationApplyResponse.serializer(),
        )
    }

    suspend fun revertCalibration(
        endpoint: Endpoint,
        bearerToken: String,
    ): HttpResult<CalibrationRevertResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/revert")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CalibrationRevertResponse.serializer(),
        )
    }

    suspend fun listSpeakers(
        endpoint: Endpoint,
        bearerToken: String,
    ): HttpResult<SpeakersListResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/speakers")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .get()
                .build(),
            deserializer = SpeakersListResponse.serializer(),
        )
    }

    suspend fun discoverSpeakers(
        endpoint: Endpoint,
        bearerToken: String,
    ): HttpResult<SpeakersDiscoverResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/speakers/discover")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .get()
                .build(),
            deserializer = SpeakersDiscoverResponse.serializer(),
        )
    }

    suspend fun pairSpeaker(
        endpoint: Endpoint,
        bearerToken: String,
        request: SpeakerPairRequest,
    ): HttpResult<SpeakerPairResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/speakers/pair")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(SpeakerPairRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = SpeakerPairResponse.serializer(),
        )
    }

    suspend fun unpairSpeaker(
        endpoint: Endpoint,
        bearerToken: String,
        clientId: String,
    ): HttpResult<SpeakerUnpairResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/speakers/${encodePathSegment(clientId)}")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .delete()
                .build(),
            deserializer = SpeakerUnpairResponse.serializer(),
        )
    }

    suspend fun probeSpeakerChime(
        endpoint: Endpoint,
        bearerToken: String,
        request: SpeakerProbeChimeRequest,
    ): HttpResult<Unit> = withBearer(bearerToken) { token ->
        executeUnit(
            requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/speakers/probe-chime")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(SpeakerProbeChimeRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    suspend fun listRooms(
        endpoint: Endpoint,
        bearerToken: String,
    ): HttpResult<RoomsListResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/rooms")
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
            deserializer = RoomsListResponse.serializer(),
        )
    }

    suspend fun createRoom(
        endpoint: Endpoint,
        bearerToken: String,
        roomName: String,
    ): HttpResult<RoomSummary> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/rooms")
                .header("Authorization", "Bearer $token")
                .post(json.encodeToString(CreateRoomRequest.serializer(), CreateRoomRequest(roomName)).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = RoomSummary.serializer(),
        )
    }

    suspend fun updateRoom(
        endpoint: Endpoint,
        bearerToken: String,
        roomId: String,
        request: UpdateRoomRequest,
    ): HttpResult<RoomSummary> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/rooms/${encodePathSegment(roomId)}")
                .header("Authorization", "Bearer $token")
                .patch(json.encodeToString(UpdateRoomRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = RoomSummary.serializer(),
        )
    }

    suspend fun deleteRoom(
        endpoint: Endpoint,
        bearerToken: String,
        roomId: String,
    ): HttpResult<DeleteRoomResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/rooms/${encodePathSegment(roomId)}")
                .header("Authorization", "Bearer $token")
                .delete()
                .build(),
            deserializer = DeleteRoomResponse.serializer(),
        )
    }

    suspend fun discoverLocalOutputs(
        endpoint: Endpoint,
        bearerToken: String,
    ): HttpResult<LocalOutputsDiscoverResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/outputs/local")
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
            deserializer = LocalOutputsDiscoverResponse.serializer(),
        )
    }

    suspend fun pairLocalOutput(
        endpoint: Endpoint,
        bearerToken: String,
        request: LocalOutputPairRequest,
    ): HttpResult<SpeakerPairResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/outputs/local/pair")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(LocalOutputPairRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = SpeakerPairResponse.serializer(),
        )
    }

    suspend fun probeLocalOutputChime(
        endpoint: Endpoint,
        bearerToken: String,
        request: LocalOutputProbeChimeRequest,
    ): HttpResult<Unit> = withBearer(bearerToken) { token ->
        executeUnit(
            requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/outputs/local/probe-chime")
                .header("Authorization", "Bearer $token")
                .post(json.encodeToString(LocalOutputProbeChimeRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
    }

    suspend fun provisionNoiseEndpoint(
        endpoint: Endpoint,
        bearerToken: String,
        request: NoiseEndpointProvisionRequest,
    ): HttpResult<NoiseEndpointProvisionResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = requestBuilder(endpoint)
                .url("${endpoint.baseHttpUrl()}/api/v1/outputs/noise/provision")
                .header("Authorization", "Bearer $token")
                .withRoomHeader(endpoint)
                .post(json.encodeToString(NoiseEndpointProvisionRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = NoiseEndpointProvisionResponse.serializer(),
        )
    }

    private fun Request.Builder.withRoomHeader(endpoint: Endpoint): Request.Builder {
        val rid = endpoint.roomId?.trim()
        if (!rid.isNullOrEmpty()) {
            header("X-Avr-Room-Id", rid)
        }
        return this
    }

    private suspend inline fun <T> withBearer(
        bearerToken: String,
        crossinline block: suspend (String) -> HttpResult<T>,
    ): HttpResult<T> {
        val tokenCheck = SendspinAvrRequestValidator.validateToken(bearerToken, "avr_token")
        if (tokenCheck is ValidationResult.Invalid) {
            return HttpResult.Error(httpStatus = 0, errorCode = tokenCheck.errorCode, message = tokenCheck.reason)
        }
        return block(bearerToken)
    }

    private fun clientFor(endpoint: Endpoint?): OkHttpClient {
        val spki = endpoint?.tlsSpkiSha256?.trim()?.takeIf { it.isNotEmpty() }
        return if (endpoint != null && endpoint.useTls && spki != null) {
            SendspinAvrTlsPinning.createPinnedClient(client, spki)
        } else {
            client
        }
    }

    private suspend fun <T> executeJson(
        request: Request,
        deserializer: KSerializer<T>,
        endpoint: Endpoint? = null,
    ): HttpResult<T> = executeRaw(request, endpoint) { body ->
        json.decodeFromString(deserializer, body)
    }

    private suspend fun executeUnit(
        request: Request,
        endpoint: Endpoint? = null,
    ): HttpResult<Unit> = executeRaw(request, endpoint) { }

    private suspend fun <T> executeRaw(
        request: Request,
        endpoint: Endpoint? = null,
        parser: (String) -> T,
    ): HttpResult<T> = withContext(Dispatchers.IO) {
        try {
            val activeClient = clientFor(endpoint ?: request.tag(Endpoint::class.java))
            awaitCall(activeClient.newCall(request)).use { response ->
                val rawBody = response.body.string()
                if (response.isSuccessful) {
                    val value = parser(rawBody)
                    HttpResult.Success(value = value, etag = response.header("ETag"))
                } else {
                    parseHttpError(response, rawBody)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SendspinAvrTlsPinning.isPinMismatch(e)) {
                HttpResult.Error(
                    httpStatus = 0,
                    errorCode = "tls_pin_mismatch",
                    message = e.message ?: "Theater AVR TLS certificate pin mismatch",
                    retryable = false,
                )
            } else {
                HttpResult.Error(
                    httpStatus = 0,
                    errorCode = "internal_error",
                    message = e.message ?: "HTTP transport failure",
                    retryable = true,
                )
            }
        }
    }

    private fun parseHttpError(response: Response, rawBody: String): HttpResult.Error {
        val retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
        val parsed = runCatching {
            if (rawBody.isNotBlank()) json.decodeFromString(HttpErrorResponse.serializer(), rawBody) else null
        }.getOrNull()
        return if (parsed != null) {
            HttpResult.Error(
                httpStatus = response.code,
                errorCode = parsed.errorCode,
                message = parsed.message,
                retryable = parsed.retryable,
                retryAfterSeconds = retryAfterSeconds,
                details = parsed.details,
            )
        } else {
            HttpResult.Error(
                httpStatus = response.code,
                errorCode = if (response.isRedirect) "url_rejected" else "internal_error",
                message = rawBody.ifBlank { "HTTP ${response.code} ${response.message}" },
                retryable = response.code in setOf(408, 429, 500, 502, 503, 504),
                retryAfterSeconds = retryAfterSeconds,
            )
        }
    }

    private suspend fun awaitCall(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private fun encodePathSegment(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
        val YAML_MEDIA_TYPE = "application/yaml".toMediaType()
    }
}
