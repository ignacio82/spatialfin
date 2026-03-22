package dev.spatialfin.tv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val TvColorScheme =
    darkColorScheme(
        primary = Color(0xFFA4C9FE),
        onPrimary = Color(0xFF05294D),
        primaryContainer = Color(0xFF1F4876),
        onPrimaryContainer = Color(0xFFDCEAFF),
        secondary = Color(0xFFB9C7DA),
        onSecondary = Color(0xFF253140),
        secondaryContainer = Color(0xFF394759),
        onSecondaryContainer = Color(0xFFD9E3F5),
        background = Color(0xFF0C1016),
        onBackground = Color(0xFFE2E7EF),
        surface = Color(0xFF10151D),
        onSurface = Color(0xFFE2E7EF),
        surfaceVariant = Color(0xFF2A3442),
        onSurfaceVariant = Color(0xFFBEC7D4),
        outline = Color(0xFF667386),
    )

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvColorScheme,
        typography = MaterialTheme.typography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = TvColorScheme.background,
        ) {
            content()
        }
    }
}
