package dev.jdtech.jellyfin.film.domain

import dev.jdtech.jellyfin.models.AudioChannel
import dev.jdtech.jellyfin.models.DisplayProfile
import dev.jdtech.jellyfin.models.Resolution
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import java.util.Locale
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

/** What a hero fact chip is about, so each surface can pick its own icon. */
enum class HeroFactKind {
    CERTIFICATION,
    YEAR,
    RUNTIME,
    RATING,
    EPISODE,
    UNPLAYED,
}

/** One labelled chip in the detail hero's fact row. */
data class HeroFact(val kind: HeroFactKind, val label: String)

/**
 * Everything the detail hero shows above the overview, derived in one place so
 * the XR, Beam and TV heroes cannot drift apart.
 *
 * [video], [audio] and [subtitle] summarise the media streams of the source
 * that will actually play — the "4K DoVi/HDR10+", "ENG - 5.1" and "ENG" chips.
 * They are null when the item has no such stream (a series, a folder, an item
 * whose sources have not been fetched), and the caller simply omits the chip.
 */
data class DetailHeroMetadata(
    val facts: List<HeroFact> = emptyList(),
    val genres: List<String> = emptyList(),
    val video: String? = null,
    val audio: String? = null,
    val subtitle: String? = null,
)

/** Builds the hero metadata for [item]. Pure — safe to call during composition. */
fun SpatialFinItem.detailHeroMetadata(maxGenres: Int = 3): DetailHeroMetadata {
    val item = this
    val facts = buildList {
        when (item) {
            is SpatialFinMovie -> {
                item.officialRating?.takeIf { it.isNotBlank() }
                    ?.let { add(HeroFact(HeroFactKind.CERTIFICATION, it)) }
                item.productionYear?.let { add(HeroFact(HeroFactKind.YEAR, it.toString())) }
                formatRuntime(item.runtimeTicks)?.let { add(HeroFact(HeroFactKind.RUNTIME, it)) }
                formatRating(item.communityRating)?.let { add(HeroFact(HeroFactKind.RATING, it)) }
            }
            is SpatialFinShow -> {
                item.officialRating?.takeIf { it.isNotBlank() }
                    ?.let { add(HeroFact(HeroFactKind.CERTIFICATION, it)) }
                item.productionYear?.let { add(HeroFact(HeroFactKind.YEAR, it.toString())) }
                formatRuntime(item.runtimeTicks)?.let { add(HeroFact(HeroFactKind.RUNTIME, it)) }
                formatRating(item.communityRating)?.let { add(HeroFact(HeroFactKind.RATING, it)) }
                item.unplayedItemCount?.takeIf { it > 0 }
                    ?.let { add(HeroFact(HeroFactKind.UNPLAYED, "$it unwatched")) }
            }
            is SpatialFinEpisode -> {
                add(HeroFact(HeroFactKind.EPISODE, episodeLabel(item)))
                item.premiereDate?.year?.let { add(HeroFact(HeroFactKind.YEAR, it.toString())) }
                formatRuntime(item.runtimeTicks)?.let { add(HeroFact(HeroFactKind.RUNTIME, it)) }
                formatRating(item.communityRating)?.let { add(HeroFact(HeroFactKind.RATING, it)) }
            }
            is SpatialFinSeason -> {
                item.unplayedItemCount?.takeIf { it > 0 }
                    ?.let { add(HeroFact(HeroFactKind.UNPLAYED, "$it unwatched")) }
            }
            else -> Unit
        }
    }

    val genres =
        when (item) {
            is SpatialFinMovie -> item.genres
            is SpatialFinShow -> item.genres
            else -> emptyList()
        }.filter { it.isNotBlank() }.take(maxGenres)

    // The first source is the one playback defaults to; a version switch
    // reloads the screen with that version's item, so this stays in step.
    val streams = item.sources.firstOrNull()?.mediaStreams.orEmpty()

    return DetailHeroMetadata(
        facts = facts,
        genres = genres,
        video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.let(::videoLabel),
        audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.let(::audioLabel),
        subtitle = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE }?.let(::subtitleLabel),
    )
}

/** `1h 47m`, or `47m` under an hour. Null when the runtime is unknown. */
fun formatRuntime(runtimeTicks: Long): String? {
    val totalMinutes = runtimeTicks.takeIf { it > 0L }?.div(TICKS_PER_MINUTE) ?: return null
    if (totalMinutes <= 0L) return null
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0L) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Community rating trimmed to at most two decimals — `7.32`, `8.1`, `9`. */
fun formatRating(rating: Float?): String? {
    val value = rating?.takeIf { it > 0f } ?: return null
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}

/** `4K DoVi/HDR10+` — resolution plus every HDR flavour the stream carries. */
private fun videoLabel(stream: SpatialFinMediaStream): String? {
    val resolution = resolutionOf(stream)?.raw
    val profiles = buildList {
        // videoDoViTitle is set independently of videoRangeType on some servers,
        // so a file can be Dolby Vision *and* advertise an HDR10+ base layer —
        // Fladder shows both, and dropping either misrepresents the file.
        if (!stream.videoDoViTitle.isNullOrBlank() || stream.videoRangeType.isDolbyVision()) {
            add("DoVi")
        }
        when (stream.videoRangeType) {
            VideoRangeType.HDR10_PLUS,
            VideoRangeType.DOVI_WITH_ELHDR10_PLUS,
            VideoRangeType.DOVI_WITH_HDR10_PLUS -> add(DisplayProfile.HDR10_PLUS.raw)
            VideoRangeType.HDR10,
            VideoRangeType.DOVI_WITH_HDR10 -> add(DisplayProfile.HDR10.raw)
            VideoRangeType.HLG,
            VideoRangeType.DOVI_WITH_HLG -> add(DisplayProfile.HLG.raw)
            else -> Unit
        }
    }
    return listOfNotNull(resolution, profiles.joinToString("/").takeIf { it.isNotEmpty() })
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}

private fun resolutionOf(stream: SpatialFinMediaStream): Resolution? {
    val width = stream.width ?: return null
    val height = stream.height ?: return null
    return when {
        width >= 3200 || height >= 1800 -> Resolution.UHD
        width >= 1200 || height >= 900 -> Resolution.HD
        else -> Resolution.SD
    }
}

private fun VideoRangeType?.isDolbyVision(): Boolean =
    this == VideoRangeType.DOVI ||
        this == VideoRangeType.DOVI_WITH_EL ||
        this == VideoRangeType.DOVI_WITH_ELHDR10_PLUS ||
        this == VideoRangeType.DOVI_WITH_HDR10 ||
        this == VideoRangeType.DOVI_WITH_HDR10_PLUS ||
        this == VideoRangeType.DOVI_WITH_HLG ||
        this == VideoRangeType.DOVI_WITH_SDR

/** `ENG - 5.1`, falling back to the codec when the layout is unknown. */
private fun audioLabel(stream: SpatialFinMediaStream): String? {
    val language = languageLabel(stream)
    val channels = channelsOf(stream)
    return listOfNotNull(language, channels).joinToString(" - ").takeIf { it.isNotBlank() }
}

private fun channelsOf(stream: SpatialFinMediaStream): String? =
    when (stream.channelLayout) {
        AudioChannel.CH_7_1.raw -> AudioChannel.CH_7_1.raw
        AudioChannel.CH_5_1.raw -> AudioChannel.CH_5_1.raw
        AudioChannel.CH_2_1.raw -> AudioChannel.CH_2_1.raw
        "stereo" -> AudioChannel.CH_2_0.raw
        "mono" -> "1.0"
        else -> stream.channelLayout?.takeIf { it.isNotBlank() }
    }

private fun subtitleLabel(stream: SpatialFinMediaStream): String? = languageLabel(stream)

private fun languageLabel(stream: SpatialFinMediaStream): String? =
    stream.language
        .takeIf { it.isNotBlank() }
        ?.uppercase(Locale.US)
        ?: stream.displayTitle?.takeIf { it.isNotBlank() }?.substringBefore(' ')

private fun episodeLabel(episode: SpatialFinEpisode): String =
    "S${episode.parentIndexNumber}:E${episode.indexNumber}"

private const val TICKS_PER_MINUTE = 600_000_000L
