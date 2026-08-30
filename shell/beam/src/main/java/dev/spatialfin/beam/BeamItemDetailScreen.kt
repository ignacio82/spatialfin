package dev.spatialfin.beam

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderAction
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderEvent
import dev.jdtech.jellyfin.core.presentation.downloader.DownloaderViewModel
import dev.jdtech.jellyfin.film.domain.detailHeroMetadata
import dev.jdtech.jellyfin.film.domain.languagePreferences
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.MediaStreamLanguage
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinFolder
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.versionChipLabel
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.spatialfin.unified.audio.JellyfinAudioDetailScreen
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * The Jellyfin item detail screen — movie / episode, plus the photo-viewer and
 * native-audio routing it short-circuits into.
 *
 * Split out of BeamJellyfinScreens.kt, which had grown past 4000 lines. Same
 * package, so nothing here changed except its file.
 */
@Composable
fun BeamItemDetailScreen(
    contentPadding: PaddingValues,
    itemId: UUID,
    onBack: () -> Unit,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenPerson: (UUID) -> Unit,
    viewModel: BeamItemDetailViewModel = hiltViewModel(),
    downloaderViewModel: DownloaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloaderState by downloaderViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current
    val scope = rememberCoroutineScope()
    val setBackground = LocalBeamBackground.current
    var showDownloadDialog by rememberSaveable(itemId) { mutableStateOf(false) }
    var showPlaybackOptions by rememberSaveable(itemId) { mutableStateOf(false) }
    var showOverflow by rememberSaveable(itemId) { mutableStateOf(false) }
    var showEditExternalIds by rememberSaveable(itemId) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(itemId) { mutableStateOf(false) }
    var showAudioTrackDialog by rememberSaveable(itemId) { mutableStateOf(false) }
    var showSubtitleTrackDialog by rememberSaveable(itemId) { mutableStateOf(false) }
    var selectedAudioStreamIndex by rememberSaveable(itemId) { mutableStateOf<Int?>(null) }
    var selectedSubtitleStreamIndex by rememberSaveable(itemId) { mutableStateOf<Int?>(null) }
    var subtitlesDisabled by rememberSaveable(itemId) { mutableStateOf(false) }

    // The same configuration the in-player selector reads, so the chips promise
    // what playback will actually do.
    val languagePreferences = remember(context) {
        viewModel.appPreferences.languagePreferences(context)
    }

    LaunchedEffect(itemId) {
        viewModel.deletedEvents.collect { ok ->
            val msg = if (ok) "Deleted" else "Delete failed"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            if (ok) onBack()
        }
    }

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    LaunchedEffect(state.item?.id) {
        state.item?.let {
            downloaderViewModel.update(it)
            setBackground(beamBackdropArtwork(it))
        }
    }

    LaunchedEffect(Unit) {
        downloaderViewModel.events.collect { event ->
            val message =
                when (event) {
                    DownloaderEvent.Successful -> "Download completed"
                    DownloaderEvent.Deleted -> "Download deleted"
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    state.item?.let { itemData ->
        itemData.jellyfinAudioDetailType()?.let { detailType ->
            JellyfinAudioDetailScreen(
                itemId = itemData.id,
                title = itemData.name,
                detailType = detailType,
                parentId = null,
                onBack = onBack,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            return
        }
        // A still image has no metadata worth a detail page — go straight to the
        // viewer. Routing through Detail (rather than a dedicated Beam route) means
        // every surface that already calls onOpenItem for unrecognised kinds — home
        // rows, library grids, folder browse, search — opens photos for free.
        if (itemData is dev.jdtech.jellyfin.models.SpatialFinPhoto) {
            dev.jdtech.jellyfin.presentation.film.PhotoViewerScreen(
                photoId = itemData.id,
                parentId = itemData.parentId,
                onBack = onBack,
                // Full-bleed at the top, but keep the page counter clear of the bottom nav.
                modifier = Modifier.fillMaxSize()
                    .padding(bottom = contentPadding.calculateBottomPadding()),
            )
            return
        }
    }

    // Resolved once, above the list: the hero chips, the Play buttons and the
    // two track dialogs must all act on the same answer, and deriving it more
    // than once is how they drift apart.
    val heroMetadata = state.item?.detailHeroMetadata(
        languagePreferences = languagePreferences,
        selectedAudioStreamIndex = selectedAudioStreamIndex,
        selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
        subtitlesDisabled = subtitlesDisabled,
    )
    val resolvedAudioStreamIndex = heroMetadata?.audioStreamIndex
    val resolvedSubtitleStreamIndex = heroMetadata?.subtitleStreamIndex

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 0.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when {
            state.isLoading -> item { LoadingCard("Loading...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this item",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(itemId) },
                )
            }
            state.item == null -> item { BeamEmptyCard("This item is no longer available.") }
            else -> {
                val itemData = state.item ?: return@LazyColumn
                val supportingLine =
                    when (itemData) {
                        is SpatialFinMovie ->
                            itemData.originalTitle?.takeIf { !it.isNullOrBlank() && it != itemData.name }
                                ?: itemData.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" • ")
                        is SpatialFinEpisode ->
                            listOf(itemData.seriesName, itemData.seasonName, buildEpisodeLabel(itemData))
                                .filterNotNull()
                                .filter { it.isNotBlank() }
                                .joinToString(" • ")
                                .ifBlank { null }
                        is SpatialFinSeason -> itemData.seriesName
                        else -> itemData.originalTitle?.takeIf { !it.isNullOrBlank() && it != itemData.name }
                    }
                val hero = heroMetadata ?: return@LazyColumn
                val effectiveAudioStreamIndex = resolvedAudioStreamIndex
                val effectiveSubtitleStreamIndex = resolvedSubtitleStreamIndex

                item {
                    BeamDetailHeroCard(
                        item = itemData,
                        eyebrow = buildPrimaryBadge(itemData),
                        supportingLine = supportingLine,
                        hero = hero,
                        onBack = onBack,
                        onAudioChipClick = { showAudioTrackDialog = true },
                        onSubtitleChipClick = { showSubtitleTrackDialog = true },
                        actions = {} // Actions moved below
                    )
                }
                item {
                    val isResume = itemData.playbackPositionTicks > 0L
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (itemData.canPlay) {
                            androidx.compose.material3.Button(
                                onClick = {
                                    if (!playNativeAudioItem(itemData, jellyfinAudioDispatcher)) {
                                        launchServerItem(
                                            context = context,
                                            fcastSession = fcastSession,
                                            scope = scope,
                                            item = itemData,
                                            audioStreamIndex = effectiveAudioStreamIndex,
                                            subtitleStreamIndex = effectiveSubtitleStreamIndex,
                                            subtitlesDisabled = subtitlesDisabled,
                                        )
                                    }
                                }
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                                    contentDescription = if (isResume) "Resume" else "Play"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (isResume) "Resume" else "Play")
                            }
                            if (isResume) {
                                androidx.compose.material3.FilledTonalIconButton(
                                    onClick = {
                                        if (
                                            !playNativeAudioItem(
                                                itemData,
                                                jellyfinAudioDispatcher,
                                                fromStart = true,
                                            )
                                        ) {
                                            launchServerItem(
                                                context = context,
                                                fcastSession = fcastSession,
                                                scope = scope,
                                                item = itemData,
                                                startFromBeginning = true,
                                                audioStreamIndex = effectiveAudioStreamIndex,
                                                subtitleStreamIndex = effectiveSubtitleStreamIndex,
                                                subtitlesDisabled = subtitlesDisabled,
                                            )
                                        }
                                    }
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Rounded.Replay,
                                        contentDescription = "From Start"
                                    )
                                }
                            }
                        }
                        androidx.compose.material3.IconButton(
                            onClick = { viewModel.toggleFavorite() }
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = if (itemData.favorite) androidx.compose.material.icons.Icons.Rounded.Favorite else androidx.compose.material.icons.Icons.Rounded.FavoriteBorder,
                                contentDescription = if (itemData.favorite) "Favorited" else "Favorite",
                                tint = if (itemData.favorite) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
                            )
                        }
                        androidx.compose.material3.IconButton(
                            onClick = { viewModel.togglePlayed() }
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = if (itemData.played) androidx.compose.material.icons.Icons.Rounded.CheckCircle else androidx.compose.material.icons.Icons.Rounded.Check,
                                contentDescription = if (itemData.played) "Watched" else "Mark watched",
                                tint = if (itemData.played) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
                            )
                        }

                        if (itemData.canPlay) {
                                BeamDownloadActions(
                                    item = itemData,
                                    downloaderState = downloaderState,
                                    onOpenOptions = { showDownloadDialog = true },
                                    onCancelDownload = {
                                        downloaderViewModel.onAction(DownloaderAction.CancelDownload(itemData))
                                    },
                                    onPauseDownload = {
                                        downloaderViewModel.onAction(DownloaderAction.PauseDownload(itemData))
                                    },
                                    onResumeDownload = {
                                        downloaderViewModel.onAction(DownloaderAction.ResumeDownload(itemData))
                                    },
                                    onDeleteDownload = {
                                        downloaderViewModel.onAction(DownloaderAction.DeleteDownload(itemData))
                                    },
                                )
                            }

                            BeamOverflowMenu(
                                expanded = showOverflow,
                                onExpandedChange = { showOverflow = it },
                                extraItems = {
                                    if (itemData.canPlay) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("SyncPlay") },
                                            onClick = {
                                                showOverflow = false
                                                dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
                                                    .createIntentForSpatialItem(
                                                        context = context,
                                                        item = itemData,
                                                        openSyncPlayDialogOnStart = true,
                                                    )
                                                    ?.let(context::startActivity)
                                            }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Playback Options") },
                                            onClick = {
                                                showOverflow = false
                                                showPlaybackOptions = true
                                            }
                                        )
                                    }
                                    if (itemData is SpatialFinCollection || itemData is SpatialFinFolder) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Open Collection") },
                                            onClick = {
                                                showOverflow = false
                                                openServerItem(context, itemData, onOpenLibrary, onOpenShow, onOpenSeason, {})
                                            }
                                        )
                                    }
                                    if (itemData is SpatialFinEpisode) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Go to series") },
                                            onClick = {
                                                showOverflow = false
                                                onOpenShow(itemData.seriesId)
                                            }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Go to season") },
                                            onClick = {
                                                showOverflow = false
                                                onOpenSeason(itemData.seasonId)
                                            }
                                        )
                                    }
                                    // Edit external IDs works for anything that
                                    // Jellyfin writes providerIds onto. Movies
                                    // and shows are the primary target; episodes
                                    // are supported because an individual
                                    // episode's IMDb ID is a valid override
                                    // when the series mapping is right but the
                                    // specific episode got misattributed.
                                    // Collections/folders aren't writable.
                                    if (itemData is SpatialFinMovie ||
                                        itemData is SpatialFinShow ||
                                        itemData is SpatialFinEpisode
                                    ) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("Edit external IDs") },
                                            onClick = {
                                                showOverflow = false
                                                showEditExternalIds = true
                                            }
                                        )
                                    }
                                },
                                onRefresh = {
                                    viewModel.refreshMetadata()
                                    Toast.makeText(context, "Refreshing metadata…", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { showDeleteConfirm = true },
                                onShare = {
                                    val base = viewModel.serverBaseUrl().trimEnd('/')
                                    val url = "$base/web/#!/details?id=${itemData.id}"
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_SUBJECT, itemData.name)
                                                putExtra(android.content.Intent.EXTRA_TEXT, "${itemData.name}\n$url")
                                            },
                                            "Share ${itemData.name}",
                                        )
                                    )
                                },
                            )
                        }
                        if (state.availableVersions.size > 1) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Version",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD7DDE6),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.availableVersions.forEach { version ->
                                    FilterChip(
                                        selected = version.id == itemData.id,
                                        onClick = { if (version.id != itemData.id) viewModel.load(version.id) },
                                        label = { Text(version.versionChipLabel()) },
                                    )
                                }
                            }
                        }
                    }
                item {
                    val isResume = itemData.playbackPositionTicks > 0L
                    if (isResume && itemData.runtimeTicks > 0L) {
                        val progress = itemData.playbackPositionTicks.toFloat() / itemData.runtimeTicks.toFloat()
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Text(
                        text = itemData.overview.ifBlank { "No overview available." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                val actors = beamPeopleOf(itemData).filter { person ->
                    person.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR || person.type == org.jellyfin.sdk.model.api.PersonKind.DIRECTOR
                }
                if (actors.isNotEmpty()) {
                    item {
                        BeamCastAndCrew(actors = actors, onOpenPerson = onOpenPerson)
                    }
                }
                if (itemData.chapters.isNotEmpty()) {
                    item {
                        BeamChaptersRow(
                            chapters = itemData.chapters,
                            onChapterClick = { chapter ->
                                dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
                                    .createIntentForSpatialItem(
                                        context = context,
                                        item = itemData,
                                        // chapter.startPosition is already in ms (stored as ticks/10000 by toSpatialFinChapters).
                                        startPositionMs = chapter.startPosition,
                                    )
                                    ?.let(context::startActivity)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteItem()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Delete from library?") },
            text = { Text("This removes the item from your Jellyfin server. This can't be undone.") },
        )
    }

    if (showDownloadDialog) {
        val item = state.item
        if (item != null) {
            BeamDownloadOptionsDialog(
                item = item,
                onConfirm = { request ->
                    downloaderViewModel.onAction(DownloaderAction.Download(item, request))
                    showDownloadDialog = false
                },
                onDismiss = { showDownloadDialog = false },
            )
        }
    }
    if (showPlaybackOptions) {
        val item = state.item
        if (item != null) {
            BeamPlaybackOptionsDialog(
                item = item,
                onDismiss = { showPlaybackOptions = false },
                onPlay = { sourceIndex, bitrate, fromBeginning ->
                    launchServerItem(
                        context = context,
                        fcastSession = fcastSession,
                        scope = scope,
                        item = item,
                        startFromBeginning = fromBeginning,
                        mediaSourceIndex = sourceIndex,
                        maxBitrate = bitrate,
                        audioStreamIndex = selectedAudioStreamIndex,
                        subtitleStreamIndex = selectedSubtitleStreamIndex,
                        subtitlesDisabled = subtitlesDisabled,
                    )
                    showPlaybackOptions = false
                },
            )
        }
    }
    if (showAudioTrackDialog) {
        val item = state.item
        if (item != null) {
            BeamAudioTrackSelectionDialog(
                item = item,
                resolvedStreamIndex = resolvedAudioStreamIndex,
                onStreamSelected = { selectedAudioStreamIndex = it },
                onDismiss = { showAudioTrackDialog = false },
            )
        }
    }
    if (showSubtitleTrackDialog) {
        val item = state.item
        if (item != null) {
            BeamSubtitleTrackSelectionDialog(
                item = item,
                resolvedStreamIndex = resolvedSubtitleStreamIndex,
                onStreamSelected = { streamIndex, disabled ->
                    selectedSubtitleStreamIndex = streamIndex
                    subtitlesDisabled = disabled
                },
                onDismiss = { showSubtitleTrackDialog = false },
            )
        }
    }
    if (showEditExternalIds) {
        val item = state.item
        if (item != null) {
            val year = when (item) {
                is SpatialFinMovie -> item.productionYear
                is SpatialFinShow -> item.productionYear
                else -> null
            }
            val initialTitle = when (item) {
                is SpatialFinEpisode ->
                    buildString {
                        if (item.seriesName.isNotBlank()) append(item.seriesName).append(" ")
                        append("S").append(item.parentIndexNumber)
                        append("E").append(item.indexNumber)
                        if (item.name.isNotBlank()) append(" ").append(item.name)
                    }
                else -> item.name
            }
            val scope = rememberCoroutineScope()
            dev.jdtech.jellyfin.presentation.film.components.EditExternalIdsDialog(
                itemId = item.id,
                initialTitle = initialTitle,
                initialYear = year,
                onDismiss = { showEditExternalIds = false },
                onSaved = {
                    // Wait for Jellyfin to finish its async refresh before
                    // reloading. See BeamShowDetailScreen's onSaved handler.
                    scope.launch {
                        kotlinx.coroutines.delay(5_000)
                        viewModel.load(itemId)
                    }
                },
            )
        }
    }
}

@Composable
internal fun BeamAudioTrackSelectionDialog(
    item: SpatialFinItem,
    /**
     * Stream index that will play. Resolved once by `detailHeroMetadata` and
     * passed in, so the pre-checked row and the hero chip can never disagree.
     */
    resolvedStreamIndex: Int?,
    onStreamSelected: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val audioStreams = remember(item) {
        item.sources.firstOrNull()?.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1E),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Audio Track",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    audioStreams.forEach { stream ->
                        val isSelected = stream.index == resolvedStreamIndex
                        val title = stream.displayTitle?.takeIf { it.isNotBlank() }
                            ?: buildString {
                                append(MediaStreamLanguage.displayCode(stream) ?: "UND")
                                if (stream.title.isNotBlank()) append(" - ").append(stream.title)
                                if (stream.codec.isNotBlank()) append(" (").append(stream.codec.uppercase(Locale.US)).append(")")
                            }
                        val details = buildList {
                            if (stream.codec.isNotBlank()) add(stream.codec.uppercase(Locale.US))
                            stream.channelLayout?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }.joinToString(" · ")

                        Surface(
                            onClick = {
                                onStreamSelected(stream.index)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onStreamSelected(stream.index)
                                        onDismiss()
                                    },
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (details.isNotBlank() && details != title) {
                                        Text(
                                            text = details,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeamSubtitleTrackSelectionDialog(
    item: SpatialFinItem,
    /** Subtitle stream that will play, or null for none. See the audio dialog. */
    resolvedStreamIndex: Int?,
    onStreamSelected: (streamIndex: Int?, disabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val subtitleStreams = remember(item) {
        item.sources.firstOrNull()?.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1C1E),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Subtitle Track",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // "Off" option
                    val isOffSelected = resolvedStreamIndex == null
                    Surface(
                        onClick = {
                            onStreamSelected(null, true)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isOffSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                        border = if (isOffSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isOffSelected,
                                onClick = {
                                    onStreamSelected(null, true)
                                    onDismiss()
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Off",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isOffSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isOffSelected) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                    }

                    subtitleStreams.forEach { stream ->
                        val isSelected = stream.index == resolvedStreamIndex
                        val title = stream.displayTitle?.takeIf { it.isNotBlank() }
                            ?: buildString {
                                append(MediaStreamLanguage.displayCode(stream) ?: "UND")
                                if (stream.title.isNotBlank()) append(" - ").append(stream.title)
                            }
                        val details = buildList {
                            if (stream.codec.isNotBlank()) add(stream.codec.uppercase(Locale.US))
                            if (stream.isExternal) add("External") else add("Embedded")
                        }.joinToString(" · ")

                        Surface(
                            onClick = {
                                onStreamSelected(stream.index, false)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onStreamSelected(stream.index, false)
                                        onDismiss()
                                    },
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (details.isNotBlank() && details != title) {
                                        Text(
                                            text = details,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
