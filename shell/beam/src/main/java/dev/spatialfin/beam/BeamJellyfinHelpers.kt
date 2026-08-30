package dev.spatialfin.beam

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.api.SeerrSearchResult
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SpatialFinAudioBook
import dev.jdtech.jellyfin.models.SpatialFinAudioTrack
import dev.jdtech.jellyfin.models.SpatialFinBoxSet
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinMusicAlbum
import dev.jdtech.jellyfin.models.SpatialFinMusicArtist
import dev.jdtech.jellyfin.models.SpatialFinPlaylist
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.toAudioQueueItem
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.spatialfin.unified.audio.AudioPlaybackDispatcher
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Navigation, launch and label-formatting helpers shared by the Beam Jellyfin
 * screens. Kept together so a screen file does not have to carry them.
 *
 * Split out of BeamJellyfinScreens.kt, which had grown past 4000 lines. Same
 * package, so nothing here changed except its file.
 */
internal fun openServerItem(
    context: Context,
    item: SpatialFinItem,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    maPlayDispatcher: dev.spatialfin.unified.MaPlayDispatcher? = null,
    onOpenMaUriBrowse: ((uri: String, name: String) -> Unit)? = null,
    audioDispatcher: AudioPlaybackDispatcher? = null,
    onOpenJellyfinAudioDetail: ((UUID, String, JellyfinAudioDetailType) -> Unit)? = null,
) {
    when (item) {
        is SpatialFinCollection -> onOpenLibrary(item.id, item.name, item.type)
        is SpatialFinFolder -> onOpenLibrary(item.id, item.name, CollectionType.Folders)
        is SpatialFinShow -> onOpenShow(item.id)
        is SpatialFinSeason -> onOpenSeason(item.id)
        is SpatialFinAudioTrack -> {
            if (!playNativeAudioItem(item, audioDispatcher)) onOpenItem(item.id)
        }
        is SpatialFinMusicAlbum,
        is SpatialFinMusicArtist,
        is SpatialFinPlaylist,
        is SpatialFinAudioBook -> {
            val detailType = item.jellyfinAudioDetailType()
            if (detailType != null && onOpenJellyfinAudioDetail != null) {
                onOpenJellyfinAudioDetail(item.id, item.name, detailType)
            } else {
                onOpenItem(item.id)
            }
        }
        else -> {
            val maUri = item.originalTitle
            if (!maUri.isNullOrBlank() && maUri.contains("://")) {
                // Podcasts/audiobooks open a detail screen (episode list /
                // chapters + resume); tracks & directly-playable items just play.
                val browseCallback = onOpenMaUriBrowse
                if (
                    browseCallback != null &&
                        dev.spatialfin.unified.music.maDetailTargetForUri(maUri, item.name) != null
                ) {
                    browseCallback(maUri, item.name)
                    return
                }
                val artwork = item.images.primary?.toString()
                if (maPlayDispatcher != null) {
                    maPlayDispatcher.playUri(maUri, title = item.name, artworkUrl = artwork)
                } else {
                    // Fallback for callers that didn't thread the dispatcher;
                    // the receiver service still works, just no optimistic UI.
                    dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
                        .playMusicAssistantMedia(
                            context,
                            maUri,
                            dev.jdtech.jellyfin.api.JellyfinApi.getInstance(context).userId?.toString(),
                        )
                }
                android.widget.Toast.makeText(context, "Queuing in Music Assistant…", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                onOpenItem(item.id)
            }
        }
    }
}

internal fun SpatialFinItem.jellyfinAudioDetailType(): JellyfinAudioDetailType? =
    when (this) {
        is SpatialFinMusicAlbum -> JellyfinAudioDetailType.Album
        is SpatialFinMusicArtist -> JellyfinAudioDetailType.Artist
        is SpatialFinPlaylist -> JellyfinAudioDetailType.Playlist
        is SpatialFinAudioBook -> JellyfinAudioDetailType.Book
        else -> null
    }

internal fun openNativeAudioItem(
    item: SpatialFinItem,
    audioDispatcher: AudioPlaybackDispatcher?,
    onOpenJellyfinAudioDetail: (UUID, String, JellyfinAudioDetailType) -> Unit,
): Boolean {
    if (playNativeAudioItem(item, audioDispatcher)) return true
    val detailType = item.jellyfinAudioDetailType() ?: return false
    onOpenJellyfinAudioDetail(item.id, item.name, detailType)
    return true
}

internal fun playNativeAudioItem(
    item: SpatialFinItem,
    audioDispatcher: AudioPlaybackDispatcher?,
    fromStart: Boolean = false,
): Boolean {
    val track = item as? SpatialFinAudioTrack ?: return false
    val playableTrack = if (fromStart) track.copy(playbackPositionTicks = 0L) else track
    audioDispatcher?.playQueue(listOf(playableTrack.toAudioQueueItem()))
    return audioDispatcher != null
}

/**
 * Cast actions injected into [BeamOverflowMenu]. Reads the FCast session manager from the
 * composition local installed by [BeamNavigationRoot] (null on surfaces without one — TV,
 * previews — in which case nothing is rendered). Lives in the overflow rather than as a peer
 * icon so the hero-card action row stays single-line on phones.
 */
@Composable
internal fun BeamCastOverflowItems(onItemSelected: () -> Unit) {
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current ?: return
    val pickedTarget by fcastSession.pickedTarget.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Text(
                if (pickedTarget != null) "Cast (${pickedTarget?.name})"
                else "Cast to receiver…"
            )
        },
        onClick = {
            onItemSelected()
            fcastSession.showPicker()
        },
        leadingIcon = {
            androidx.compose.material3.Icon(
                painter = painterResource(dev.jdtech.jellyfin.core.R.drawable.ic_cast),
                contentDescription = null,
                tint = if (pickedTarget != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    androidx.compose.material3.LocalContentColor.current
                },
            )
        },
    )
    if (pickedTarget != null) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Stop casting") },
            onClick = {
                onItemSelected()
                scope.launch { fcastSession.stopCast() }
            },
            leadingIcon = {
                androidx.compose.material3.Icon(
                    painter = painterResource(dev.jdtech.jellyfin.core.R.drawable.ic_x),
                    contentDescription = null,
                )
            },
        )
    }
}

@Composable
internal fun BeamOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    extraItems: @Composable ColumnScope.() -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    Box {
        androidx.compose.material3.FilledTonalIconButton(onClick = { onExpandedChange(true) }) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Rounded.MoreVert,
                contentDescription = "More actions",
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            extraItems()
            // Cast affordance lives inside the overflow rather than as a peer icon so hero-card
            // action rows stay single-line on narrow phones. No-op (nothing rendered) on
            // surfaces without an CastSessionManager in scope (e.g. TV).
            BeamCastOverflowItems(onItemSelected = { onExpandedChange(false) })
            if (onRefresh != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Refresh metadata") },
                    onClick = {
                        onExpandedChange(false)
                        onRefresh()
                    },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.Refresh,
                            contentDescription = null,
                        )
                    },
                )
            }
            if (onShare != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        onExpandedChange(false)
                        onShare()
                    },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.Share,
                            contentDescription = null,
                        )
                    },
                )
            }
            if (onDelete != null) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        onExpandedChange(false)
                        onDelete()
                    },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun BeamChaptersRow(
    chapters: List<dev.jdtech.jellyfin.models.SpatialFinChapter>,
    onChapterClick: (dev.jdtech.jellyfin.models.SpatialFinChapter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Key by list index, not startPosition — Jellyfin often emits several
            // chapters at the same position (e.g. a leading marker plus "Chapter 1"
            // both at 0), and duplicate LazyRow keys crash ("Key 0 already used").
            itemsIndexed(chapters, key = { index, chapter -> "$index-${chapter.startPosition}" }) { _, chapter ->
                BeamChapterCard(chapter = chapter, onClick = { onChapterClick(chapter) })
            }
        }
    }
}

@Composable
internal fun BeamChapterCard(
    chapter: dev.jdtech.jellyfin.models.SpatialFinChapter,
    onClick: () -> Unit,
) {
    val title = chapter.name?.takeIf { it.isNotBlank() } ?: formatChapterTime(chapter.startPosition)
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.77f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161D28)),
        ) {
            if (chapter.imageUri != null) {
                AsyncImage(
                    model = chapter.imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomStart)
                    .background(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = formatChapterTime(chapter.startPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun beamPeopleOf(item: SpatialFinItem): List<dev.jdtech.jellyfin.models.SpatialFinItemPerson> =
    when (item) {
        is SpatialFinMovie -> item.people
        is SpatialFinEpisode -> item.people
        is SpatialFinShow -> item.people
        else -> emptyList()
    }

internal fun formatChapterTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun launchServerItem(
    context: Context,
    fcastSession: dev.spatialfin.fcast.session.CastSessionManager?,
    scope: kotlinx.coroutines.CoroutineScope,
    item: SpatialFinItem,
    startFromBeginning: Boolean = false,
    mediaSourceIndex: Int? = null,
    maxBitrate: Long? = null,
    audioStreamIndex: Int? = null,
    subtitleStreamIndex: Int? = null,
    subtitlesDisabled: Boolean = false,
) {
    val kind =
        when (item) {
            is SpatialFinMovie -> "Movie"
            is SpatialFinEpisode -> "Episode"
            is SpatialFinSeason -> "Season"
            is SpatialFinShow -> "Series"
            is SpatialFinBoxSet -> "BoxSet"
            else -> {
                val maUri = item.originalTitle
                android.util.Log.e("BeamJellyfinScreens", "launchServerItem: else branch, maUri=$maUri")
                if (!maUri.isNullOrBlank() && maUri.contains("://")) {
                    dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService.playMusicAssistantMedia(
                        context,
                        maUri,
                        dev.jdtech.jellyfin.api.JellyfinApi.getInstance(context).userId?.toString(),
                    )
                    android.widget.Toast.makeText(context, "Playing on Sendspin...", android.widget.Toast.LENGTH_SHORT).show()
                }
                return
            }
        }

    val intentBuilder: () -> android.content.Intent = {
        BeamPlayerActivity.createIntent(
            context = context,
            itemId = item.id,
            itemKind = kind,
            startFromBeginning = startFromBeginning,
            mediaSourceIndex = mediaSourceIndex,
            maxBitrate = maxBitrate,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            subtitlesDisabled = subtitlesDisabled,
        )
    }
    // Split-A/V on Beam: Pixel plays video locally with audio muted while the picked
    // receiver plays audio only. Builds the same Beam intent as the non-split path but with
    // `splitAvVideoRole=true` — BeamPlayerActivity reads that extra and constructs a
    // PlayerSplitAvAdapter, which binds the SplitAvVideoBridge that the cast controller
    // needs to drive its first-play seek + drift correction.
    //
    // Before this wire-up, the Beam call site passed no splitAvIntentBuilder; launchPlayback
    // defaulted it to `{ null }`, so castSpatialItemSplitAv's `localPlayerIntentBuilder()`
    // returned null, no Activity was launched, and SplitAvVideoBridge never received a
    // master. The receiver's audio stream sat at playWhenReady=false (waiting for a master
    // signal that never came) and the user saw "no video on phone, no audio on TV."
    val splitAvIntentBuilder: () -> android.content.Intent? = {
        BeamPlayerActivity.createIntentForSpatialItem(
            context = context,
            item = item,
            startFromBeginning = startFromBeginning,
            splitAvVideoRole = true,
        )
    }
    // Route through launchPlayback so a chosen FCast receiver wins over the
    // local player. With no session manager (defensive null branch) or no
    // intent-to-cast, launchPlayback falls through to startActivity.
    if (fcastSession != null) {
        dev.spatialfin.fcast.session.launchPlayback(
            context = context,
            sessionManager = fcastSession,
            scope = scope,
            item = item,
            intentBuilder = intentBuilder,
            splitAvIntentBuilder = splitAvIntentBuilder,
        )
    } else {
        context.startActivity(intentBuilder())
    }
}

internal fun buildServerItemSubtitle(item: SpatialFinItem): String =
    buildList {
        when (item) {
            is SpatialFinMovie -> add("Movie")
            is SpatialFinEpisode -> {
                add(buildEpisodeLabel(item))
                item.seasonName?.takeIf { !it.isNullOrBlank() }?.let(::add)
            }
            is SpatialFinShow -> add("Series")
            is SpatialFinSeason -> {
                add(if (item.indexNumber > 0) "Season ${item.indexNumber}" else "Season")
                item.seriesName.takeIf { it.isNotBlank() }?.let(::add)
            }
            is SpatialFinBoxSet -> add("Box Set")
            is SpatialFinCollection -> add(item.type.type.replaceFirstChar(Char::titlecase))
            is SpatialFinFolder -> add("Folder")
            is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem -> {
                item.universalMediaItem.author?.takeIf { it.isNotBlank() }?.let(::add)
            }
            else -> Unit
        }
        val original = item.originalTitle?.takeIf { it.isNotBlank() && it != item.name }
        when {
            // Music Assistant smuggles the play URI in originalTitle; show the
            // friendly artist/provider line (overview) instead of "library://…".
            original?.contains("://") == true -> item.overview.takeIf { it.isNotBlank() }?.let(::add)
            original != null -> add(original)
        }
        buildProgressLabel(item)?.let(::add)
        buildRemainingLabel(item)?.let(::add)
    }.joinToString(" • ")

internal fun buildPrimaryBadge(item: SpatialFinItem): String =
    when (item) {
        is SpatialFinMovie -> "Movie"
        is SpatialFinEpisode -> buildEpisodeLabel(item)
        is SpatialFinShow -> "Series"
        is SpatialFinSeason -> if (item.indexNumber > 0) "Season ${item.indexNumber}" else "Season"
        is SpatialFinBoxSet -> "Collection"
        is SpatialFinCollection -> item.type.type.replaceFirstChar(Char::titlecase)
        is SpatialFinFolder -> "Folder"
        is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem -> item.universalMediaItem.author?.takeIf { it.isNotBlank() } ?: ""
        else -> ""
    }

internal fun buildPlaybackFraction(item: SpatialFinItem): Float? {
    val runtime = item.runtimeTicks
    val position = item.playbackPositionTicks
    if (runtime <= 0L || position <= 0L) return null
    return (position.toFloat() / runtime.toFloat()).coerceIn(0f, 1f)
}

internal fun buildBeamSeerrSubtitle(item: SeerrSearchResult): String? {
    val parts = mutableListOf<String>()
    parts += when (item.mediaType) {
        "movie" -> "Movie"
        "tv" -> "Series"
        else -> item.mediaType.replaceFirstChar(Char::titlecase)
    }
    item.releaseDate?.takeIf { it.length >= 4 }?.substring(0, 4)?.let(parts::add)
    item.firstAirDate?.takeIf { it.length >= 4 }?.substring(0, 4)?.let(parts::add)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

@Composable
internal fun BeamSeerrStatusBadge(status: Int?) {
    val (text, color) =
        when (status ?: 1) {
            2 -> "Pending" to Color(0xFFFFC107)
            3 -> "Processing" to Color(0xFF2196F3)
            4 -> "Partial" to Color(0xFF8BC34A)
            5 -> "Available" to Color(0xFF4CAF50)
            else -> return
        }
    Box(
        modifier =
            Modifier
                .background(color.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = text, color = Color.Black, style = MaterialTheme.typography.labelMedium)
    }
}

internal fun buildServerItemMeta(item: SpatialFinItem): List<String> {
    val lines = mutableListOf<String>()
    val typeLabel =
        when (item) {
            is SpatialFinMovie -> "Movie"
            is SpatialFinEpisode -> item.seriesName.ifBlank { "Episode" }
            is SpatialFinShow -> "Series"
            is SpatialFinSeason -> item.seriesName.ifBlank { "Season" }
            is SpatialFinBoxSet -> "Box Set"
            is SpatialFinCollection -> item.type.type
            is SpatialFinFolder -> "Folder"
            is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem -> item.universalMediaItem.author
            else -> null
        }
    typeLabel?.takeIf { it.isNotBlank() }?.let { lines += it }
    when (item) {
        is SpatialFinEpisode -> lines += "${buildEpisodeLabel(item)}${item.seasonName?.takeIf { !it.isNullOrBlank() }?.let { " • $it" } ?: ""}"
        is SpatialFinSeason -> if (item.indexNumber > 0) lines += "Season ${item.indexNumber}"
        else -> Unit
    }
    item.originalTitle
        ?.takeIf { it.isNotBlank() && it != item.name && !it.contains("://") }
        ?.let { lines += "Original title: $it" }
    formatRuntime(item.runtimeTicks)?.let { lines += "Runtime: $it" }
    if (item.playbackPositionTicks > 0L) {
        formatRuntime(item.playbackPositionTicks)?.let { lines += "Progress: $it watched" }
        buildRemainingLabel(item)?.let { lines += it }
    }
    item.unplayedItemCount?.let { if (it > 0) lines += "Episodes left: $it" }
    if (item.favorite) lines += "Favorite"
    if (item.played) lines += "Played"
    lines += item.ratings.mapNotNull { rating ->
        rating.value.takeIf { it.isNotBlank() }?.let { "${rating.type.label}: $it" }
    }
    return lines
}

internal fun buildEpisodeLabel(episode: SpatialFinEpisode): String {
    val seasonPart = if (episode.parentIndexNumber > 0) "S${episode.parentIndexNumber}" else "S?"
    val episodePart = if (episode.indexNumber > 0) "E${episode.indexNumber}" else "E?"
    return "$seasonPart$episodePart"
}

internal fun buildProgressLabel(item: SpatialFinItem): String? {
    if (item.played) return "Played"
    val position = item.playbackPositionTicks
    val runtime = item.runtimeTicks
    if (position <= 0L || runtime <= 0L) return null
    val percent = ((position.toDouble() / runtime.toDouble()) * 100.0).toInt().coerceIn(1, 99)
    return "$percent% watched"
}

internal fun buildRemainingLabel(item: SpatialFinItem): String? {
    if (item.played) return null
    val runtime = item.runtimeTicks
    val position = item.playbackPositionTicks
    if (runtime <= 0L || position <= 0L || position >= runtime) return null
    return formatRuntime(runtime - position)?.let { "$it left" }
}

internal fun formatRuntime(ticks: Long): String? {
    if (ticks <= 0L) return null
    val totalMinutes = ticks / 10_000_000L / 60L
    if (totalMinutes <= 0L) return null
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
