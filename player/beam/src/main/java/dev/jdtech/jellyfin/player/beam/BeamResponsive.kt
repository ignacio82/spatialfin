package dev.jdtech.jellyfin.player.beam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Lightweight width-breakpoint enum used across Beam screens so layouts can
 * collapse on narrow phones (portrait) and expand on wider devices (Beam Pro
 * landscape, tablets, foldables opened).
 *
 * We avoid the Material3 WindowSizeClass library because a single read of
 * `screenWidthDp` from LocalConfiguration is enough — Beam never needs the
 * height bucket or the more elaborate windowing the adaptive APIs provide.
 *
 * Lives in :player:beam so both the app shell (app/beam) and the player
 * activity (player/beam) can consume it without circular module deps.
 */
enum class BeamWidth {
    /** Portrait phones. `screenWidthDp` < 600. Sidebar collapses to drawer, poster rails wrap. */
    Compact,

    /** Large phones landscape / small tablets. 600..839. Keep sidebar but narrow. */
    Medium,

    /** Beam Pro landscape, tablets, desktop. >= 840. Full sidebar + multi-column. */
    Expanded,
}

/** `true` when the current width is narrow enough to warrant single-column, drawer-nav layouts. */
val BeamWidth.isCompact: Boolean get() = this == BeamWidth.Compact

val LocalBeamWidth = compositionLocalOf { BeamWidth.Expanded }

@Composable
@ReadOnlyComposable
fun rememberBeamWidth(): BeamWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> BeamWidth.Compact
        widthDp < 840 -> BeamWidth.Medium
        else -> BeamWidth.Expanded
    }
}
