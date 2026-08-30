package dev.spatialfin.companion.wear.presentation.theme

import androidx.compose.runtime.Composable
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
    MaterialTheme(
        colorScheme = WearColorScheme,
        typography = WearTypography,
        content = content,
    )
}
