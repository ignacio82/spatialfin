package dev.spatialfin.beam

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.presentation.components.FloatingProgressBar
import dev.jdtech.jellyfin.film.domain.DetailHeroMetadata
import dev.jdtech.jellyfin.film.domain.HeroFactKind
import dev.jdtech.jellyfin.film.domain.detailHeroMetadata
import dev.jdtech.jellyfin.film.presentation.home.HomeViewModel
import dev.jdtech.jellyfin.film.presentation.home.rowId
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.SpatialFinCollection
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.deduplicateMovieVersions
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.player.beam.LocalBeamWidth
import dev.jdtech.jellyfin.player.beam.isCompact
import dev.jdtech.jellyfin.presentation.components.HiddenHomeRowsCard
import dev.jdtech.jellyfin.presentation.components.HomeRowArrangeControls
import dev.jdtech.jellyfin.presentation.components.homeRowArrangeHandle
import dev.jdtech.jellyfin.settings.domain.HomeRowIds
import dev.jdtech.jellyfin.settings.domain.restorableHiddenRows
import dev.spatialfin.unified.audio.JellyfinAudioDetailType
import dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun BeamHomeTopAppBar(
    serverName: String,
    userName: String?,
    onOpenServer: () -> Unit,
    onOpenUser: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AsyncImage(
            model = dev.jdtech.jellyfin.core.R.mipmap.ic_launcher,
            contentDescription = null,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onOpenServer() }
        ) {
            Text(
                text = "SpatialFin",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onOpenUser() },
            contentAlignment = Alignment.Center
        ) {
            if (!userName.isNullOrBlank()) {
                Text(
                    text = userName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = "Switch user",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
@com.skydoves.compose.stability.runtime.TraceRecomposition(tag = "beam-home", threshold = 3)
fun BeamHomeScreen(
    contentPadding: PaddingValues,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    onOpenJellyfinAudioDetail: (UUID, String, JellyfinAudioDetailType) -> Unit,
    onOpenPluginBrowse: (String, String?) -> Unit,
    onOpenNetworkShare: (String) -> Unit = {},
    onOpenMaSearch: () -> Unit = {},
    // Primitive-typed (uri, name) rather than a MaBrowseTarget lambda: the
    onOpenMaBrowse: (String, String) -> Unit = { _, _ -> },
    userName: String? = null,
    onOpenServer: () -> Unit = {},
    onOpenUser: () -> Unit = {},
) {
    val viewModel: HomeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    val maPlayDispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val setBackground = LocalBeamBackground.current

    val featuredItem =
        state.suggestionsSection?.items?.firstOrNull()
            ?: state.resumeSection?.homeSection?.items?.firstOrNull()
            ?: state.nextUpSection?.homeSection?.items?.firstOrNull()

    LaunchedEffect(featuredItem?.id) {
        if (featuredItem != null) {
            setBackground(featuredItem.images.backdrop ?: featuredItem.images.primary)
        }
    }

    // Long-press a row's title to arrange it; the saved order and hidden set live
    // in HomeRowPreferences, so the result survives process death and matches the
    // XR and TV homes.
    val rowLayout by viewModel.rowLayout.collectAsStateWithLifecycle()
    var arrangingRowId by rememberSaveable { mutableStateOf<String?>(null) }

    // Libraries with no "Latest" row of their own — either because the library has
    // nothing recent, or because the Latest preference is off entirely. Their
    // per-library "See All" header is the only way into a library on Beam, so
    // without this they'd be unreachable.
    val unlistedLibraries =
        state.libraries.filter { library -> state.views.none { it.view.id == library.id } }

    val openItem: (dev.jdtech.jellyfin.models.SpatialFinItem) -> Unit = { item ->
        openServerItem(
            context,
            item,
            onOpenLibrary,
            onOpenShow,
            onOpenSeason,
            onOpenItem,
            maPlayDispatcher,
            audioDispatcher = jellyfinAudioDispatcher,
            onOpenJellyfinAudioDetail = onOpenJellyfinAudioDetail,
        )
    }

    // Natural (shipped) row order. HomeRowLayout.arrange() then applies whatever
    // the user rearranged on top of it.
    val naturalRows = buildList {
        state.suggestionsSection?.let { suggestions ->
            val items = suggestions.items.filter { it.id != featuredItem?.id }.deduplicateMovieVersions()
            if (items.isNotEmpty()) {
                add(BeamHomeRow(HomeRowIds.SUGGESTIONS, "Suggestions") {
                    BeamPosterCarousel(items = items, onItemClick = openItem)
                })
            }
        }
        state.resumeSection?.let { section ->
            val items = section.homeSection.items.deduplicateMovieVersions()
            if (items.isNotEmpty()) {
                add(BeamHomeRow(HomeRowIds.CONTINUE_WATCHING, section.homeSection.name.asString()) {
                    BeamPosterCarousel(items = items, onItemClick = openItem, showProgress = true)
                })
            }
        }
        state.nextUpSection?.let { section ->
            val items = section.homeSection.items.deduplicateMovieVersions()
            if (items.isNotEmpty()) {
                add(BeamHomeRow(HomeRowIds.NEXT_UP, section.homeSection.name.asString()) {
                    BeamPosterCarousel(items = items, onItemClick = openItem)
                })
            }
        }
        if (unlistedLibraries.isNotEmpty()) {
            add(BeamHomeRow(HomeRowIds.LIBRARIES, "Your libraries") {
                BeamLibraryChipRow(libraries = unlistedLibraries, onOpenLibrary = onOpenLibrary)
            })
        }
        state.views.forEach { homeView ->
            val viewItems = homeView.view.items.deduplicateMovieVersions()
            add(
                BeamHomeRow(
                    id = HomeRowIds.latest(homeView.view.id),
                    title = homeView.view.name,
                    actionLabel = "See All",
                    onAction = {
                        onOpenLibrary(homeView.view.id, homeView.view.name, homeView.view.type)
                    },
                ) {
                    if (viewItems.isNotEmpty()) {
                        BeamPosterCarousel(items = viewItems, onItemClick = openItem)
                    }
                }
            )
        }
        // Universal plugin rows belong after Jellyfin rows.
        state.universalPluginSections.forEach { section ->
            val firstItem = section.homeSection.items.firstOrNull() as? dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
            val pluginId = firstItem?.universalMediaItem?.pluginId
            val pluginRowId = firstItem?.universalMediaItem?.homeRowId
            add(
                BeamHomeRow(
                    id = section.rowId,
                    title = section.homeSection.name.asString() + " (" + section.homeSection.items.size + ")",
                    actionLabel = if (pluginId != null) "See All" else null,
                    onAction = if (pluginId != null) { { onOpenPluginBrowse(pluginId, pluginRowId) } } else null,
                ) {
                    BeamPosterCarousel(
                        items = section.homeSection.items,
                        onItemClick = { item ->
                            if (item is dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem) {
                                context.startActivity(
                                    dev.jdtech.jellyfin.player.beam.BeamPlayerActivity.createIntentForUniversalMedia(
                                        context,
                                        item.universalMediaItem.pluginId,
                                        item.universalMediaItem.id,
                                        item.universalMediaItem.videoUrl,
                                        item.name
                                    )
                                )
                            } else {
                                openItem(item)
                            }
                        }
                    )
                }
            )
        }
        state.musicAssistantSections.forEach { section ->
            add(
                BeamHomeRow(
                    id = section.rowId,
                    title = section.homeSection.name.asString(),
                ) {
                    BeamPosterCarousel(
                        items = section.homeSection.items,
                        onItemClick = { item ->
                            openServerItem(
                                context,
                                item,
                                onOpenLibrary,
                                onOpenShow,
                                onOpenSeason,
                                onOpenItem,
                                maPlayDispatcher,
                                onOpenMaBrowse,
                                audioDispatcher = jellyfinAudioDispatcher,
                                onOpenJellyfinAudioDetail = onOpenJellyfinAudioDetail,
                            )
                        },
                    )
                }
            )
        }
        // Network share (SMB/NFS) rows — one per configured share that has scanned
        // content. "See All" opens the full share browser; tapping a card plays it
        // straight through the network proxy.
        state.networkShareSections.forEach { section ->
            add(
                BeamHomeRow(
                    id = section.rowId,
                    title = section.homeSection.name.asString(),
                    actionLabel = "See All",
                    onAction = { onOpenNetworkShare(section.shareId) },
                ) {
                    BeamPosterCarousel(
                        items = section.homeSection.items,
                        onItemClick = { item ->
                            (item as? dev.jdtech.jellyfin.models.NetworkVideoItem)?.let { video ->
                                context.startActivity(
                                    dev.jdtech.jellyfin.player.beam.BeamPlayerActivity.createIntentForNetworkMedia(
                                        context = context,
                                        networkVideoId = video.networkVideoId,
                                    )
                                )
                            }
                        },
                    )
                }
            )
        }
    }

    val orderedRows = rowLayout.arrange(naturalRows) { it.id }
    val hiddenRows =
        remember(rowLayout, state.libraries, state.networkShareSections, context) {
            val customTitles = buildMap {
                state.libraries.forEach { put(HomeRowIds.latest(it.id), it.name) }
                state.networkShareSections.forEach { put(it.rowId, it.homeSection.name.asString(context.resources)) }
            }
            rowLayout.restorableHiddenRows(customTitles)
        }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading your media...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't reach your server",
                    body = state.error?.message ?: "Failed to load home content.",
                    onRetry = { viewModel.onAction(dev.jdtech.jellyfin.film.presentation.home.HomeAction.OnRetryClick) },
                )
            }
            else -> {
                // Top App Bar
                item {
                    BeamHomeTopAppBar(
                        serverName = state.server?.name ?: "Jellyfin",
                        userName = userName,
                        onOpenServer = onOpenServer,
                        onOpenUser = onOpenUser,
                    )
                }

                // Featured hero card
                featuredItem?.let { featured ->
                    item {
                        BeamHeroCard(
                            item = featured,
                            actions = {
                                BeamPrimaryActionButton(label = if (featured.playbackPositionTicks > 0L) "Resume" else "Play") {
                                    if (
                                        !openNativeAudioItem(
                                            featured,
                                            jellyfinAudioDispatcher,
                                            onOpenJellyfinAudioDetail,
                                        )
                                    ) {
                                        launchServerItem(context, fcastSession, scope, featured)
                                    }
                                }
                                BeamSecondaryActionButton(label = "Details") {
                                    openServerItem(
                                        context,
                                        featured,
                                        onOpenLibrary,
                                        onOpenShow,
                                        onOpenSeason,
                                        onOpenItem,
                                        maPlayDispatcher,
                                        audioDispatcher = jellyfinAudioDispatcher,
                                        onOpenJellyfinAudioDetail = onOpenJellyfinAudioDetail,
                                    )
                                }
                            },
                        )
                    }
                }

                orderedRows.forEachIndexed { index, row ->
                    item(key = row.id) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BeamHomeSectionHeader(
                                title = row.title,
                                actionLabel = row.actionLabel,
                                onAction = row.onAction,
                                arranging = arrangingRowId == row.id,
                                canMoveUp = index > 0,
                                canMoveDown = index < orderedRows.lastIndex,
                                onStartArranging = { arrangingRowId = row.id },
                                onMoveUp = {
                                    viewModel.moveRow(row.id, orderedRows.map { it.id }, up = true)
                                },
                                onMoveDown = {
                                    viewModel.moveRow(row.id, orderedRows.map { it.id }, up = false)
                                },
                                onHide = {
                                    viewModel.setRowVisible(row.id, false)
                                },
                                onDoneArranging = { arrangingRowId = null },
                            )
                            row.content()
                        }
                    }
                }

                // Only while arranging or when all rows have been hidden: the rows
                // the user switched off, so hiding one from home never becomes a one-way trip.
                if ((arrangingRowId != null || orderedRows.isEmpty()) && hiddenRows.isNotEmpty()) {
                    item(key = "hidden_home_rows") {
                        HiddenHomeRowsCard(
                            rows = hiddenRows,
                            onShow = { rowId -> viewModel.setRowVisible(rowId, true) },
                            onDone = { arrangingRowId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BeamLibraryChipRow(
    libraries: List<dev.jdtech.jellyfin.models.SpatialFinCollection>,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(libraries, key = { it.id }) { library ->
            Card(
                onClick = { onOpenLibrary(library.id, library.name, library.type) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * One arrangeable home row: a stable [id] for the saved layout, the title the
 * user long-presses, its optional trailing action, and the row body itself.
 */
internal data class BeamHomeRow(
    val id: String,
    val title: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val content: @Composable () -> Unit,
)

@Composable
internal fun BeamHomeSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    arranging: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onStartArranging: (() -> Unit)? = null,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onHide: () -> Unit = {},
    onDoneArranging: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(8.dp))
                .homeRowArrangeHandle(enabled = onStartArranging != null) {
                    onStartArranging?.invoke()
                }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp),
                    )
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (arranging) {
            HomeRowArrangeControls(
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                isHidden = false,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onToggleVisibility = onHide,
                onDone = onDoneArranging,
            )
        } else if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun BeamPosterCarousel(
    items: List<SpatialFinItem>,
    onItemClick: (SpatialFinItem) -> Unit,
    showProgress: Boolean = false,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items, key = { it.id }) { item ->
            BeamPosterCard(
                item = item,
                onClick = { onItemClick(item) },
                showProgress = showProgress,
            )
        }
    }
}

@Composable
internal fun BeamPosterCard(
    item: SpatialFinItem,
    onClick: () -> Unit,
    showProgress: Boolean = false,
) {
    val imageModel = item.images.primary ?: item.images.showPrimary ?: item.images.backdrop ?: item.images.showBackdrop
    val cardWidth = if (showProgress) 150.dp else 132.dp
    Card(
        onClick = onClick,
        modifier = Modifier.width(cardWidth),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, hoveredElevation = 8.dp, focusedElevation = 8.dp, pressedElevation = 4.dp),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(0.67f),
            ) {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            text = item.name.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (showProgress) {
                    buildPlaybackFraction(item)?.let { progress ->
                        FloatingProgressBar(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .align(androidx.compose.ui.Alignment.BottomCenter),
                            progressColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // minLines = 2 forces short titles to reserve the same vertical
                // space as two-line titles, so a rail of mixed-length names
                // renders as a flat row of same-height cards instead of a
                // jagged line.
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Always render the subtitle slot with the built label, falling
                // back to " " to keep the card footer at a constant height even
                // if a future item type returns a blank subtitle from
                // buildServerItemSubtitle.
                val subtitle = remember(item) { buildServerItemSubtitle(item).ifBlank { " " } }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun BeamLibraryScreen(
    contentPadding: PaddingValues,
    parentId: UUID,
    title: String,
    type: CollectionType,
    onBack: () -> Unit,
    onOpenLibrary: (UUID, String, CollectionType) -> Unit,
    onOpenShow: (UUID) -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: BeamLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(parentId, title, type) {
        viewModel.load(parentId, title, type)
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BeamBackAction(onClick = onBack)
                Text(
                    text = state.title.ifBlank { title },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        when {
            state.isLoading -> item { LoadingCard("Loading library...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this library",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(parentId, title, type) },
                )
            }
            state.items.isEmpty() -> item { BeamEmptyCard("No items found in this library.") }
            else -> {
                item {
                    BeamPosterGrid(
                        items = state.items,
                        onItemClick = { openServerItem(context, it, onOpenLibrary, onOpenShow, onOpenSeason, onOpenItem) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun BeamPosterGrid(
    items: List<SpatialFinItem>,
    onItemClick: (SpatialFinItem) -> Unit,
) {
    val chunked = remember(items) { items.chunked(5) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        chunked.forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1f)) {
                        BeamPosterCard(
                            item = item,
                            onClick = { onItemClick(item) },
                        )
                    }
                }
                // Fill remaining cells for incomplete rows
                repeat(5 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun BeamShowScreen(
    contentPadding: PaddingValues,
    showId: UUID,
    onBack: () -> Unit,
    onOpenSeason: (UUID) -> Unit,
    onOpenItem: (UUID) -> Unit,
    onOpenPerson: (UUID) -> Unit,
    viewModel: BeamShowViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current
    val scope = rememberCoroutineScope()
    val setBackground = LocalBeamBackground.current
    var showBulkDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflow by rememberSaveable(showId) { mutableStateOf(false) }
    var showEditExternalIds by rememberSaveable(showId) { mutableStateOf(false) }

    LaunchedEffect(showId) {
        viewModel.load(showId)
    }

    LaunchedEffect(state.show?.id) {
        state.show?.let { show ->
            setBackground(beamBackdropArtwork(show))
        }
    }

    LaunchedEffect(state.bulkDownload.result) {
        val result = state.bulkDownload.result ?: return@LaunchedEffect
        result.storageShortfallBytes?.let { shortfall ->
            val mb = shortfall / (1024 * 1024)
            Toast.makeText(context, "Low storage: need ~${mb}MB more space", Toast.LENGTH_LONG).show()
        }
        val msg = buildString {
            if (result.queued > 0) append("${result.queued} episodes queued")
            if (result.skipped > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.skipped} already downloaded")
            }
            if (result.failed > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.failed} failed")
            }
        }
        if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading series...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this series",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(showId) },
                )
            }
            state.show == null -> item { BeamEmptyCard("This series is no longer available.") }
            else -> {
                val show = state.show ?: return@BeamScaffoldBody
                val supportingLine =
                    show.originalTitle?.takeIf { !it.isNullOrBlank() && it != show.name }
                        ?: show.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" • ")
                item {
                    BeamDetailHeroCard(
                        item = show,
                        eyebrow = "Series",
                        supportingLine = supportingLine,
                        hero = show.detailHeroMetadata(),
                        onBack = onBack,
                    ) {
                        // Single-row by design: extras go in the overflow menu rather than
                        // wrapping to a second visual row. See BeamCastOverflowItems.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.nextUp?.let { nextEpisode ->
                                androidx.compose.material3.FilledIconButton(
                                    onClick = { launchServerItem(context, fcastSession, scope,nextEpisode) },
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                                        contentDescription = if (nextEpisode.playbackPositionTicks > 0L) "Resume Episode" else "Play Next"
                                    )
                                }
                            }
                            androidx.compose.material3.FilledTonalIconButton(
                                onClick = { viewModel.toggleFavorite() },
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = if (show.favorite) androidx.compose.material.icons.Icons.Rounded.Favorite else androidx.compose.material.icons.Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (show.favorite) "Favorited" else "Favorite"
                                )
                            }
                            androidx.compose.material3.FilledTonalIconButton(
                                onClick = { viewModel.togglePlayed() },
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = if (show.played) androidx.compose.material.icons.Icons.Rounded.CheckCircle else androidx.compose.material.icons.Icons.Rounded.Check,
                                    contentDescription = if (show.played) "Watched" else "Mark watched"
                                )
                            }
                            BeamOverflowMenu(
                                expanded = showOverflow,
                                onExpandedChange = { showOverflow = it },
                                extraItems = {
                                    state.nextUp?.let { nextEpisode ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text("SyncPlay") },
                                            onClick = {
                                                showOverflow = false
                                                dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
                                                    .createIntentForSpatialItem(
                                                        context = context,
                                                        item = nextEpisode,
                                                        openSyncPlayDialogOnStart = true,
                                                    )
                                                    ?.let(context::startActivity)
                                            }
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(if (state.bulkDownload.isQueuing) "Queuing…" else "Download Show") },
                                        onClick = {
                                            showOverflow = false
                                            showBulkDownloadDialog = true
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Edit external IDs") },
                                        onClick = {
                                            showOverflow = false
                                            showEditExternalIds = true
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
                if (state.seasons.isNotEmpty()) {
                    item {
                        Text("Seasons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        BeamSeasonStrip(
                            seasons = state.seasons,
                            onOpenSeason = onOpenSeason,
                        )
                    }
                }
                state.nextUp?.let { nextEpisode ->
                    item {
                        Text("Next Up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        BeamServerItemCard(
                            item = nextEpisode,
                            onPlay = { launchServerItem(context, fcastSession, scope,nextEpisode) },
                            onOpen = { onOpenItem(nextEpisode.id) },
                        )
                    }
                }
                val showActors = show.people.filter { person ->
                    person.type == org.jellyfin.sdk.model.api.PersonKind.ACTOR
                }
                if (showActors.isNotEmpty()) {
                    item {
                        dev.jdtech.jellyfin.presentation.film.components.ActorsRow(
                            actors = showActors,
                            onActorClick = onOpenPerson,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        )
                    }
                }
            }
        }
    }

    if (showBulkDownloadDialog) {
        BeamBulkDownloadDialog(
            title = "Download Show",
            description = "All episodes across ${state.seasons.size} season(s) will be queued for download.",
            confirmLabel = "Download All",
            onConfirm = { settings ->
                viewModel.downloadShow(showId, settings)
                showBulkDownloadDialog = false
            },
            onDismiss = { showBulkDownloadDialog = false },
        )
    }
    if (showEditExternalIds) {
        state.show?.let { show ->
            val scope = rememberCoroutineScope()
            dev.jdtech.jellyfin.presentation.film.components.EditExternalIdsDialog(
                itemId = show.id,
                initialTitle = show.name,
                initialYear = show.productionYear,
                onDismiss = { showEditExternalIds = false },
                onSaved = {
                    // Jellyfin queues its server-side metadata refresh
                    // asynchronously — wait ~5s, then reload so the hero
                    // card renders with the refreshed title / overview /
                    // poster instead of the pre-edit values.
                    scope.launch {
                        kotlinx.coroutines.delay(5_000)
                        viewModel.load(showId)
                    }
                },
            )
        }
    }
}

@Composable
fun BeamSeasonScreen(
    contentPadding: PaddingValues,
    seasonId: UUID,
    onBack: () -> Unit,
    onOpenItem: (UUID) -> Unit,
    viewModel: BeamSeasonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fcastSession = dev.spatialfin.fcast.session.LocalFCastSession.current
    val jellyfinAudioDispatcher = LocalAudioPlaybackDispatcher.current
    val scope = rememberCoroutineScope()
    val setBackground = LocalBeamBackground.current
    var showBulkDownloadDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflow by rememberSaveable(seasonId) { mutableStateOf(false) }

    LaunchedEffect(seasonId) {
        viewModel.load(seasonId)
    }

    LaunchedEffect(state.season?.id) {
        state.season?.let { season ->
            setBackground(beamBackdropArtwork(season))
        }
    }

    LaunchedEffect(state.bulkDownload.result) {
        val result = state.bulkDownload.result ?: return@LaunchedEffect
        result.storageShortfallBytes?.let { shortfall ->
            val mb = shortfall / (1024 * 1024)
            Toast.makeText(context, "Low storage: need ~${mb}MB more space", Toast.LENGTH_LONG).show()
        }
        val msg = buildString {
            if (result.queued > 0) append("${result.queued} queued")
            if (result.skipped > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.skipped} already downloaded")
            }
            if (result.failed > 0) {
                if (isNotEmpty()) append(", ")
                append("${result.failed} failed")
            }
        }
        if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        when {
            state.isLoading -> item { LoadingCard("Loading season...") }
            state.error != null -> item {
                ErrorCard(
                    title = "Couldn't load this season",
                    body = state.error?.localizedMessage ?: "Unknown error",
                    onRetry = { viewModel.load(seasonId) },
                )
            }
            state.season == null -> item { BeamEmptyCard("This season is no longer available.") }
            else -> {
                val season = state.season ?: return@BeamScaffoldBody
                val downloadableEpisodes = state.episodes.filter { !it.isDownloaded() }
                item {
                    BeamDetailHeroCard(
                        item = season,
                        eyebrow = "Season",
                        supportingLine = season.seriesName,
                        hero = season.detailHeroMetadata(),
                        onBack = onBack,
                    ) {
                        // Single-row by design: extras go in the overflow menu rather than
                        // wrapping to a second visual row. See BeamCastOverflowItems.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (season.canPlay) {
                                androidx.compose.material3.FilledIconButton(
                                    onClick = { launchServerItem(context, fcastSession, scope,season) }
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                                        contentDescription = if (season.playbackPositionTicks > 0L) "Resume" else "Play"
                                    )
                                }
                            }
                            BeamOverflowMenu(
                                expanded = showOverflow,
                                onExpandedChange = { showOverflow = it },
                                extraItems = {
                                    if (downloadableEpisodes.isNotEmpty()) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(if (state.bulkDownload.isQueuing) "Queuing…" else "Download Season") },
                                            onClick = {
                                                showOverflow = false
                                                showBulkDownloadDialog = true
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                if (state.episodes.isEmpty()) {
                    item { BeamEmptyCard("No episodes in this season.") }
                } else {
                    item {
                        Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    items(state.episodes, key = { it.id }) { episode ->
                        BeamServerItemCard(
                            item = episode,
                            onPlay = { launchServerItem(context, fcastSession, scope,episode) },
                            onOpen = { onOpenItem(episode.id) },
                        )
                    }
                }
            }
        }
    }

    if (showBulkDownloadDialog) {
        val downloadableEpisodes = state.episodes.filter { !it.isDownloaded() }
        BeamBulkDownloadDialog(
            title = "Download Season",
            description = "${downloadableEpisodes.size} episodes will be queued for download.",
            confirmLabel = "Download ${downloadableEpisodes.size} Episodes",
            onConfirm = { settings ->
                viewModel.downloadEpisodes(downloadableEpisodes, settings)
                showBulkDownloadDialog = false
            },
            onDismiss = { showBulkDownloadDialog = false },
        )
    }
}

internal fun beamPrimaryArtwork(item: SpatialFinItem): Any? =
    when (item) {
        is SpatialFinEpisode ->
            item.images.showPrimary ?: item.images.primary ?: item.images.showBackdrop ?: item.images.backdrop
        else ->
            item.images.primary ?: item.images.showPrimary ?: item.images.backdrop ?: item.images.showBackdrop
    }

internal fun beamBackdropArtwork(item: SpatialFinItem): Any? =
    when (item) {
        is SpatialFinEpisode ->
            item.images.showBackdrop ?: item.images.backdrop ?: item.images.showPrimary ?: item.images.primary
        else ->
            item.images.backdrop ?: item.images.showBackdrop ?: item.images.primary ?: item.images.showPrimary
    }

internal fun beamSeasonLabel(season: SpatialFinSeason): String =
    if (season.indexNumber > 0) {
        "Season ${season.indexNumber}"
    } else {
        season.name.ifBlank { "Season" }
    }

@Composable
internal fun BeamHeroCard(
    item: SpatialFinItem,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.images.backdrop ?: item.images.primary ?: item.images.showPrimary,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE605070B)),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY, // Or better let compose determine height automatically
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildServerItemSubtitle(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions()
                }
            }
        }
    }
}

@Composable
internal fun BeamDetailHeroCard(
    item: SpatialFinItem,
    eyebrow: String,
    supportingLine: String?,
    hero: DetailHeroMetadata,
    onBack: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    // Deliberately NOT a fixed height. This used to be `.height(280.dp)`, which on a
    // phone left the info column ~20dp after the poster and its padding — the title,
    // the fact chips and the genres were all computed, laid out, and then clipped
    // away, so the hero looked empty next to Fladder's. The backdrop is painted
    // behind via matchParentSize() and the content decides how tall the hero is.
    Box(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = beamBackdropArtwork(item),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC0B0D11),
                            Color(0xE6101319),
                            Color(0xFF111318),
                        ),
                    )
                )
        )

        val stacked = LocalBeamWidth.current.isCompact
        val info: @Composable ColumnScope.() -> Unit = {
            BeamBadge(text = eyebrow)
            // Prefer the artwork logo the way Fladder does — it is the title as the
            // studio set it. Falls back to text whenever the server has no logo.
            val logo = item.images.logo ?: item.images.showLogo
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = item.name,
                    modifier = Modifier
                        .heightIn(max = if (stacked) 72.dp else 88.dp)
                        .widthIn(max = 340.dp),
                    contentScale = ContentScale.Fit,
                    alignment = if (stacked) Alignment.Center else Alignment.CenterStart,
                )
            } else {
                Text(
                    text = item.name,
                    style = if (stacked) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            supportingLine?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE6EBF2),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hero.facts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hero.facts.forEach { fact ->
                        BeamDetailPill(text = fact.label, icon = beamHeroFactIcon(fact.kind))
                    }
                }
            }
            if (hero.genres.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hero.genres.forEach { genre -> BeamDetailPill(text = genre) }
                }
            }
            // What will actually play: resolution + HDR, the default audio track,
            // and the default subtitle track.
            val streamChips = listOfNotNull(
                hero.video?.let { it to Icons.Rounded.Movie },
                hero.audio?.let { it to Icons.Rounded.VolumeUp },
                hero.subtitle?.let { it to Icons.Rounded.ClosedCaption },
            )
            if (streamChips.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    streamChips.forEach { (label, icon) ->
                        BeamDetailPill(
                            text = label,
                            icon = icon,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            actions()
        }

        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 56.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BeamPosterArtwork(
                    item = item,
                    modifier = Modifier.width(150.dp).aspectRatio(0.67f),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { info() }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp)
                    .padding(top = 56.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.Top,
            ) {
                BeamPosterArtwork(
                    item = item,
                    modifier = Modifier.width(180.dp).aspectRatio(0.67f),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { info() }
            }
        }

        if (onBack != null) {
            androidx.compose.material3.IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(12.dp)
                    .align(Alignment.TopStart)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
    }
}

internal fun beamHeroFactIcon(kind: HeroFactKind): androidx.compose.ui.graphics.vector.ImageVector? =
    when (kind) {
        HeroFactKind.CERTIFICATION -> null
        HeroFactKind.YEAR -> Icons.Rounded.CalendarMonth
        HeroFactKind.RUNTIME -> Icons.Rounded.Schedule
        HeroFactKind.RATING -> Icons.Rounded.Star
        HeroFactKind.EPISODE -> null
        HeroFactKind.UNPLAYED -> Icons.Rounded.Visibility
    }

@Composable
internal fun BeamCastAndCrew(
    actors: List<dev.jdtech.jellyfin.models.SpatialFinItemPerson>,
    onOpenPerson: (UUID) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "CAST & CREW",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(actors) { person ->
                Column(
                    modifier = Modifier.width(72.dp).clickable { onOpenPerson(person.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val colors = listOf(Color(0xFF3C4758), Color(0xFF543F5E), Color(0xFF1F4876))
                    val color = colors[Math.floorMod(person.name.hashCode(), colors.size)]
                    if (person.image.uri != null) {
                        AsyncImage(
                            model = person.image.uri,
                            contentDescription = person.name,
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(color),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = person.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun BeamDetailPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = Color.White,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.25f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = tint,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (icon != null) tint else Color.White,
            )
        }
    }
}

@Composable
internal fun BeamSeasonStrip(
    seasons: List<SpatialFinSeason>,
    onOpenSeason: (UUID) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(seasons, key = { it.id }) { season ->
            BeamSeasonCard(
                season = season,
                onClick = { onOpenSeason(season.id) },
            )
        }
    }
}

@Composable
internal fun BeamSeasonCard(
    season: SpatialFinSeason,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BeamPosterArtwork(
                item = season,
                modifier = Modifier.width(58.dp).aspectRatio(0.67f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Reserve two lines so a rail of mixed-length season labels
                // ("Season 1" vs. "Season 10 · Specials") stays aligned.
                Text(
                    text = beamSeasonLabel(season),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        season.unplayedItemCount
                            ?.takeIf { it > 0 }
                            ?.let { "$it unwatched" }
                            ?: season.seriesName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun BeamMediaFeatureCard(
    item: SpatialFinItem,
    actions: @Composable RowScope.() -> Unit,
) {
    val subtitle = remember(item) { buildServerItemSubtitle(item) }
    val badge = remember(item) { buildPrimaryBadge(item) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BeamPosterArtwork(
                item = item,
                modifier = Modifier.width(80.dp).aspectRatio(0.67f),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BeamBadge(text = badge)
                    if (item.isDownloaded()) {
                        BeamBadge(text = "Downloaded")
                    }
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.overview.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                buildPlaybackFraction(item)?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(0.5f).height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

@Composable
internal fun BeamPosterArtwork(
    item: SpatialFinItem,
    modifier: Modifier,
) {
    val imageModel = beamPrimaryArtwork(item)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = item.name,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = item.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun BeamBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun BeamPrimaryActionButton(
    label: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick) {
        Text(label, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun BeamSecondaryActionButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick) {
        Text(label, maxLines = 1, softWrap = false)
    }
}
