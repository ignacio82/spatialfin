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
    ) {
        fun baseHttpUrl(): String {
            val scheme = if (useTls) "https" else "http"
            val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "$scheme://$formattedHost:$port"
        }

        fun baseWsUrl(): String {
            val scheme = if (useTls) "wss" else "ws"
            val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "$scheme://$formattedHost:$port/ws/v1/control"
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

    suspend fun health(endpoint: Endpoint): HttpResult<HealthResponse> =
        executeJson(
            request = Request.Builder().url("${endpoint.baseHttpUrl()}/health").get().build(),
            deserializer = HealthResponse.serializer(),
        )

    suspend fun pairClaim(endpoint: Endpoint, request: PairClaimRequest): HttpResult<PairClaimResponse> =
        executeJson(
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/pair/claim")
                .post(json.encodeToString(PairClaimRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = PairClaimResponse.serializer(),
        )

    suspend fun armPairPin(endpoint: Endpoint, bearerToken: String): HttpResult<PairPinResponse> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = Request.Builder()
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
    ): HttpResult<ClientRevokeResponse> = withBearer(bearerToken) { token ->
        executeJson(
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/clients/${encodePathSegment(clientId)}")
                .header("Authorization", "Bearer $token")
                .delete()
                .build(),
            deserializer = ClientRevokeResponse.serializer(),
        )
    }

    suspend fun getInfo(endpoint: Endpoint, bearerToken: String): HttpResult<HelloOkPayload> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = Request.Builder()
                    .url("${endpoint.baseHttpUrl()}/api/v1/info")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
                deserializer = HelloOkPayload.serializer(),
            )
        }

    suspend fun getStatus(endpoint: Endpoint, bearerToken: String): HttpResult<AvrStatusResponse> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = Request.Builder()
                    .url("${endpoint.baseHttpUrl()}/api/v1/status")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build(),
                deserializer = AvrStatusResponse.serializer(),
            )
        }

    suspend fun getRoomProfile(endpoint: Endpoint, bearerToken: String): HttpResult<RoomProfileResponse> =
        withBearer(bearerToken) { token ->
            executeJson(
                request = Request.Builder()
                    .url("${endpoint.baseHttpUrl()}/api/v1/room/profile")
                    .header("Authorization", "Bearer $token")
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
        val builder = Request.Builder()
            .url("${endpoint.baseHttpUrl()}/api/v1/room/profile")
            .header("Authorization", "Bearer $token")
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
                Request.Builder()
                    .url("${endpoint.baseHttpUrl()}/api/v1/calibration/chime")
                    .header("Authorization", "Bearer $token")
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
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/center-snap")
                .header("Authorization", "Bearer $token")
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
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/sweeps")
                .header("Authorization", "Bearer $token")
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
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/sweeps/${encodePathSegment(sweepId)}")
                .header("Authorization", "Bearer $token")
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
        umikCalText: String,
        metadata: CalibrationSweepUploadMetadata,
    ): HttpResult<CalibrationSweepUploadResponse> = withBearer(bearerToken) { token ->
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "recording_wav",
                recordingWavFile.name,
                recordingWavFile.asRequestBody(WAV_MEDIA_TYPE),
            )
            .addFormDataPart(
                "umik_cal_txt",
                "umik.txt",
                umikCalText.toRequestBody(TEXT_MEDIA_TYPE),
            )
            .addFormDataPart(
                "metadata_json",
                "metadata.json",
                json.encodeToString(CalibrationSweepUploadMetadata.serializer(), metadata)
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()
        executeJson(
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/sweeps/${encodePathSegment(sweepId)}/upload")
                .header("Authorization", "Bearer $token")
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
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/bundles")
                .header("Authorization", "Bearer $token")
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
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/apply")
                .header("Authorization", "Bearer $token")
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
            request = Request.Builder()
                .url("${endpoint.baseHttpUrl()}/api/v1/calibration/revert")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            deserializer = CalibrationRevertResponse.serializer(),
        )
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

    private suspend fun <T> executeJson(
        request: Request,
        deserializer: KSerializer<T>,
    ): HttpResult<T> = executeRaw(request) { body ->
        json.decodeFromString(deserializer, body)
    }

    private suspend fun executeUnit(request: Request): HttpResult<Unit> = executeRaw(request) { }

    private suspend fun <T> executeRaw(
        request: Request,
        parser: (String) -> T,
    ): HttpResult<T> = withContext(Dispatchers.IO) {
        try {
            awaitCall(client.newCall(request)).use { response ->
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
            HttpResult.Error(
                httpStatus = 0,
                errorCode = "internal_error",
                message = e.message ?: "HTTP transport failure",
                retryable = true,
            )
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
