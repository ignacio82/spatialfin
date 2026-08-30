package dev.spatialfin.companion.wear.presentation.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val WearColorScheme = ColorScheme(
    primary = WearDarkPrimary,
    primaryContainer = WearDarkPrimaryContainer,
    onPrimary = WearDarkOnPrimary,
    onPrimaryContainer = WearDarkOnPrimaryContainer,
    secondary = WearDarkSecondary,
    secondaryContainer = WearDarkSecondaryContainer,
    onSecondary = WearDarkOnSecondary,
    onSecondaryContainer = WearDarkOnSecondaryContainer,
    surfaceContainer = WearDarkSurfaceContainer,
    surfaceContainerHigh = WearDarkSurfaceVariant,
    onSurface = WearDarkOnSurface,
    onSurfaceVariant = WearDarkOnSurfaceVariant,
    outline = WearDarkOutline,
    error = WearDarkError,
    errorContainer = WearDarkErrorContainer,
    onError = WearDarkOnError,
    onErrorContainer = WearDarkOnErrorContainer,
    background = WearDarkSurface, // OLED pure black
    onBackground = WearDarkOnSurface,
)

@Composable
fun SpatialFinWearTheme(
    content: @Composable () -> Unit,
) {
    // LocalReduceMotion MUST be provided, not left to the library default.
    //
    // Wear Compose Foundation's default computes the value by reading the
    // `reduce_motion` Settings.Global key, which is annotated readable only up to
    // targetSdk 34. We target 36, so that read throws SecurityException — and it is
    // consumed inside TransformingLazyColumn's item provider, so every list screen
    // (audio, subtitles, chapters, spatial, device picker, Next Up) crashed on
    // launch until this provider was added. Providing a value here means the
    // library's default lambda never runs.
    //
    // ANIMATOR_DURATION_SCALE is the publicly readable reduced-motion signal, so
    // the accessibility preference is still honoured rather than hardcoded off.
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = WearColorScheme,
            typography = WearTypography,
            content = content,
        )
    }
}
