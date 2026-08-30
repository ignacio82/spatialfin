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

// ---------------------------------------------------------------------------
// Redesign tokens ("The screen is the player", 2026-08-30).
//
// The arc-timeline surface needs a handful of values DESIGN.md's M3 roles do
// not carry: a scrub accent that is deliberately NOT the primary blue (so a
// crown turn is unmistakable), a mint for the Split-A/V sink, and glass fills
// for the chrome that floats over cover art.
// ---------------------------------------------------------------------------

/** Timeline track, at rest. */
val WearArcTrack = Color(0xFF2C323D)

/** Timeline track while the crown is scrubbing — lifts so the amber reads against it. */
val WearArcTrackActive = Color(0xFF556070)

/** Scrub accent. Amber, not primary: "the crown is moving you" must never be */
/** confusable with "this is where playback is". */
val WearScrubAmber = Color(0xFFFFD56B)

/** The scrub timecode itself, one step brighter than the arc. */
val WearScrubAmberBright = Color(0xFFFFE9AF)

/** Split-A/V sink. Borrowed from the TV scheme's tertiary — the only place */
/** the Wear app uses a non-blue status colour. */
val WearMint = Color(0xFF6EE4C5)
val WearOnMint = Color(0xFF012B23)

/** Title white — brighter than onSurface, for the one line of hero metadata. */
val WearTitleBright = Color(0xFFF2F3F8)

/** Chrome floating over artwork. Matches --glass-fill-strong at Wear's black base. */
val WearGlassFill = Color(0xD116181F)
val WearGlassBorder = Color(0x24E2E2E9)

/** Ambient (always-on): hairline arc, no fills, safe at 1-bit. */
val WearAmbientArcTrack = Color(0xFF2A2D35)
val WearAmbientArcProgress = Color(0xFF8E9099)

/** Destructive affordance backing (pairing reject). */
val WearRejectContainer = Color(0xFF2A1515)
