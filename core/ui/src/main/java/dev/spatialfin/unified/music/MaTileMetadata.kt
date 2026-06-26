package dev.spatialfin.unified.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.data.musicassistant.MusicProviderNames
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.AudioFormat
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueueItem

/**
 * Tile metadata helpers shared across every Music Assistant surface
 * ([MaSearchItemRow] search + detail track lists, [MaQueuePanel] queue rows, and
 * the film-search `MusicAssistantItemCard`). Kept in one place so the provider
 * label and the HD badge stay consistent everywhere.
 */

/**
 * Friendly streaming-source name for a tile subheading — "YouTube Music",
 * "Local", "Audible", … instead of the raw `library://` provider.
 *
 * A saved item carries `provider == "library"`; the real source lives in its
 * provider mappings (`youtube_music--<instance>`, `filesystem_local--<id>`, …),
 * so we prefer a concrete mapping and fall back to nothing rather than echoing
 * "Library".
 */
fun ServerMediaItem.providerDisplayName(): String? =
    MusicProviderNames.fromProviderOrUri(
        provider = provider,
        providerInstance = providerMappings?.firstOrNull { it.providerInstance.isNotBlank() }?.providerInstance,
        uri = uri,
    )

// Lossless containers/codecs — bit-perfect even at CD resolution, so they earn
// the HD badge regardless of sample rate.
private val LOSSLESS_CODECS = setOf(
    "flac", "alac", "wav", "wavpack", "ape", "aiff", "aif", "pcm", "dsf", "dff", "dsd",
)

/** True when this stream is lossless or hi-res (≥24-bit or >48 kHz). */
fun AudioFormat.isHiResOrLossless(): Boolean {
    val depth = bitDepth
    val rate = sampleRate
    val hiRes = (depth != null && depth >= 24) || (rate != null && rate > 48_000)
    val codec = (codecType ?: contentType?.substringAfterLast('/'))?.lowercase()
    val lossless = codec != null && LOSSLESS_CODECS.any { codec == it || codec.contains(it) }
    return hiRes || lossless
}

/** Badge a tile as HD when any of its provider mappings can serve lossless/hi-res audio. */
fun ServerMediaItem.isHighDefinitionAudio(): Boolean =
    providerMappings?.any { it.audioFormat?.isHiResOrLossless() == true } == true

/** Queue rows know the actual stream format; fall back to the media item's mappings. */
fun ServerQueueItem.isHighDefinitionAudio(): Boolean =
    streamDetails?.audioFormat?.isHiResOrLossless() == true ||
        mediaItem?.isHighDefinitionAudio() == true

/** Small "HD" pill rendered next to a track/album title when [isHighDefinitionAudio]. */
@Composable
fun MaHdBadge(modifier: Modifier = Modifier) {
    Text(
        text = "HD",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
