package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.spatialfin.presentation.theme.spacings

/**
 * Placeholder home shown while the first fetch is in flight.
 *
 * The home used to paint its loaded layout over empty state, which read as a
 * broken gray screen — and, because "no rows" was indistinguishable from "all
 * rows hidden", it also offered to restore hidden rows for a few seconds on
 * every cold start. Drawing the *shape* of the home instead makes the wait look
 * deliberate and stops the layout jumping when content lands.
 *
 * Only reached on a cold start or a cache miss; a warm start paints real
 * content from the home cache almost immediately.
 *
 * The card geometry is fully parameterised because the three shells disagree
 * about it: Beam uses 132dp 2:3 posters, the XR home 360dp 16:9 stills, the TV
 * home 300dp 16:9 stills. Pass the values the shell's own carousel uses, or the
 * skeleton will visibly resize as content arrives.
 */
@Composable
fun HomeSkeleton(
    modifier: Modifier = Modifier,
    cardWidth: Dp = 132.dp,
    cardAspect: Float = 0.67f,
    cardShape: Shape = RoundedCornerShape(16.dp),
    cardSpacing: Dp = 14.dp,
    rowCount: Int = 3,
    showHero: Boolean = true,
) {
    val shimmer = rememberShimmerBrush()
    Column(
        // Decorative: a screen reader should hear the loading announcement the
        // shell already makes, not a dozen anonymous placeholder boxes.
        // `then(modifier)` rather than `modifier.fillMaxWidth()`, so a caller's
        // own width still wins and its padding insets the placeholders.
        modifier = Modifier.fillMaxWidth().then(modifier).semantics { hideFromAccessibility() },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
    ) {
        if (showHero) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(HERO_ASPECT)
                    .clip(RoundedCornerShape(MaterialTheme.spacings.medium))
                    .background(shimmer),
            )
        }
        repeat(rowCount) { index ->
            SkeletonRow(
                brush = shimmer,
                cardWidth = cardWidth,
                cardAspect = cardAspect,
                cardShape = cardShape,
                cardSpacing = cardSpacing,
                // Varying title widths stop the placeholder reading as a grid.
                titleWidth = if (index % 2 == 0) 120.dp else 168.dp,
            )
        }
    }
}

@Composable
private fun SkeletonRow(
    brush: Brush,
    cardWidth: Dp,
    cardAspect: Float,
    cardShape: Shape,
    cardSpacing: Dp,
    titleWidth: Dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small)) {
        Spacer(
            Modifier
                .width(titleWidth)
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(brush),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(cardSpacing)) {
            // More than can fit on any of the three form factors. Row hands the
            // card straddling the edge whatever width is left and the rest zero,
            // so the row self-truncates into a half-visible card — which is what
            // a scrollable shelf looks like anyway.
            //
            // The height is derived from the width rather than left to
            // `aspectRatio`, which is not safe under overflow: given a zero
            // width budget it abandons the width and matches the *height*
            // constraint instead, so every card past the row's edge inflated to
            // fill the screen and dragged the row's height up with it.
            val cardHeight = cardWidth / cardAspect
            repeat(CARDS_PER_ROW) {
                Spacer(
                    Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(cardShape)
                        .background(brush),
                )
            }
        }
    }
}

/**
 * A sweep that travels left-to-right across each placeholder.
 *
 * The gradient is built in local pixel space, so every box animates on the same
 * clock but sweeps within its own bounds — cheap, and close enough to a single
 * sweep across the screen that the difference is not visible in motion.
 */
@Composable
private fun rememberShimmerBrush(): Brush {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val transition = rememberInfiniteTransition(label = "home-skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "home-skeleton-sweep",
    )
    val start = (progress * 2f - 0.5f) * SWEEP_SPAN
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(start, 0f),
        end = Offset(start + SWEEP_SPAN, 0f),
    )
}

private const val HERO_ASPECT = 16f / 9f
private const val CARDS_PER_ROW = 8
private const val SWEEP_SPAN = 600f
