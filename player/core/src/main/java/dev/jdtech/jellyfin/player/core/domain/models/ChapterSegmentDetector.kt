package dev.jdtech.jellyfin.player.core.domain.models

import dev.jdtech.jellyfin.models.SpatialFinSegment
import dev.jdtech.jellyfin.models.SpatialFinSegmentType
import timber.log.Timber
import java.util.Locale

/**
 * Heuristic detector that converts embedded video chapter markers (from MKV/MP4 files or
 * Jellyfin metadata) into [SpatialFinSegment] objects when server-side segments are unavailable.
 *
 * Implements strict duration and position guards to prevent false positives (e.g. documentary acts
 * or lecture sections titled "Introduction").
 */
object ChapterSegmentDetector {

    // Regex to strip prefixes like "Chapter 1 - ", "02. ", "[01] ", etc.
    private val CHAPTER_PREFIX_REGEX = Regex(
        """^(?:chapter\s*\d+|\d+)\s*[-.:]?\s*""",
        RegexOption.IGNORE_CASE,
    )

    // Regex to strip trailing parenthetical notes like "(Creditless)", "[TV Version]", etc.
    private val CHAPTER_SUFFIX_REGEX = Regex(
        """\s*[\(\[].*?[\)\]]\s*$""",
    )

    private val INTRO_PATTERNS = listOf(
        Regex("""^intro(duction)?$""", RegexOption.IGNORE_CASE),
        Regex("""^opening(\s*(credits|theme))?$""", RegexOption.IGNORE_CASE),
        Regex("""^op(\s*\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^ncop(\s*\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^main\s+titles?(\s+sequence)?$""", RegexOption.IGNORE_CASE),
        Regex("""^title\s+sequence$""", RegexOption.IGNORE_CASE),
        Regex("""^theme(\s+song)?$""", RegexOption.IGNORE_CASE),
    )

    private val OUTRO_PATTERNS = listOf(
        Regex("""^outro$""", RegexOption.IGNORE_CASE),
        Regex("""^ending(\s*(credits|theme))?$""", RegexOption.IGNORE_CASE),
        Regex("""^ed(\s*\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^nced(\s*\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(end|closing)\s+credits$""", RegexOption.IGNORE_CASE),
        Regex("""^credits$""", RegexOption.IGNORE_CASE),
        Regex("""^closing$""", RegexOption.IGNORE_CASE),
    )

    private val RECAP_PATTERNS = listOf(
        Regex("""^recap$""", RegexOption.IGNORE_CASE),
        Regex("""^previously(\s+on(\s+.*)?)?$""", RegexOption.IGNORE_CASE),
        Regex("""^the\s+story\s+so\s+far$""", RegexOption.IGNORE_CASE),
    )

    private val PREVIEW_PATTERNS = listOf(
        Regex("""^preview$""", RegexOption.IGNORE_CASE),
        Regex("""^next\s+time(\s+on(\s+.*)?)?$""", RegexOption.IGNORE_CASE),
        Regex("""^next\s+episode\s+preview$""", RegexOption.IGNORE_CASE),
        Regex("""^teaser$""", RegexOption.IGNORE_CASE),
    )

    // Bounds constants
    private const val MIN_SEGMENT_DURATION_MS = 8_000L // At least 8 seconds
    private const val MAX_INTRO_DURATION_MS = 300_000L // At most 5 minutes
    private const val MAX_OUTRO_DURATION_MS = 600_000L // At most 10 minutes
    private const val MAX_RECAP_DURATION_MS = 300_000L // At most 5 minutes
    private const val MAX_PREVIEW_DURATION_MS = 180_000L // At most 3 minutes

    private const val MAX_INTRO_START_FRACTION = 0.40f // Intro must start in first 40%
    private const val MIN_OUTRO_START_FRACTION = 0.50f // Outro must start in second half

    /**
     * Inspects a list of [PlayerChapter]s and extracts skippable [SpatialFinSegment]s.
     *
     * @param chapters Ordered or unordered list of chapters.
     * @param durationMs Total duration of the media in milliseconds (0 if unknown).
     * @return List of valid detected segments.
     */
    fun detectSegments(
        chapters: List<PlayerChapter>,
        durationMs: Long,
    ): List<SpatialFinSegment> {
        if (chapters.isEmpty()) return emptyList()

        val sorted = chapters.sortedBy { it.startPosition }
        val foundTypes = mutableSetOf<SpatialFinSegmentType>()
        val detectedSegments = mutableListOf<SpatialFinSegment>()

        for (i in sorted.indices) {
            val current = sorted[i]
            val rawName = current.name?.trim() ?: continue
            val startMs = current.startPosition
            val endMs = if (i + 1 < sorted.size) {
                sorted[i + 1].startPosition
            } else if (durationMs > startMs) {
                durationMs
            } else {
                continue
            }

            val segmentDuration = endMs - startMs
            if (segmentDuration < MIN_SEGMENT_DURATION_MS) continue

            val normalizedName = normalizeChapterName(rawName)
            val segmentType = classifyChapter(
                normalizedName = normalizedName,
                startMs = startMs,
                segmentDurationMs = segmentDuration,
                totalDurationMs = durationMs,
            ) ?: continue

            // Ensure we don't produce duplicate segments of the same type
            if (foundTypes.add(segmentType)) {
                Timber.d(
                    "ChapterSegmentDetector: detected %s from '%s' (%d-%d ms)",
                    segmentType,
                    rawName,
                    startMs,
                    endMs,
                )
                detectedSegments.add(
                    SpatialFinSegment(
                        type = segmentType,
                        startTicks = startMs,
                        endTicks = endMs,
                    ),
                )
            }
        }

        return detectedSegments
    }

    internal fun normalizeChapterName(raw: String): String {
        var cleaned = raw.trim()
        cleaned = CHAPTER_PREFIX_REGEX.replace(cleaned, "")
        cleaned = CHAPTER_SUFFIX_REGEX.replace(cleaned, "")
        return cleaned.trim().lowercase(Locale.ROOT)
    }

    internal fun classifyChapter(
        normalizedName: String,
        startMs: Long,
        segmentDurationMs: Long,
        totalDurationMs: Long,
    ): SpatialFinSegmentType? {
        val hasDuration = totalDurationMs > 0

        // 1. INTRO
        if (INTRO_PATTERNS.any { it.matches(normalizedName) }) {
            if (segmentDurationMs <= MAX_INTRO_DURATION_MS) {
                if (!hasDuration || startMs <= (totalDurationMs * MAX_INTRO_START_FRACTION) || startMs <= 900_000L) {
                    return SpatialFinSegmentType.INTRO
                }
            }
        }

        // 2. OUTRO
        if (OUTRO_PATTERNS.any { it.matches(normalizedName) }) {
            if (segmentDurationMs <= MAX_OUTRO_DURATION_MS) {
                if (!hasDuration || startMs >= (totalDurationMs * MIN_OUTRO_START_FRACTION)) {
                    return SpatialFinSegmentType.OUTRO
                }
            }
        }

        // 3. RECAP
        if (RECAP_PATTERNS.any { it.matches(normalizedName) }) {
            if (segmentDurationMs <= MAX_RECAP_DURATION_MS) {
                if (!hasDuration || startMs <= (totalDurationMs * MAX_INTRO_START_FRACTION)) {
                    return SpatialFinSegmentType.RECAP
                }
            }
        }

        // 4. PREVIEW
        if (PREVIEW_PATTERNS.any { it.matches(normalizedName) }) {
            if (segmentDurationMs <= MAX_PREVIEW_DURATION_MS) {
                if (!hasDuration || startMs >= (totalDurationMs * MIN_OUTRO_START_FRACTION)) {
                    return SpatialFinSegmentType.PREVIEW
                }
            }
        }

        return null
    }
}
