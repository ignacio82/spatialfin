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
        primary = Color(0xFF7DDAFF),
        onPrimary = Color(0xFF072538),
        primaryContainer = Color(0xFF0F3F5D),
        onPrimaryContainer = Color(0xFFD9F4FF),
        secondary = Color(0xFFFFBE74),
        onSecondary = Color(0xFF422300),
        secondaryContainer = Color(0xFF5B3611),
        onSecondaryContainer = Color(0xFFFFE5C6),
        tertiary = Color(0xFF6EE4C5),
        onTertiary = Color(0xFF012B23),
        tertiaryContainer = Color(0xFF0E473B),
        onTertiaryContainer = Color(0xFFD5FFF3),
        background = Color(0xFF06111B),
        onBackground = Color(0xFFE5EEF7),
        surface = Color(0xFF0D1824),
        onSurface = Color(0xFFE5EEF7),
        surfaceVariant = Color(0xFF223245),
        onSurfaceVariant = Color(0xFFB7C6D8),
        outline = Color(0xFF5C7087),
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
