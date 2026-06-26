package dev.jdtech.jellyfin.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Seams for the two settings preview surfaces that are inherently coupled to
 * `:player:xr` (TTS voice synthesis and libass subtitle rendering). The XR
 * settings screen lives in a feature module that must not depend on `:player:xr`
 * (the single dex-merge path), so it renders these through composable-lambda
 * `CompositionLocal`s instead of referencing the player-backed components.
 *
 * The app (XR `NavigationRoot`) provides implementations backed by the
 * app-resident `VoicePickerDialog` (wraps `SpatialVoiceSynthesizer`) and
 * `SubtitlePreviewCard` (wraps `LibassRenderer`). `null` where they aren't wired
 * (the default), in which case the surface is simply absent.
 */
val LocalVoicePickerDialog =
    compositionLocalOf<(@Composable (
        initialVoiceName: String?,
        onSave: (String?) -> Unit,
        onDismissRequest: () -> Unit,
    ) -> Unit)?> { null }

/**
 * Seam for the subtitle-style preview card. The provider closes over the
 * `AppPreferences` instance, so the call site only supplies a [Modifier].
 */
val LocalSubtitlePreviewCard =
    compositionLocalOf<(@Composable (modifier: Modifier) -> Unit)?> { null }
