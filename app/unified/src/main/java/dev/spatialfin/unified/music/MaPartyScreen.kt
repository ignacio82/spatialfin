package dev.spatialfin.unified.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    queueViewModel: MaQueueViewModel = hiltViewModel(),
) {
    val state by session.session.collectAsStateWithLifecycle()
    val queue by queueViewModel.state.collectAsStateWithLifecycle()
    val partyUrl by queueViewModel.partyJoinUrl.collectAsStateWithLifecycle()
    val track = state.nowPlaying
    val context = LocalContext.current

    // Pull the Party plugin's guest join URL for the share QR.
    LaunchedEffect(Unit) { queueViewModel.loadPartyUrl() }

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

            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Row(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                NowPlayingColumn(
                    title = track?.title,
                    artist = track?.artist,
                    artworkUrl = track?.artworkUrl,
                    partyActive = queue.partyActive,
                    joinUrl = partyUrl,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                UpNextColumn(
                    items = queue.items,
                    currentItemId = queue.currentItemId,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
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
    title: String?,
    artist: String?,
    artworkUrl: String?,
    partyActive: Boolean,
    joinUrl: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
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
            modifier = Modifier.width(280.dp).aspectRatio(1f),
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
        )
        if (!artist.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!joinUrl.isNullOrBlank()) {
            Spacer(Modifier.height(28.dp))
            JoinQr(joinUrl = joinUrl)
        }
    }
}

@Composable
private fun JoinQr(joinUrl: String) {
    val qr = rememberQrBitmap(joinUrl, size = 480)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (qr != null) {
            androidx.compose.foundation.Image(
                bitmap = qr,
                contentDescription = "Scan to join the party",
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
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
        }
    }
}

@Composable
private fun UpNextColumn(
    items: List<ServerQueueItem>,
    currentItemId: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // Drop everything up to and including the current item — the party
        // screen is about what's coming, not what already played.
        val currentIndex = items.indexOfFirst { it.queueItemId == currentItemId }
        val upcoming = if (currentIndex >= 0) items.drop(currentIndex + 1) else items
        if (upcoming.isEmpty()) {
            Text(
                text = "Nothing queued yet — add some tracks!",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f),
            )
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(upcoming, key = { it.queueItemId }) { item ->
                PartyQueueRow(item)
            }
        }
    }
}

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
