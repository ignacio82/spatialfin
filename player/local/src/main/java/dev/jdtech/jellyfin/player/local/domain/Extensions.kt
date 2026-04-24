package dev.jdtech.jellyfin.player.local.domain

import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import java.util.Locale

fun List<Tracks.Group>.getTrackNames(): Array<String> {
    return this.map { group ->
            val nameParts: MutableList<String?> = mutableListOf()
            val format = group.mediaTrackGroup.getFormat(0)
            nameParts.run {
                add(format.label)
                add(
                    format.language?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                            Locale.of(it.split("-").last()).displayLanguage
                        } else {
                            @Suppress("DEPRECATION") Locale(it.split("-").last()).displayLanguage
                        }
                    }
                )
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
                }
                add(formatName)
                add(format.codecs)
                filterNotNull().joinToString(separator = " - ")
            }
        }
        .toTypedArray()
}
