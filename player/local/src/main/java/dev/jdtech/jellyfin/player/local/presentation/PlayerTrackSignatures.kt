package dev.jdtech.jellyfin.player.local.presentation

import androidx.media3.common.Tracks

/**
 * Stable, deterministic identifiers for audio and subtitle tracks.
 *
 * These signatures let SpatialFin remember a user's manual track pick across
 * sessions by something more durable than a track index — for example, a
 * series may add a new audio track and shuffle the order, but the signature
 * for the user's preferred track stays stable.
 *
 * Each function comes in two forms:
 * - a primitive-typed overload that takes only `Format` fields, so the rule
 *   can be unit-tested without constructing media3 [Tracks.Group] instances;
 * - a `Tracks.Group` overload that pulls the first track's format and
 *   delegates to the primitive form.
 *
 * Lifted out of `PlayerViewModel` so the rule is testable in JVM unit tests
 * and so other player surfaces can produce matching ids.
 */
internal object PlayerTrackSignatures {

    /**
     * Signature for a subtitle track. The primary discriminators are language,
     * label (e.g. "Full Dialogue", "Signs & Songs"), role/selection flags
     * (forced, default, descriptive), and the MIME type.
     *
     * Note: codecs are intentionally omitted from the subtitle signature —
     * subtitle tracks of the same logical content can be re-muxed across
     * formats and the user's pick should still survive.
     */
    fun subtitle(
        language: String?,
        label: String?,
        roleFlags: Int,
        selectionFlags: Int,
        mimeType: String?,
    ): String = listOf(
        language.orEmpty().trim().lowercase(),
        label.orEmpty().trim().lowercase(),
        roleFlags.toString(),
        selectionFlags.toString(),
        mimeType.orEmpty().trim().lowercase(),
    ).joinToString("|")

    /**
     * Signature for an audio track. Includes everything the subtitle signature
     * carries, plus the codec — the same language can ship as Stereo AAC and
     * 5.1 Atmos, and those should be remembered as distinct picks.
     */
    fun audio(
        language: String?,
        label: String?,
        roleFlags: Int,
        selectionFlags: Int,
        mimeType: String?,
        codecs: String?,
    ): String = listOf(
        language.orEmpty().trim().lowercase(),
        label.orEmpty().trim().lowercase(),
        roleFlags.toString(),
        selectionFlags.toString(),
        mimeType.orEmpty().trim().lowercase(),
        codecs.orEmpty().trim().lowercase(),
    ).joinToString("|")

    fun subtitle(group: Tracks.Group): String {
        val format = group.getTrackFormat(0)
        return subtitle(
            language = format.language,
            label = format.label,
            roleFlags = format.roleFlags,
            selectionFlags = format.selectionFlags,
            mimeType = format.sampleMimeType,
        )
    }

    fun audio(group: Tracks.Group): String {
        val format = group.getTrackFormat(0)
        return audio(
            language = format.language,
            label = format.label,
            roleFlags = format.roleFlags,
            selectionFlags = format.selectionFlags,
            mimeType = format.sampleMimeType,
            codecs = format.codecs,
        )
    }
}
