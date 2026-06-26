package dev.jdtech.jellyfin.presentation.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import dev.jdtech.jellyfin.models.SpatialFinItem

/**
 * Seam for the Music Assistant long-press actions sheet shown over a home-row
 * card. Browse UI (the film `HomeScreen`) renders it through this composable-
 * lambda `CompositionLocal` instead of referencing the app's
 * `MaCardActionsMenu` + `LocalMaPlayDispatcher` directly, so the film package
 * can live in a feature module without an `:app` Music Assistant dependency.
 *
 * The app (XR `NavigationRoot`) provides an implementation that reads
 * `LocalMaPlayDispatcher.current` and calls `MaCardActionsMenu`. `null` where MA
 * isn't wired (the default), in which case the long-press menu is simply absent.
 */
val LocalMaCardActionsRenderer =
    compositionLocalOf<(@Composable (item: SpatialFinItem, onDismiss: () -> Unit) -> Unit)?> { null }
