package dev.spatialfin.companion.wear.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.spatialfin.companion.wear.presentation.theme.WearAmbientArcProgress
import dev.spatialfin.companion.wear.presentation.theme.WearAmbientArcTrack
import dev.spatialfin.companion.wear.presentation.theme.WearArcTrack
import dev.spatialfin.companion.wear.presentation.theme.WearArcTrackActive
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkOnPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSecondary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSecondaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearScrubAmber
import dev.spatialfin.companion.wear.presentation.theme.WearScrubAmberBright
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bezel-arc geometry.
 *
 * A 310-degree sweep opening at the bottom, so the gap sits under the "Actions"
 * affordance and the timeline never runs behind it. In Compose's [DrawScope.drawArc]
 * frame zero degrees is 3 o'clock and sweep is clockwise, so 115 + 310 leaves a
 * 50-degree notch centred on 6 o'clock.
 *
 * The point of the arc: 310 degrees at a 108dp radius is ~1,170px of scrub travel
 * against the 369px a full-width linear bar could offer.
 */
const val ARC_START_DEGREES = 115f
const val ARC_SWEEP_DEGREES = 310f

/** What the timeline is currently expressing. */
enum class ArcTimelineState { Idle, Scrubbing, Volume, Ambient }

/**
 * The timeline. [progress] is 0..1 of the whole runtime.
 *
 * Ambient draws a hairline with no fills and no thumb — burn-in protection is the
 * reason the always-on branch exists at all, and a 5dp filled arc parked in one
 * position for two hours is exactly what it exists to prevent.
 *
 * [ArcTimelineState.Volume] thins the arc to 3dp and drops its fill to the muted
 * container tone, because in volume mode the crown is not driving this ring. It
 * stays on screen — seeing position and volume at once is the whole argument for
 * two rings — but it stops competing with the one the crown *is* driving.
 */
@Composable
fun ArcTimeline(
    progress: Float,
    state: ArcTimelineState,
    modifier: Modifier = Modifier,
) {
    val strokeWidth: Dp = when (state) {
        ArcTimelineState.Idle -> 5.dp
        ArcTimelineState.Scrubbing -> 6.dp
        ArcTimelineState.Volume -> 3.dp
        ArcTimelineState.Ambient -> 2.dp
    }
    val trackColor = when (state) {
        ArcTimelineState.Idle -> WearArcTrack
        ArcTimelineState.Scrubbing -> WearArcTrackActive
        ArcTimelineState.Volume -> WearArcTrack
        ArcTimelineState.Ambient -> WearAmbientArcTrack
    }
    val progressColor = when (state) {
        ArcTimelineState.Idle -> WearDarkPrimary
        ArcTimelineState.Scrubbing -> WearScrubAmber
        ArcTimelineState.Volume -> WearDarkSecondaryContainer
        ArcTimelineState.Ambient -> WearAmbientArcProgress
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f + 3.dp.toPx()
        val sweep = ARC_SWEEP_DEGREES * progress.coerceIn(0f, 1f)

        drawSweep(ARC_SWEEP_DEGREES, trackColor, stroke, inset)
        if (sweep > 0f) drawSweep(sweep, progressColor, stroke, inset)

        if (state == ArcTimelineState.Idle || state == ArcTimelineState.Scrubbing) {
            // The head. At rest it is a quiet 5.5dp dot; under the crown it grows a
            // halo, which is the only motion cue you get with the screen this close
            // to your face.
            val head = arcPointAt(sweep, inset)
            if (state == ArcTimelineState.Scrubbing) {
                drawCircle(WearScrubAmber.copy(alpha = 0.22f), radius = 10.dp.toPx(), center = head)
                drawCircle(WearScrubAmberBright, radius = 6.dp.toPx(), center = head)
            } else {
                drawCircle(WearDarkOnPrimaryContainer, radius = 5.5.dp.toPx(), center = head)
            }
        }
    }
}

/**
 * The volume ring — a second, tighter arc inside the timeline.
 *
 * Drawn concurrently with [ArcTimeline] rather than replacing it: the redesign's
 * whole argument against the old full-width crown-mode pill is that you should be
 * able to see position and volume at the same time.
 */
@Composable
fun ArcVolumeRing(
    volume: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = 7.dp.toPx()
        // Sits 12dp inside the timeline so the two never optically merge.
        val inset = stroke / 2f + 15.dp.toPx()
        drawSweep(ARC_SWEEP_DEGREES, WearArcTrack, stroke, inset)
        val sweep = ARC_SWEEP_DEGREES * volume.coerceIn(0f, 1f)
        if (sweep > 0f) drawSweep(sweep, WearDarkSecondary, stroke, inset)
    }
}

/**
 * Pairing countdown — a full ring, not a notched arc, because it is a clock rather
 * than a position and a gap would read as elapsed time.
 */
@Composable
fun ArcCountdownRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = WearDarkPrimary,
    strokeWidth: Dp = 4.dp,
    /** Extra gap between the ring and its bounds. Zero makes the ring hug the edge — */
    /** what a progress ring around a poster wants, so it never crops the art. */
    edgeInset: Dp = 3.dp,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f + edgeInset.toPx()
        drawCircle(
            color = WearArcTrack,
            radius = size.minDimension / 2f - inset,
            center = center,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private fun DrawScope.drawSweep(sweep: Float, color: Color, stroke: Float, inset: Float) {
    drawArc(
        color = color,
        startAngle = ARC_START_DEGREES,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

/** Cartesian position of the arc head, [sweep] degrees along from the start. */
private fun DrawScope.arcPointAt(sweep: Float, inset: Float): Offset {
    val radius = size.minDimension / 2f - inset
    val radians = Math.toRadians((ARC_START_DEGREES + sweep).toDouble())
    return Offset(
        x = center.x + radius * cos(radians).toFloat(),
        y = center.y + radius * sin(radians).toFloat(),
    )
}
