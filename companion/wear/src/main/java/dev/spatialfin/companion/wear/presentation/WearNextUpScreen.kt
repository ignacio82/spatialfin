package dev.spatialfin.companion.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.lazy.scrollTransform
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import dev.spatialfin.companion.protocol.WearNextUpItem
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.wear.presentation.components.ArcCountdownRing
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearGlassBorder
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon
import dev.spatialfin.companion.wear.transport.WearTransportManager
import kotlinx.coroutines.launch

/**
 * Frame 10 — Continue Watching.
 *
 * Poster art returns, and progress rides the poster as a ring rather than a 3dp
 * line under the title: on a 227dp round screen a hairline bar under two lines of
 * text is the first thing the bezel eats. "14 min left" replaces the bar's
 * information entirely — it is the number people actually decide on.
 */
@Composable
fun WearNextUpScreen(
    transportManager: WearTransportManager,
    onNavigateBack: () -> Unit,
) {
    val nextUpState by transportManager.nextUp.collectAsState()
    val scope = rememberCoroutineScope()

    WearNextUpContent(
        items = nextUpState?.items.orEmpty(),
        onPlay = { item ->
            scope.launch {
                transportManager.dispatchAction(
                    WearPlayerAction.PlayMediaItem(
                        itemId = item.id,
                        mediaType = item.mediaType,
                        startPositionMs = item.playbackPositionSeconds * 1000L,
                    ),
                )
            }
            onNavigateBack()
        },
        onNavigateBack = onNavigateBack,
    )
}

/**
 * The list itself, with no transport attached.
 *
 * Split out so the debug-only store-screenshot harness renders this exact composable
 * with representative items instead of a lookalike — a watch with no paired host has
 * an empty Next Up feed and would otherwise only ever screenshot the empty state.
 */
@Composable
internal fun WearNextUpContent(
    items: List<WearNextUpItem>,
    onPlay: (WearNextUpItem) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.scrollTransform(this).padding(bottom = 2.dp),
                ) {
                    WearVectorIcon(
                        icon = WearIcons.ClockSmall,
                        contentDescription = null,
                        tint = WearDarkPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Continue",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WearDarkPrimary,
                    )
                }
            }

            if (items.isEmpty()) {
                item {
                    Text(
                        text = "Nothing to continue",
                        fontSize = 10.sp,
                        color = WearDarkOutline,
                        modifier = Modifier.scrollTransform(this).padding(vertical = 20.dp),
                    )
                }
            } else {
                items(items.size) { index ->
                    val item = items[index]
                    NextUpRow(
                        item = item,
                        onClick = { onPlay(item) },
                        modifier = Modifier.scrollTransform(this),
                    )
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .scrollTransform(this)
                        .padding(top = 6.dp)
                        .size(width = 84.dp, height = 34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(WearDarkPrimary)
                        .clickable(onClick = onNavigateBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Back",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = WearDarkOnPrimary,
                    )
                }
            }
        }
        ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun NextUpRow(
    item: WearNextUpItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (item.durationSeconds > 0) {
        (item.playbackPositionSeconds.toFloat() / item.durationSeconds).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(WearDarkSurfaceContainer)
            .border(1.dp, WearGlassBorder, RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(WearDarkSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!item.primaryImageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.primaryImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                } else {
                    // No art, or no route to the server on the Bluetooth-only path.
                    // An initial beats an empty grey disc for telling rows apart.
                    Text(
                        text = item.title.take(1).uppercase(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WearDarkOnSurfaceVariant,
                    )
                }
            }
            if (progress > 0f) {
                ArcCountdownRing(
                    fraction = progress,
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 2.5.dp,
                    edgeInset = 0.dp,
                )
            }
        }

        Spacer(modifier = Modifier.width(9.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = WearTitleBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val series = item.seriesName
            if (!series.isNullOrBlank()) {
                Text(
                    text = if (item.seasonNumber != null && item.episodeNumber != null) {
                        "S${item.seasonNumber}:E${item.episodeNumber} · $series"
                    } else {
                        series
                    },
                    fontSize = 8.5.sp,
                    color = WearDarkOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val remaining = item.durationSeconds - item.playbackPositionSeconds
            if (remaining > 0) {
                Text(
                    text = formatRemaining(remaining),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = WearDarkPrimary,
                )
            }
        }
    }
}

/** "14 min left", or "1 h 12 min left" once it stops fitting in minutes. */
internal fun formatRemaining(seconds: Long): String {
    val totalMinutes = (seconds + 59) / 60
    return if (totalMinutes >= 60) {
        "${totalMinutes / 60} h ${totalMinutes % 60} min left"
    } else {
        "$totalMinutes min left"
    }
}
