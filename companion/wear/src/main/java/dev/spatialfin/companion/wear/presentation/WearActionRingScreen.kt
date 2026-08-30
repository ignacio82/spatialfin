package dev.spatialfin.companion.wear.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.wear.fcast.WearAudioReceiverService
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnSurfaceVariant
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOutline
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceContainer
import dev.spatialfin.companion.wear.presentation.theme.WearGlassBorder
import dev.spatialfin.companion.wear.presentation.theme.WearIcon
import dev.spatialfin.companion.wear.presentation.theme.WearIcons
import dev.spatialfin.companion.wear.presentation.theme.WearMint
import dev.spatialfin.companion.wear.presentation.theme.WearTitleBright
import dev.spatialfin.companion.wear.presentation.theme.WearVectorIcon
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The action ring — everything that used to be a stacked pill on the player.
 *
 * Six 48dp targets laid out on a circle plus the Split-A/V toggle in the middle.
 * The point is that all seven are reachable without scrolling: on a round screen a
 * ring fits more 48dp targets above the fold than a column ever can.
 */
@Composable
fun WearActionRingScreen(
    onOpenAudio: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenNextUp: () -> Unit,
    onOpenSpatial: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenPrivateAudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSinkActive by WearAudioReceiverService.isSinkActive.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06070A))
            .pointerInput(Unit) {
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = { if (travel > SWIPE_DOWN_THRESHOLD_PX) onDismiss() },
                ) { _, dragAmount -> travel += dragAmount }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(WearDarkPrimaryContainer.copy(alpha = 0.34f), Color.Transparent),
                    ),
                ),
        )

        Text(
            text = "ACTIONS",
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.1.em,
            color = WearDarkOutline,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        )

        // Six seats, starting at 12 o'clock and stepping 60 degrees, on a ring whose
        // centre is pushed below the screen's so the top seat clears the title.
        val handlers = mapOf(
            RingAction.Audio to onOpenAudio,
            RingAction.Subtitles to onOpenSubtitles,
            RingAction.Chapters to onOpenChapters,
            RingAction.NextUp to onOpenNextUp,
            RingAction.Recenter to onOpenSpatial,
            RingAction.Voice to onOpenVoice,
        )

        RING_ITEMS.forEachIndexed { index, item ->
            val angle = Math.toRadians(-90.0 + index * 60.0)
            RingTarget(
                icon = item.icon,
                label = item.label,
                highlighted = item.highlighted,
                onClick = handlers.getValue(item.action),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = (RING_RADIUS_DP * cos(angle)).roundToInt().dp,
                        y = (RING_RADIUS_DP * sin(angle) + RING_CENTRE_DROP_DP).roundToInt().dp,
                    ),
            )
        }

        // Split-A/V in the middle: it is a state, not a destination, so it reads
        // as the hub rather than a seventh spoke.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .size(63.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D0F14))
                .border(1.dp, WearDarkPrimary.copy(alpha = 0.34f), CircleShape)
                .clickable(onClick = onOpenPrivateAudio),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            WearVectorIcon(
                icon = WearIcons.Headphones,
                contentDescription = "Private audio",
                tint = WearDarkPrimary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Earbuds",
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Medium,
                color = WearDarkOnSurfaceVariant,
            )
            Text(
                text = if (isSinkActive) "ON" else "OFF",
                fontSize = 7.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.06.em,
                color = if (isSinkActive) WearMint else WearDarkOutline,
            )
        }
    }
}

@Composable
private fun RingTarget(
    icon: WearIcon,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (highlighted) WearDarkPrimaryContainer else WearDarkSurfaceContainer)
            .border(
                width = 1.dp,
                color = if (highlighted) WearDarkOnPrimaryContainer.copy(alpha = 0.24f) else WearGlassBorder,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WearVectorIcon(
            icon = icon,
            contentDescription = label,
            tint = if (highlighted) WearDarkOnPrimaryContainer else WearTitleBright,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (highlighted) WearDarkOnPrimaryContainer else WearDarkOnSurfaceVariant,
        )
    }
}

/** Seat identity, so a seat's handler never depends on its display label. */
private enum class RingAction { Audio, Subtitles, Chapters, NextUp, Recenter, Voice }

private data class RingItem(
    val action: RingAction,
    val icon: WearIcon,
    val label: String,
    val highlighted: Boolean = false,
)

/** Clockwise from 12 o'clock, matching the design's seat order. */
private val RING_ITEMS = listOf(
    RingItem(RingAction.Audio, WearIcons.VolumeSmall, "Audio"),
    RingItem(RingAction.Voice, WearIcons.Mic, "Voice", highlighted = true),
    RingItem(RingAction.Recenter, WearIcons.Target, "Recenter"),
    RingItem(RingAction.NextUp, WearIcons.Tv, "Up Next"),
    RingItem(RingAction.Chapters, WearIcons.ListOrdered, "Chapters"),
    RingItem(RingAction.Subtitles, WearIcons.CaptionsSmall, "Subs"),
)

/**
 * Seat centres sit 71dp out — the design's 142px on a 454px face.
 *
 * The two constants are one decision, not two. Shrinking the radius alone is what
 * makes the top seat clear the "ACTIONS" title, and it also walks all six seats
 * into the 31.5dp hub. The design instead keeps the radius and drops the ring's
 * centre 14.5dp below the screen's, buying the title its headroom out of the slack
 * at 6 o'clock. The hub does *not* move with it — it stays on the screen centre,
 * which is why the top seat clears it by only 1dp while the bottom seat clears it
 * by 30dp.
 *
 * Bezel check: the lowest seat's far edge lands 109.5dp from the centre of a
 * 113.5dp screen, so nothing is clipped.
 */
private const val RING_RADIUS_DP = 71.0
private const val RING_CENTRE_DROP_DP = 14.5
private const val SWIPE_DOWN_THRESHOLD_PX = 40f
