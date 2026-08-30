package dev.jdtech.jellyfin.player.local.domain

import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.models.SpatialFinMediaStream
import java.util.Locale

fun List<Tracks.Group>.getTrackNames(
    mediaStreams: List<SpatialFinMediaStream> = emptyList(),
): Array<String> {
    val pairedStreams = pairedStreams(mediaStreams)
    return this.mapIndexed { index, group ->
            val nameParts: MutableList<String?> = mutableListOf()
            val format = group.mediaTrackGroup.getFormat(0)
            val matchingStream = pairedStreams.getOrNull(index)
            val rawLabel = format.label?.takeIf { it.isNotBlank() }
                ?: matchingStream?.title?.takeIf { it.isNotBlank() }
            val langCode = format.language?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
                ?: matchingStream?.language?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }

            val displayLanguage = langCode?.let {
                val code = it.split("-").last()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    Locale.of(code).displayLanguage
                } else {
                    @Suppress("DEPRECATION") Locale(code).displayLanguage
                }.takeIf { name -> name.isNotBlank() && !name.equals("und", ignoreCase = true) }
            }

            nameParts.run {
                if (!rawLabel.isNullOrBlank()) {
                    add(rawLabel)
                }
                if (!displayLanguage.isNullOrBlank() && (rawLabel == null || !rawLabel.contains(displayLanguage, ignoreCase = true))) {
                    add(displayLanguage)
                }
                val formatName = when (format.sampleMimeType) {
                    MimeTypes.APPLICATION_SUBRIP -> "SubRip"
                    MimeTypes.TEXT_SSA, "text/x-ssa" -> "ASS"
                    MimeTypes.TEXT_VTT -> "VTT"
                    MimeTypes.APPLICATION_PGS -> "PGS"
                    MimeTypes.APPLICATION_TTML -> "TTML"
                    MimeTypes.APPLICATION_TX3G -> "TX3G"
                    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                    MimeTypes.APPLICATION_CEA608 -> "CEA-608"
                    MimeTypes.APPLICATION_CEA708 -> "CEA-708"
                    else -> format.sampleMimeType?.substringAfterLast("/")?.uppercase()?.removePrefix("X-")
                        ?: matchingStream?.codec?.uppercase()
                }
                add(formatName)
                add(format.codecs)
                val joined = filterNotNull().filter { it.isNotBlank() }.joinToString(separator = " - ")
                if (joined.isNotBlank()) {
                    joined
                } else {
                    matchingStream?.displayTitle?.takeIf { it.isNotBlank() } ?: "Unknown"
                }
            }
        }
        .toTypedArray()
}
