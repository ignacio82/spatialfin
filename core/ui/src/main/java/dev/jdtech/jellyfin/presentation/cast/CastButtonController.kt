package dev.jdtech.jellyfin.presentation.cast

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal cast-button surface the browse UI needs, decoupled from the app's
 * `:fcast` `CastSessionManager`. The browse components (e.g. `ItemButtonsBar`)
 * render a cast affordance from this seam so they can live in feature modules
 * without depending on `dev.spatialfin.fcast.session.*` (and on TV, where there
 * is no session, the `CompositionLocal` is simply `null`).
 *
 * The app provides the concrete implementation (an adapter over
 * `CastSessionManager`) via [LocalCastButtonController], the same place it
 * provides `LocalFCastSession`.
 */
interface CastButtonController {
    /** Currently-selected cast receiver, or `null` when nothing is picked. */
    val pickedTarget: StateFlow<Any?>

    /** Open the cast-target picker. */
    fun showPicker()
}

/** Null when no cast session is in scope (e.g. TV), or before one is provided. */
val LocalCastButtonController = compositionLocalOf<CastButtonController?> { null }
