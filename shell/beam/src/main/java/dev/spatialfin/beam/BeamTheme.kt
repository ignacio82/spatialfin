package dev.spatialfin.beam

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

/**
 * Corner-radius scale from the SpatialFin design system (tokens/spacing.css):
 * 10dp small, 16dp media cards, 32dp dialogs/spatial panels, full-pill for chips.
 * Wired into [MaterialTheme] so default-shaped M3 components inherit it.
 */
private val BeamShapes =
    Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

/**
 * Design tokens that have no Material3 colorScheme slot — the glass system and the
 * lone warm accent (rating star). Values mirror the design system's
 * tokens/colors.css + tokens/spacing.css. Reach for these instead of hardcoding;
 * neon mascot colors stay out of UI (DESIGN.md: logos & marketing only).
 */
object BeamTokens {
    /** darkSurface @ 62% — glass over passthrough/video. */
    val GlassFill = Color(0x9E111318)

    /** darkSurface @ 88% — glass for dense controls and dialogs. */
    val GlassFillStrong = Color(0xE0111318)

    /** Hairline translucent border on glass surfaces. */
    val GlassBorder = Color(0x24E1E2E8)

    /** Backdrop blur radius for glass surfaces. */
    val GlassBlur = 24.dp

    /** The only warm accent in normal UI — the IMDb-style rating star. */
    val RatingStar = Color(0xFFF2C94C)
}

@Composable
fun BeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BeamColorScheme,
        typography = MaterialTheme.typography,
        shapes = BeamShapes,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF111318),
        ) {
            content()
        }
    }
}
