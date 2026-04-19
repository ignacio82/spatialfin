package dev.jdtech.jellyfin.settings.presentation.models

/**
 * Preferences that render as a tappable row with a dynamic summary line. The
 * actual editing UI lives in a dialog the host screen opens via [Preference.onClick]-style
 * flows, so the card itself only needs the summary string to display.
 */
interface SummaryPreference : Preference {
    val summary: String
}
