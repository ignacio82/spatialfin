package dev.jdtech.jellyfin.core.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Fladder-style pause overlay: title top-left, wall-clock + ETA top-right.
 *
 * Renders only when [visible] is true. Uses only Compose foundation + a
 * minimal core-friendly Text drawing — no Material dep, so this is safe to
 * call from any of the players (XR, Beam, TV).
 *
 * Text sizes are parameterized so each player can scale — TV bumps them for
 * couch distance, Beam keeps them phone-sized.
 */
@Composable
fun PlayerPauseOverlay(
    visible: Boolean,
    title: String,
    subtitle: String? = null,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(28.dp),
    titleFontSize: TextUnit = 28.sp,
    subtitleFontSize: TextUnit = 14.sp,
    clockFontSize: TextUnit = 18.sp,
    etaFontSize: TextUnit = 14.sp,
    /** Optional logo rendered top-left in place of the title text. Caller owns
     *  image loading (Coil lives in platform modules, not in :core). */
    logoSlot: (@Composable () -> Unit)? = null,
    /** When true, drops the dimming gradient and the top-left title/logo so only
     *  the top-right wall-clock remains. Used while the full player controls
     *  overlay is on-screen — the controls already show the logo and we don't
     *  want to double-dim the video. */
    minimal: Boolean = false,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .then(
                        if (minimal) {
                            Modifier
                        } else {
                            Modifier.background(
                                Brush.verticalGradient(
                                    colors = listOf(Color(0x99000000), Color.Transparent, Color.Transparent),
                                )
                            )
                        }
                    )
                    .padding(contentPadding)
        ) {
            if (!minimal) {
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    if (logoSlot != null) {
                        logoSlot()
                    } else {
                        androidx.compose.foundation.text.BasicText(
                            text = title,
                            style =
                                TextStyle(
                                    color = Color.White,
                                    fontSize = titleFontSize,
                                    fontWeight = FontWeight.Bold,
                                ),
                        )
                    }
                    if (!subtitle.isNullOrBlank()) {
                        androidx.compose.foundation.text.BasicText(
                            text = subtitle,
                            style =
                                TextStyle(
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = subtitleFontSize,
                                ),
                        )
                    }
                }
            }
            PauseClockContent(
                visible = visible,
                positionMs = positionMs,
                durationMs = durationMs,
                modifier = Modifier.align(Alignment.TopEnd),
                clockFontSize = clockFontSize,
                etaFontSize = etaFontSize,
            )
        }
    }
}

/**
 * Just the top-right digital clock + "Ends at XX:XX · Nm left" block, no backdrop
 * or positioning container. Use when you want the wall-clock affordance outside
 * of the full [PlayerPauseOverlay] — e.g. an XR SpatialPanel, or a phone/TV
 * overlay that keeps its own Box layout.
 *
 * Pass `visible` so the internal tick coroutine pauses when off-screen; the
 * composable itself always renders (the caller decides visibility). Rendering
 * is Material-free so this is safe to call from any player module.
 */
@Composable
fun PauseClockContent(
    visible: Boolean,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    clockFontSize: TextUnit = 18.sp,
    etaFontSize: TextUnit = 14.sp,
) {
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(visible) {
        while (visible) {
            tick = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val clock = remember(tick) { formatClock(tick) }
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val eta = remember(tick, remainingMs) { formatEta(tick, remainingMs) }
        val remaining = remember(remainingMs) { formatRemaining(remainingMs) }
        androidx.compose.foundation.text.BasicText(
            text = clock,
            style =
                TextStyle(
                    color = Color.White,
                    fontSize = clockFontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
        )
        androidx.compose.foundation.text.BasicText(
            text = "Ends at $eta · $remaining left",
            style =
                TextStyle(
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = etaFontSize,
                ),
        )
    }
}

private fun formatClock(nowMs: Long): String {
    val hour = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }.get(java.util.Calendar.MINUTE)
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val suffix = if (hour >= 12) "PM" else "AM"
    return "%d:%02d %s".format(hour12, minute, suffix)
}

private fun formatEta(nowMs: Long, remainingMs: Long): String = formatClock(nowMs + remainingMs)

private fun formatRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

