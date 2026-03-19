package dev.jdtech.jellyfin.models

import java.util.Locale
import timber.log.Timber

fun List<SpatialFinItem>.deduplicateMovieVersions(): List<SpatialFinItem> {
    return this
        .groupBy { item -> item.movieVersionGroupKey() ?: item.id.toString() }
        .values
        .map { groupedItems ->
            groupedItems
                .sortedWith(compareByDescending<SpatialFinItem> { it is SpatialFinMovie && it.playbackPositionTicks > 0L }
                    .thenByDescending { it is SpatialFinMovie && !it.played }
                    .thenByDescending { it.sources.size })
                .first()
        }
}

fun SpatialFinMovie.versionOptionsFrom(candidates: List<SpatialFinMovie>): List<SpatialFinMovie> {
    val versions = (candidates + this)
        .distinctBy { it.id }
        .filter { it.isSameMovieVersionOf(this) }
        .sortedWith(compareBy<SpatialFinMovie> { it.versionSortRank() }.thenBy { it.id.toString() })
    if (versions.size > 1) {
        Timber.d(
            "Resolved %d movie versions for %s: %s",
            versions.size,
            name,
            versions.joinToString { it.versionChipLabel() },
        )
    }
    return versions
}

fun SpatialFinMovie.versionChipLabel(): String {
    val stereoMode = detectMovieStereoMode(name, video3DFormat, sources.flatMap { listOf(it.name, it.path) })
    val stereoLabel =
        when (stereoMode) {
            MovieStereoMode.SIDE_BY_SIDE -> "3D SBS"
            MovieStereoMode.TOP_BOTTOM -> "3D T/B"
            MovieStereoMode.MULTIVIEW -> "Spatial"
            else -> "2D"
        }
    val qualityLabel = sources.firstNotNullOfOrNull { source -> source.qualityTag() }
    return qualityLabel?.let { "$stereoLabel $it" } ?: stereoLabel
}

fun SpatialFinItem.movieVersionGroupKey(): String? {
    val title = canonicalMovieTitle() ?: return null
    val year = inferredMovieYear()
    return buildString {
        append(title)
        append('|')
        append(year ?: "unknown")
    }
}

fun SpatialFinMovie.isSameMovieVersionOf(other: SpatialFinMovie): Boolean {
    val sameTitle = canonicalMovieTitle() == other.canonicalMovieTitle()
    val year = productionYear ?: premiereDate?.year
    val otherYear = other.productionYear ?: other.premiereDate?.year
    val sameYear = year != null && year == otherYear
    val runtimeDelta = kotlin.math.abs(runtimeTicks - other.runtimeTicks)
    val sameRuntime = runtimeDelta <= 10 * 60 * 10_000_000L
    return sameTitle && (sameYear || sameRuntime)
}

private fun SpatialFinMovie.versionSortRank(): Int {
    return when (detectMovieStereoMode(name, video3DFormat, sources.flatMap { listOf(it.name, it.path) })) {
        MovieStereoMode.MONO -> 0
        MovieStereoMode.SIDE_BY_SIDE -> 1
        MovieStereoMode.TOP_BOTTOM -> 2
        MovieStereoMode.MULTIVIEW -> 3
    }
}

private fun SpatialFinItem.canonicalMovieTitle(): String? {
    val baseTitle =
        when (this) {
            is SpatialFinMovie -> originalTitle?.takeIf { it.isNotBlank() } ?: name
            is LocalVideoItem -> originalTitle?.takeIf { it.isNotBlank() } ?: name.ifBlank { fileName }
            else -> null
        } ?: return null
    return baseTitle
        .lowercase(Locale.US)
        .replace(Regex("""\([^)]*\)"""), " ")
        .replace(Regex("""\[[^]]*]"""), " ")
        .replace(Regex("""\{[^}]*\}"""), " ")
        .replace(Regex("""\b(3d|sbs|hsbs|fsbs|tab|tb|top.?bottom|ou|over.?under|half.?sbs|full.?sbs|mv-hevc|mvhevc|spatial|spatial.video|4k|uhd|2160p|1080p|720p|bluray|blu.?ray|remux|hevc|x264|x265|av1|hdr10|hdr|dv|dovi|dolby.?vision)\b"""), " ")
        .replace(Regex("""\b(3d|sbs|hsbs|fsbs|tab|tb|top.?bottom|half.?sbs|full.?sbs)\b"""), " ")
        .replace(Regex("""\.(mkv|mp4|avi|mov|m4v)$"""), " ")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
}

private fun SpatialFinItem.inferredMovieYear(): Int? {
    val explicitYear =
        when (this) {
            is SpatialFinMovie -> productionYear ?: premiereDate?.year
            is LocalVideoItem -> productionYear
            else -> null
        }
    if (explicitYear != null) return explicitYear

    val candidate =
        when (this) {
            is SpatialFinMovie -> listOfNotNull(originalTitle, name)
            is LocalVideoItem -> listOfNotNull(originalTitle, name, fileName)
            else -> emptyList()
        }.joinToString(" ")

    return YEAR_REGEX.find(candidate)?.value?.toIntOrNull()
}

private fun SpatialFinSource.qualityTag(): String? {
    val value = listOf(name, path)
        .firstNotNullOfOrNull { candidate ->
            QUALITY_TAG_REGEX.find(candidate.lowercase(Locale.US))?.groupValues?.get(1)
        }
        ?.uppercase(Locale.US)

    return when (value) {
        "4K" -> "4K"
        "2160P" -> "2160P"
        "1080P" -> "1080P"
        "720P" -> "720P"
        else -> null
    }
}

private val QUALITY_TAG_REGEX = Regex("""(4k|2160p|1080p|720p)""")

private enum class MovieStereoMode {
    MONO,
    SIDE_BY_SIDE,
    TOP_BOTTOM,
    MULTIVIEW,
}

private fun detectMovieStereoMode(
    movieName: String,
    video3DFormat: String?,
    sourceNames: List<String>,
): MovieStereoMode {
    val haystacks = buildList {
        add(video3DFormat.orEmpty())
        add(movieName)
        addAll(sourceNames)
    }.joinToString(" ").lowercase(Locale.US)

    return when {
        MULTIVIEW_REGEX.containsMatchIn(haystacks) -> MovieStereoMode.MULTIVIEW
        TOP_BOTTOM_REGEX.containsMatchIn(haystacks) -> MovieStereoMode.TOP_BOTTOM
        SIDE_BY_SIDE_REGEX.containsMatchIn(haystacks) -> MovieStereoMode.SIDE_BY_SIDE
        GENERIC_3D_REGEX.containsMatchIn(haystacks) -> MovieStereoMode.SIDE_BY_SIDE
        else -> MovieStereoMode.MONO
    }
}

private val MULTIVIEW_REGEX =
    Regex("""\b(mv-hevc|mvhevc|spatial|spatial[\s.-]?video|mvc)\b""")
private val SIDE_BY_SIDE_REGEX =
    Regex("""\b(hsbs|half[\s.-]?sbs|fsbs|full[\s.-]?sbs|sbs|side[\s.-]?by[\s.-]?side|3d[\s.-]?h?sbs)\b""")
private val TOP_BOTTOM_REGEX =
    Regex("""\b(tab|tb|top[\s.-]?bottom|top[\s.-]?and[\s.-]?bottom|ou|over[\s.-]?under|3d[\s.-]?(tab|tb|ou))\b""")
private val GENERIC_3D_REGEX = Regex("""\b3d\b""")
private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
