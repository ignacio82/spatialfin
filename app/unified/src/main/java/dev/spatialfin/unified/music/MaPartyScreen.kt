package dev.spatialfin.unified.music

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerQueueItem
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository

/**
 * Immersive "party" view: full-bleed current-track art, a now-playing column,
 * and the live up-next queue. One layout serves the big-screen (TV) and the
 * XR spatial-panel hosting — both want the same "lean back and watch the room's
 * queue" experience.
 *
 * Visuals only. The vote tally / contributor avatars and pinch-to-upvote are
 * deferred until the MA Party plugin's vote protocol is wired (the queue panel
 * already reflects the server-reordered party queue today).
 *
 * TV-perf-safe per GEMINI.md: no Compose `.blur()` (scrim + gradient only),
 * the background art decodes downscaled, and the up-next list is keyed.
 */
@Composable
fun MaPartyScreen(
    session: MaSessionRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPickPlayer: (() -> Unit)? = null,
    queueViewModel: MaQueueViewModel = hiltViewModel(),
) {
    val state by session.session.collectAsStateWithLifecycle()
    val queue by queueViewModel.state.collectAsStateWithLifecycle()
    val partyJoin by queueViewModel.partyJoin.collectAsStateWithLifecycle()
    val track = state.nowPlaying
    val context = LocalContext.current

    // Pull the Party plugin's guest join URL for the share QR. Re-query when
    // party mode toggles on — the join code only exists while a party is live.
    LaunchedEffect(queue.partyActive) { queueViewModel.loadPartyUrl() }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full-bleed art background, downscaled for weak GPUs.
            if (!track?.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(track!!.artworkUrl)
                        .size(960, 540)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Darkening scrim so foreground text stays legible (no blur on TV).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.88f)),
                        ),
                    ),
            )
            // Slow, audio-agnostic "pulse" overlay — a cheap stand-in for a
            // beat-reactive backdrop until real audio analysis lands.
            PartyPulse(modifier = Modifier.fillMaxSize())

            // Wide surfaces (TV / XR / landscape) get the lean-back two-column
            // layout; a phone in portrait stacks everything in one scroll so the
            // QR and queue aren't crushed into vertical letter-per-line text.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= 600.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(48.dp),
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                    ) {
                        NowPlayingColumn(
                            session = session,
                            title = track?.title,
                            artist = track?.artist,
                            artworkUrl = track?.artworkUrl,
                            partyActive = queue.partyActive,
                            partyJoin = partyJoin,
                            centered = false,
                            onPickPlayer = onPickPlayer,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        UpNextColumn(
                            items = queue.items,
                            currentItemId = queue.currentItemId,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                } else {
                    PartyPortraitLayout(
                        session = session,
                        track = track,
                        partyActive = queue.partyActive,
                        partyJoin = partyJoin,
                        items = queue.items,
                        currentItemId = queue.currentItemId,
                        onPickPlayer = onPickPlayer,
                    )
                }
            }

            // Drawn last so it sits above the (full-screen, scrollable) party
            // content — otherwise the content captures the tap and Back is dead.
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp).maFocusHighlight(),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
    }
}

/**
 * Phone / portrait layout: one centered vertical scroll — party badge, big art,
 * track, share QR, then the up-next list. A plain [Column] (not a nested
 * LazyColumn) holds the queue so it composes inside the outer scroll.
 */
@Composable
private fun PartyPortraitLayout(
    session: MaSessionRepository,
    track: dev.jdtech.jellyfin.data.musicassistant.repository.NowPlayingTrack?,
    partyActive: Boolean,
    partyJoin: PartyJoinUiState,
    items: List<ServerQueueItem>,
    currentItemId: String?,
    onPickPlayer: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NowPlayingColumn(
            session = session,
            title = track?.title,
            artist = track?.artist,
            artworkUrl = track?.artworkUrl,
            partyActive = partyActive,
            partyJoin = partyJoin,
            centered = true,
            onPickPlayer = onPickPlayer,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(36.dp))
        UpNextList(
            items = items,
            currentItemId = currentItemId,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PartyPulse(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "party-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(Color(0xFF8E24AA).copy(alpha = alpha), Color.Transparent),
                center = Offset.Unspecified,
            ),
        ),
    )
}

@Composable
private fun NowPlayingColumn(
    session: MaSessionRepository,
    title: String?,
    artist: String?,
    artworkUrl: String?,
    partyActive: Boolean,
    partyJoin: PartyJoinUiState,
    centered: Boolean,
    onPickPlayer: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null
    Column(
        modifier = modifier,
        verticalArrangement = if (centered) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = align,
    ) {
        if (partyActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Celebration,
                    contentDescription = null,
                    tint = Color(0xFFE1BEE7),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "PARTY MODE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE1BEE7),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        Surface(
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.08f),
        ) {
            if (artworkUrl.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            } else {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = title ?: "Nothing playing",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = textAlign,
            )
        }
        
        Spacer(Modifier.height(24.dp))
        val maDispatcher = dev.spatialfin.unified.LocalMaPlayDispatcher.current
        val sessionState by session.session.collectAsStateWithLifecycle()
        val playbackPhase = sessionState.playbackPhase

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(
                onClick = { maDispatcher?.previous() },
                modifier = Modifier.maFocusHighlight(),
                enabled = maDispatcher != null && playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { maDispatcher?.playPause() },
                modifier = Modifier.size(64.dp).maFocusHighlight(),
                enabled = maDispatcher != null && playbackPhase != PlaybackPhase.Preparing,
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = if (playbackPhase == PlaybackPhase.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playbackPhase == PlaybackPhase.Playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(
                onClick = { maDispatcher?.next() },
                modifier = Modifier.maFocusHighlight(),
                enabled = maDispatcher != null && playbackPhase != PlaybackPhase.Preparing,
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        if (onPickPlayer != null) {
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.AssistChip(
                onClick = { onPickPlayer.invoke() },
                modifier = Modifier.maFocusHighlight(RoundedCornerShape(8.dp)),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.SpeakerGroup,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = {
                    Text(
                        text = sessionState.selectedPlayer?.let { "Playing on ${it.name}" } ?: "Choose a player"
                    )
                },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    labelColor = Color.White,
                    leadingIconContentColor = Color.White,
                ),
            )
        }

        Spacer(Modifier.height(28.dp))
        JoinShare(partyJoin = partyJoin, centered = centered)
    }
}

/**
 * The share affordance below the now-playing block. Shows the scan-to-join QR
 * when MA hands us a guest URL; otherwise tells the user exactly what to do
 * (enable guest access, or install the Party plugin) instead of leaving a void.
 */
@Composable
private fun JoinShare(partyJoin: PartyJoinUiState, centered: Boolean) {
    when {
        !partyJoin.url.isNullOrBlank() -> JoinQr(joinUrl = partyJoin.url, centered = centered)
        // Still resolving (or party mode just toggled) — say nothing yet.
        partyJoin.loading || !partyJoin.loaded -> Unit
        partyJoin.pluginAvailable -> JoinHint(
            headline = "Guest sharing is off",
            body = "Turn on guest access in the Music Assistant Party plugin settings to show a join QR here.",
            centered = centered,
        )
        else -> JoinHint(
            headline = "Party plugin not installed",
            body = "Install the Party plugin on your Music Assistant server to let guests scan and join.",
            centered = centered,
        )
    }
}

@Composable
private fun JoinHint(headline: String, body: String, centered: Boolean) {
    val textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = textAlign,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = textAlign,
        )
    }
}

@Composable
private fun JoinQr(joinUrl: String, centered: Boolean) {
    val context = LocalContext.current
    val qr = rememberQrBitmap(joinUrl, size = 480)
    val image: @Composable (Modifier) -> Unit = { mod ->
        if (qr != null) {
            androidx.compose.foundation.Image(
                bitmap = qr,
                contentDescription = "Scan to join the party",
                modifier = mod
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp),
            )
        }
    }
    val shareButton: @Composable () -> Unit = {
        // Hand the link to the system share sheet too — not everyone is in the
        // room to scan (and TV/XR can't be pointed at a camera easily).
        FilledTonalButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Join the party on Music Assistant")
                    putExtra(Intent.EXTRA_TEXT, joinUrl)
                }
                // No share targets (common on TV) → ignore rather than crash.
                runCatching {
                    context.startActivity(
                        Intent.createChooser(send, "Share join link").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            },
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share link")
        }
    }

    if (centered) {
        // Phone: QR centered and large, caption + share beneath it.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            image(Modifier.size(220.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Scan to join",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Point a phone camera at the code to add songs to the queue.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            shareButton()
        }
    } else {
        // Wide: QR beside the caption.
        Row(verticalAlignment = Alignment.CenterVertically) {
            image(Modifier.size(132.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "Scan to join",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "Point a phone camera at the code to\nadd songs to the queue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
                shareButton()
            }
        }
    }
}

/** Drop the current item and everything before it — the party view is forward-looking. */
private fun upcomingOf(items: List<ServerQueueItem>, currentItemId: String?): List<ServerQueueItem> {
    val currentIndex = items.indexOfFirst { it.queueItemId == currentItemId }
    return if (currentIndex >= 0) items.drop(currentIndex + 1) else items
}

@Composable
private fun UpNextHeader() {
    Text(
        text = "Up next",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun UpNextEmpty() {
    Text(
        text = "Nothing queued yet — add some tracks!",
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.6f),
    )
}

/** Wide layout: the up-next list scrolls on its own (it's a fixed-height column). */
@Composable
private fun UpNextColumn(
    items: List<ServerQueueItem>,
    currentItemId: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        UpNextHeader()
        val upcoming = upcomingOf(items, currentItemId)
        if (upcoming.isEmpty()) {
            UpNextEmpty()
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(upcoming, key = { it.queueItemId }) { item ->
                PartyQueueRow(item)
            }
        }
    }
}

/**
 * Portrait layout: a plain [Column] (NOT a LazyColumn) so it composes inside the
 * screen's outer vertical scroll. Capped so a giant queue can't bloat the tree.
 */
@Composable
private fun UpNextList(
    items: List<ServerQueueItem>,
    currentItemId: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        UpNextHeader()
        val upcoming = upcomingOf(items, currentItemId)
        if (upcoming.isEmpty()) {
            UpNextEmpty()
            return
        }
        upcoming.take(PORTRAIT_QUEUE_CAP).forEach { item ->
            PartyQueueRow(item)
            Spacer(Modifier.height(4.dp))
        }
        val overflow = upcoming.size - PORTRAIT_QUEUE_CAP
        if (overflow > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "+ $overflow more in the queue",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

private const val PORTRAIT_QUEUE_CAP = 30

@Composable
private fun PartyQueueRow(item: ServerQueueItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = item.image?.path
            ?: item.mediaItem?.image?.path
            ?: item.mediaItem?.metadata?.images?.firstOrNull()?.path
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color.White.copy(alpha = 0.1f),
        ) {
            if (art.isNullOrBlank()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Equalizer,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                    )
                }
            } else {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name ?: item.mediaItem?.name ?: "Unknown track",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = item.mediaItem?.artists?.firstOrNull()?.name
                ?: item.mediaItem?.album?.name
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
