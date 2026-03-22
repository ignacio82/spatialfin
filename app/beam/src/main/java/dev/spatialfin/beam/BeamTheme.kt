package dev.spatialfin.beam

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val BeamColorScheme =
    darkColorScheme(
        primary = Color(0xFFA4C9FE),
        onPrimary = Color(0xFF00315C),
        primaryContainer = Color(0xFF1F4876),
        onPrimaryContainer = Color(0xFFD3E3FF),
        secondary = Color(0xFFBCC7DB),
        onSecondary = Color(0xFF263141),
        secondaryContainer = Color(0xFF3C4758),
        onSecondaryContainer = Color(0xFFD8E3F8),
        tertiary = Color(0xFFD9BDE3),
        onTertiary = Color(0xFF3C2947),
        tertiaryContainer = Color(0xFF543F5E),
        onTertiaryContainer = Color(0xFFF5D9FF),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE1E2E8),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE1E2E8),
        surfaceVariant = Color(0xFF43474E),
        onSurfaceVariant = Color(0xFFC3C6CF),
        surfaceContainerLowest = Color(0xFF0C0E13),
        surfaceContainerLow = Color(0xFF191C20),
        surfaceContainer = Color(0xFF1D2024),
        surfaceContainerHigh = Color(0xFF272A2F),
        surfaceContainerHighest = Color(0xFF32353A),
        outline = Color(0xFF8D9199),
        outlineVariant = Color(0xFF43474E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = Color(0xFFE1E2E8),
        inverseOnSurface = Color(0xFF2E3035),
        inversePrimary = Color(0xFF3A608F),
        scrim = Color(0xFF000000),
    )

@Composable
fun BeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BeamColorScheme,
        typography = MaterialTheme.typography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF111318),
        ) {
            content()
        }
    }
}
