package dev.spatialfin.companion.wear.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * SpatialFin Wear OS Color Palette.
 *
 * NOTE: [WearBlack] (0xFF000000) is a deliberate, Wear-only override of
 * DESIGN.md's darkSurface (0xFF111318) to turn off inactive OLED pixels
 * and maximize battery life on smartwatches.
 */
val WearBlack = Color(0xFF000000)
val WearDarkPrimary = Color(0xFFA4C9FE)
val WearDarkPrimaryContainer = Color(0xFF1F4876)
val WearDarkOnPrimary = Color(0xFF00315B)
val WearDarkOnPrimaryContainer = Color(0xFFD2E4FF)
val WearDarkSecondary = Color(0xFFBCC7DB)
val WearDarkOnSecondary = Color(0xFF263140)
val WearDarkSecondaryContainer = Color(0xFF3C4858)
val WearDarkOnSecondaryContainer = Color(0xFFD8E3F8)
val WearDarkSurface = Color(0xFF000000) // Pure black for OLED
val WearDarkSurfaceContainer = Color(0xFF16181F)
val WearDarkSurfaceVariant = Color(0xFF1E212B)
val WearDarkOnSurface = Color(0xFFE2E2E9)
val WearDarkOnSurfaceVariant = Color(0xFFC4C6D0)
val WearDarkOutline = Color(0xFF8E9099)
val WearDarkError = Color(0xFFFFB4AB)
val WearDarkOnError = Color(0xFF690005)
val WearDarkErrorContainer = Color(0xFF93000A)
val WearDarkOnErrorContainer = Color(0xFFFFDAD6)
