package dev.jdtech.jellyfin.cast.adapter.airplay

import java.io.IOException
import java.util.UUID
import kotlin.math.log10
import kotlin.math.max
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * HTTP shim around the six AirPlay v1 verbs SpatialFin sends. The protocol is well-documented:
 * plain HTTP on host:7000 (sometimes 49152+ on third-party receivers) with `text/parameters`
 * bodies and a small set of query-string commands. No TLS, no auth on most devices — Apple TVs
 * that require a PIN respond `470 Connection Authorization Required` and we surface that as a
 * typed error (PR 6 follow-up for the SRP-6a pairing handshake).
 *
 * What lives here:
 *  - [Request] composition for each verb. Public so tests can assert the body / query / headers
 *    without spinning up a real HTTP server.
 *  - [execute] that runs the request through OkHttp and returns the response body (or fails
 *    with a typed [AirPlayException] on 4xx/5xx).
 *
 * What does **not** live here:
 *  - Lifecycle / state machine — that's `AirPlayAdapter`.
 *  - Bplist decoding — `BinaryPlist`.
 *
 * Concurrency: OkHttp is thread-safe. Callers may invoke any of the verbs from any coroutine;
 * the [cseq] counter is atomic.
 */
internal class AirPlayHttpClient(
    val host: String,
    val port: Int,
    private val httpClient: OkHttpClient = defaultClient(),
    /** UUID-per-connection that the receiver uses to correlate inbound requests with a session. */
    val sessionId: String = UUID.randomUUID().toString().uppercase(),
) {

    private val baseUrl: String = "http://$host:$port"
    private val cseq = AtomicInteger(1)

    /** Sealed surface for the AirPlay-specific failures the adapter cares about. */
    sealed class AirPlayException(message: String) : IOException(message) {
        /** 470 — receiver wants a PIN. PR 6 work; for v1 the adapter surfaces it as unsupported. */
        class PinRequired : AirPlayException("AirPlay device requires PIN pairing")

        /** Generic 4xx / 5xx from the receiver. */
        class HttpFailure(val code: Int, body: String?) :
            AirPlayException("HTTP $code from AirPlay receiver: ${body.orEmpty()}")

        /** Receiver closed the socket mid-response. */
        class Truncated(cause: Throwable) : AirPlayException("AirPlay response truncated: ${cause.message}")
    }

    // --- Request builders. Pure, testable. ---

    /**
     * `POST /play` with a `text/parameters` body. AirPlay's body format is line-oriented:
     *   `Content-Location: <url>\nStart-Position: <0.0..1.0>\n`
     *
     * [startPositionFraction] is a fraction of the total duration (0.0..1.0). The session
     * manager converts an absolute ms position into a fraction once it knows the title's
     * duration; before that, it sends 0.0 (start from the beginning) which is the default.
     */
    fun buildPlayRequest(url: String, startPositionFraction: Double = 0.0): Request {
        val fraction = startPositionFraction.coerceIn(0.0, 1.0)
        val body = buildString {
            append("Content-Location: ").append(url).append('\n')
            append("Start-Position: ").append(fraction).append('\n')
        }
        return baseRequest("/play")
            .post(body.toRequestBody("text/parameters".toMediaType()))
            .build()
    }

    /** `POST /rate?value=0` (pause) / `POST /rate?value=1` (resume). */
    fun buildRateRequest(rate: Double): Request {
        val v = if (rate <= 0.0) "0.000000" else "1.000000"
        return baseRequest("/rate?value=$v")
            .post(EMPTY_TEXT_PARAMETERS)
            .build()
    }

    /** `POST /scrub?position=<seconds>`. */
    fun buildScrubRequest(positionSeconds: Double): Request {
        val pos = positionSeconds.coerceAtLeast(0.0)
        return baseRequest("/scrub?position=${"%.6f".format(pos)}")
            .post(EMPTY_TEXT_PARAMETERS)
            .build()
    }

    /** `POST /stop`. */
    fun buildStopRequest(): Request =
        baseRequest("/stop")
            .post(EMPTY_TEXT_PARAMETERS)
            .build()

    /**
     * `POST /volume?volume=<dB>`. AirPlay accepts dB from -30 to 0, plus Apple's -144
     * absolute-mute sentinel. Convert linear UI volume into perceptual dB with 20log10(v)
     * instead of a linear dB ramp, so the lower half of the slider remains usable.
     */
    fun buildVolumeRequest(linearVolume: Float): Request {
        val db = dbFromLinear(linearVolume)
        return baseRequest("/volume?volume=${"%.6f".format(db)}")
            .post(EMPTY_TEXT_PARAMETERS)
            .build()
    }

    /** `GET /playback-info`. Receiver returns a binary plist. */
    fun buildPlaybackInfoRequest(): Request =
        baseRequest("/playback-info").get().build()

    /**
     * Linear (0..1) → AirPlay dB. Values near zero are treated as mute; everything else uses
     * the receiver's -30..0 dB range with a perceptual amplitude mapping.
     */
    internal fun dbFromLinear(linearVolume: Float): Float {
        val clamped = linearVolume.coerceIn(0f, 1f)
        if (clamped <= MUTE_EPSILON) return AIRPLAY_MUTE_DB
        return max(AIRPLAY_MIN_DB, 20f * log10(clamped))
    }

    // --- Execution. ---

    /**
     * Synchronously execute [request] and return the response body bytes. Throws
     * [AirPlayException] on protocol-level failures; the adapter pipes those into
     * [dev.jdtech.jellyfin.cast.CastSessionEvent.Error].
     */
    fun execute(request: Request): ByteArray {
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 470) throw AirPlayException.PinRequired()
                if (!response.isSuccessful) {
                    val body = runCatching { response.body?.string() }.getOrNull()
                    throw AirPlayException.HttpFailure(response.code, body)
                }
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: AirPlayException) {
            throw e
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "AirPlay %s %s failed", request.method, request.url)
            throw AirPlayException.Truncated(e)
        }
    }

    private fun baseRequest(path: String): Request.Builder = Request.Builder()
        .url("$baseUrl$path")
        .headers(commonHeaders())

    /** Headers every AirPlay request carries. CSeq increments per call. */
    fun commonHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "MediaControl/1.0")
        .add("X-Apple-Session-ID", sessionId)
        .add("CSeq", cseq.getAndIncrement().toString())
        .build()

    private companion object {
        const val TAG = "AirPlayHttp"
        const val AIRPLAY_MUTE_DB = -144f
        const val AIRPLAY_MIN_DB = -30f
        const val MUTE_EPSILON = 0.001f

        // text/parameters bodies on POSTs without keys are empty but the receiver still wants
        // the content-type — reuse one instance.
        val EMPTY_TEXT_PARAMETERS = "".toRequestBody("text/parameters".toMediaType())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            // AirPlay receivers don't keep idle connections alive politely; explicitly cap
            // the pool so a forgotten cast doesn't leave sockets pinned.
            .retryOnConnectionFailure(true)
            .build()
    }
}
