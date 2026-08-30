package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.spatialfin.companion.protocol.WearChapterInfo
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearStreamInfo
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearIcon
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon

/**
 * The shared chassis for every picker sheet.
 *
 * A [TransformingLazyColumn] rather than a `verticalScroll` Column: on a round
 * screen the rows at the top and bottom of the viewport are clipped by the bezel,
 * and Wear's scaling list is what shrinks and fades them instead of letting them
 * run off the edge mid-word. The edge [ScrollIndicator] arc replaces the scrollbar
 * a round screen has nowhere to put.
 *
 * The list does not do that shrinking on its own. Every row has to opt in with
 * [scrollTransform], which is both the graphics transform and the layout height —
 * without it a `TransformingLazyColumn` is just a `LazyColumn` that lets the bezel
 * slice the last row in half.
 */
@Composable
private fun WearSheetScaffold(
    title: String,
    icon: WearIcon,
    content: androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.() -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.scrollTransform(this).padding(bottom = 2.dp),
                ) {
                    WearVectorIcon(
                        icon = icon,
                        contentDescription = null,
                        tint = WearDarkPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = title,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WearDarkPrimary,
                    )
                }
            }
            content()
        }
        ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * A selectable row.
 *
 * [secondary] is the codec/format line. It is mono and dimmed on purpose: it is
 * reference detail you scan, not a label you read.
 */
@Composable
private fun WearChoiceRow(
    label: String,
    secondary: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    leadingIcon: WearIcon? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) WearDarkPrimaryContainer else WearDarkSurfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            WearVectorIcon(
                icon = leadingIcon,
                contentDescription = null,
                tint = WearDarkOnPrimaryContainer,
                modifier = Modifier.size(11.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Color.White else WearTitleBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    text = secondary,
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (selected) WearDarkOnPrimaryContainer else WearDarkOutline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = trailing,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (selected) WearDarkOnPrimaryContainer else WearDarkOnSurfaceVariant,
            )
        }
        // The check replaces the old "✓ " text prefix, which shifted the label
        // sideways every time the selection moved.
        if (selected) {
            Spacer(modifier = Modifier.width(6.dp))
            WearVectorIcon(
                icon = WearIcons.Check,
                contentDescription = "Selected",
                tint = WearDarkOnPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun WearSheetEmptyState(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        fontSize = 10.sp,
        color = WearDarkOutline,
        modifier = modifier.padding(vertical = 18.dp),
    )
}

/** Frame 6. */
@Composable
fun WearAudioTracksSheet(
    tracks: List<WearStreamInfo>,
    currentTrack: String?,
    onSelectTrack: (WearStreamInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    WearSheetScaffold(title = "Audio", icon = WearIcons.VolumeSmall) {
        if (tracks.isEmpty()) {
            item { WearSheetEmptyState("No additional audio tracks", Modifier.scrollTransform(this)) }
        } else {
            items(tracks.size) { index ->
                val track = tracks[index]
                val display = track.name.splitStreamName()
                WearChoiceRow(
                    label = display.first,
                    secondary = display.second,
                    selected = track.name == currentTrack || track.isSelected,
                    onClick = {
                        onSelectTrack(track)
                        onDismiss()
                    },
                    modifier = Modifier.scrollTransform(this),
                )
            }
        }
        item { WearSheetBackButton(onDismiss, Modifier.scrollTransform(this)) }
    }
}

/** Frame 7. "Off" leads, as it did before — it is the row people come here for. */
@Composable
fun WearSubtitleTracksSheet(
    tracks: List<WearStreamInfo>,
    currentTrack: String?,
    onSelectTrack: (WearStreamInfo?) -> Unit,
    onDismiss: () -> Unit,
) {
    WearSheetScaffold(title = "Subtitles", icon = WearIcons.CaptionsSmall) {
        item {
            WearChoiceRow(
                label = "Off",
                secondary = null,
                selected = currentTrack.isNullOrBlank(),
                onClick = {
                    onSelectTrack(null)
                    onDismiss()
                },
                modifier = Modifier.scrollTransform(this),
            )
        }
        items(tracks.size) { index ->
            val track = tracks[index]
            val display = track.name.splitStreamName()
            WearChoiceRow(
                label = display.first,
                secondary = display.second,
                selected = track.name == currentTrack || track.isSelected,
                onClick = {
                    onSelectTrack(track)
                    onDismiss()
                },
                modifier = Modifier.scrollTransform(this),
            )
        }
        item { WearSheetBackButton(onDismiss, Modifier.scrollTransform(this)) }
    }
}

/**
 * Frame 8.
 *
 * The chapter you are *inside* is marked, not merely listed: with only a start
 * timestamp per chapter, "which one am I in" is otherwise arithmetic the user has
 * to do in their head.
 */
@Composable
fun WearChaptersSheet(
    chapters: List<WearChapterInfo>,
    currentChapterName: String?,
    positionSeconds: Long,
    durationSeconds: Long,
    onSelectChapter: (WearChapterInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val activeIndex = chapters.activeIndexAt(positionSeconds, currentChapterName)

    WearSheetScaffold(title = "Chapters", icon = WearIcons.ListOrdered) {
        if (chapters.isEmpty()) {
            item { WearSheetEmptyState("No chapter marks available", Modifier.scrollTransform(this)) }
        } else {
            items(chapters.size) { index ->
                val chapter = chapters[index]
                val isActive = index == activeIndex
                WearChoiceRow(
                    label = chapter.name.ifBlank { "Chapter ${index + 1}" },
                    secondary = null,
                    selected = isActive,
                    leadingIcon = if (isActive) WearIcons.Play else null,
                    trailing = formatClock(chapter.startPositionSeconds),
                    onClick = {
                        onSelectChapter(chapter)
                        onDismiss()
                    },
                    modifier = Modifier.scrollTransform(this),
                )
            }
        }
        item { WearSheetBackButton(onDismiss, Modifier.scrollTransform(this)) }
    }
}

/**
 * Frame 9.
 *
 * Recenter is the biggest thing on screen because it is the one control people
 * actually reach for; scale and distance are steppers underneath it.
 *
 * The design shows a live "1.4x" / "3.5m" readout beside each stepper. The watch
 * cannot draw one: [WearPlayerAction.AdjustScale] and [WearPlayerAction.AdjustDistance]
 * are delta-only and no panel-geometry state rides the state DataItems, so any
 * number here would be the watch's guess at a value the headset owns and the user
 * can also change from inside XR. Publishing scale/distance in `WearNowPlayingState`
 * is what would earn that readout.
 */
@Composable
fun WearSpatialControlsSheet(
    onDispatchAction: (WearPlayerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    WearSheetScaffold(title = "Spatial", icon = WearIcons.Glasses) {
        item {
            Column(
                modifier = Modifier
                    .scrollTransform(this)
                    .size(65.dp)
                    .clip(CircleShape)
                    .background(WearDarkPrimaryContainer)
                    .clickable { onDispatchAction(WearPlayerAction.ResetScreenPlacement) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                WearVectorIcon(
                    icon = WearIcons.Target,
                    contentDescription = "Recenter panel",
                    tint = WearDarkOnPrimaryContainer,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Recenter",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = WearDarkOnPrimaryContainer,
                )
            }
        }
        item {
            WearStepperRow(
                label = "Scale",
                onDecrease = { onDispatchAction(WearPlayerAction.AdjustScale(delta = -0.2f)) },
                onIncrease = { onDispatchAction(WearPlayerAction.AdjustScale(delta = 0.2f)) },
                modifier = Modifier.scrollTransform(this),
            )
        }
        item {
            WearStepperRow(
                label = "Distance",
                onDecrease = { onDispatchAction(WearPlayerAction.AdjustDistance(delta = -0.5f)) },
                onIncrease = { onDispatchAction(WearPlayerAction.AdjustDistance(delta = 0.5f)) },
                modifier = Modifier.scrollTransform(this),
            )
        }
        item { WearSheetBackButton(onDismiss, Modifier.scrollTransform(this), label = "Done") }
    }
}

@Composable
private fun WearStepperRow(
    label: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(WearDarkSurfaceContainer)
            .padding(start = 13.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = WearDarkOnSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        StepperButton(WearIcons.Minus, "Decrease $label", onDecrease)
        Spacer(modifier = Modifier.width(6.dp))
        StepperButton(WearIcons.Plus, "Increase $label", onIncrease)
    }
}

@Composable
private fun StepperButton(icon: WearIcon, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(WearDarkSurfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        WearVectorIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = WearTitleBright,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
fun WearDevicePickerSheet(
    currentDeviceName: String,
    lanReceivers: List<FCastReceiver>,
    canFling: Boolean,
    onSelectLanReceiver: (FCastReceiver) -> Unit,
    onFlingToReceiver: (FCastReceiver) -> Unit,
    onDismiss: () -> Unit,
) {
    WearSheetScaffold(title = "Target", icon = WearIcons.Tv) {
        item {
            Text(
                text = currentDeviceName,
                fontSize = 9.5.sp,
                color = WearDarkOnSurfaceVariant,
                modifier = Modifier.scrollTransform(this).padding(bottom = 4.dp),
            )
        }
        if (lanReceivers.isEmpty()) {
            item { WearSheetEmptyState("No LAN receivers found", Modifier.scrollTransform(this)) }
        } else {
            items(lanReceivers.size) { index ->
                val receiver = lanReceivers[index]
                WearChoiceRow(
                    label = receiver.name,
                    secondary = null,
                    selected = receiver.name == currentDeviceName,
                    leadingIcon = WearIcons.Tv,
                    onClick = {
                        onSelectLanReceiver(receiver)
                        onDismiss()
                    },
                    modifier = Modifier.scrollTransform(this),
                )
                // Only offered when the watch actually knows the current stream URL —
                // without one there is nothing to fling.
                if (canFling) {
                    Box(
                        modifier = Modifier
                            .scrollTransform(this)
                            .fillMaxWidth()
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WearDarkSurfaceVariant)
                            .clickable {
                                onFlingToReceiver(receiver)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Fling here",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = WearDarkOnSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { WearSheetBackButton(onDismiss, Modifier.scrollTransform(this), label = "Close") }
    }
}

@Composable
private fun WearSheetBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Back",
) {
    Box(
        modifier = modifier
            .padding(top = 6.dp)
            .size(width = 84.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(WearDarkPrimary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = WearDarkOnPrimary,
        )
    }
}

/**
 * Splits a Jellyfin stream display name into a title and a format line.
 *
 * Jellyfin hands us one flat string ("English - EAC3 - 5.1"); `WearStreamInfo`
 * carries no codec or channel-layout field of its own. Rather than widen the wire
 * protocol for a cosmetic second line, the separator Jellyfin already uses is
 * split here. A name with no separator keeps the whole string as its title and
 * simply has no second line.
 */
internal fun String.splitStreamName(): Pair<String, String?> {
    val separator = SEPARATORS.firstOrNull { contains(it) } ?: return this to null
    val head = substringBefore(separator).trim()
    val tail = substringAfter(separator).trim().replace(separator.trim(), "·")
    return if (head.isEmpty() || tail.isEmpty()) this to null else head to tail
}

private val SEPARATORS = listOf(" - ", " · ", " | ")

/**
 * Which chapter contains [positionSeconds].
 *
 * Prefers the host's own [currentChapterName] when it sent one; falls back to the
 * last chapter whose start is at or behind the playhead.
 */
internal fun List<WearChapterInfo>.activeIndexAt(
    positionSeconds: Long,
    currentChapterName: String?,
): Int {
    if (!currentChapterName.isNullOrBlank()) {
        val named = indexOfFirst { it.name == currentChapterName }
        if (named >= 0) return named
    }
    return indexOfLast { it.startPositionSeconds <= positionSeconds }
}

internal fun formatClock(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
