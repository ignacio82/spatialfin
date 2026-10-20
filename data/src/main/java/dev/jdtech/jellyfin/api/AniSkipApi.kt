package dev.jdtech.jellyfin.api

import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@Serializable
data class AniSkipResponse(
    val found: Boolean = false,
    val results: List<AniSkipResult> = emptyList(),
)

@Serializable
data class AniSkipResult(
    val interval: AniSkipInterval,
    val skipType: String,
    val episodeLength: Double = 0.0,
)

@Serializable
data class AniSkipInterval(
    val startTime: Double,
    val endTime: Double,
)

/**
 * Client for AniSkip crowdsourced anime opening and ending skip timestamps.
 * Queries https://api.aniskip.com/v2/skip-times/{malId}/{episodeNumber}.
 */
class AniSkipApi(
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getSkipTimes(
        malId: Long,
        episodeNumber: Int,
        episodeLengthSeconds: Long = 0L,
    ): List<SpatialFinSegment> = withContext(Dispatchers.IO) {
        val url = StringBuilder("https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber")
            .append("?types=op&types=ed&types=mixed-op&types=mixed-ed&types=recap")
        if (episodeLengthSeconds > 0) {
            url.append("&episodeLength=$episodeLengthSeconds")
        }

        val request = Request.Builder()
            .url(url.toString())
            .header("User-Agent", "SpatialFin")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code != 404) {
                        Timber.d("AniSkip returned HTTP %d for malId=%d ep=%d", response.code, malId, episodeNumber)
                    }
                    return@withContext emptyList()
                }

                val body = response.body.string()
                val parsed = json.decodeFromString<AniSkipResponse>(body)
                if (!parsed.found || parsed.results.isEmpty()) return@withContext emptyList()

                val segments = mutableListOf<SpatialFinSegment>()
                for (result in parsed.results) {
                    val startMs = (result.interval.startTime * 1000).toLong()
                    val endMs = (result.interval.endTime * 1000).toLong()
                    if (endMs <= startMs) continue

                    val type = when (result.skipType.lowercase()) {
                        "op", "mixed-op" -> SpatialFinSegmentType.INTRO
                        "ed", "mixed-ed" -> SpatialFinSegmentType.OUTRO
                        "recap" -> SpatialFinSegmentType.RECAP
                        else -> null
                    } ?: continue

                    segments.add(
                        SpatialFinSegment(
                            type = type,
                            startTicks = startMs,
                            endTicks = endMs,
                        ),
                    )
                }
                segments
            }
        } catch (e: Exception) {
            Timber.d(e, "AniSkip lookup failed for malId=%d ep=%d", malId, episodeNumber)
            emptyList()
        }
    }
}
