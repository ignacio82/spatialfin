package dev.jdtech.jellyfin.film.domain

import android.content.Context
import dev.jdtech.jellyfin.settings.domain.AppPreferences

/**
 * The viewer's language configuration, as both the detail-screen track chips
 * and the in-player track selector read it.
 *
 * Bundled because these four always travel together: resolving "which track
 * will play" needs the preferred languages *and* the understood-language list
 * *and* the forced-subtitle setting, and a call site that forgets one silently
 * produces a different answer from the player.
 */
data class LanguagePreferences(
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    /** Languages the viewer understands; subtitles default off when audio is one of them. */
    val spokenLanguages: List<String> = emptyList(),
    val forcedSubtitlesInUnderstoodAudio: Boolean = true,
)

/**
 * Reads the viewer's language configuration. Mirrors what `PlaylistManager` and
 * `PlayerTrackSelector` consult, so the chips agree with playback.
 *
 * Uses the non-anime preferences: the detail screen has no reliable anime
 * signal (`PlaylistManager` infers it from the media sources it has already
 * fetched), and non-anime is the correct default for everything else.
 */
fun AppPreferences.languagePreferences(context: Context): LanguagePreferences =
    LanguagePreferences(
        preferredAudioLanguage = getValue(nonAnimeAudioLanguage) ?: getValue(preferredAudioLanguage),
        preferredSubtitleLanguage = getValue(nonAnimeSubtitleLanguage) ?: getValue(preferredSubtitleLanguage),
        spokenLanguages = getSmartSpokenLanguageCodes(context),
        forcedSubtitlesInUnderstoodAudio = getValue(smartForcedSubtitles),
    )
