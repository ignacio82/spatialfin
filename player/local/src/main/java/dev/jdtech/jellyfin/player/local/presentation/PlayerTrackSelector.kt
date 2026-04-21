package dev.jdtech.jellyfin.player.local.presentation

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.presentation.PlayerTrackSignatures
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
import timber.log.Timber

/**
 * Track-selection + smart language logic lifted out of PlayerViewModel.
 *
 * Owns the auto-selection dedupe cursor (`lastAutoLanguageSelectionMediaId`), applies
 * the anime / spoken-language / series-override ranking that picks audio and subtitle
 * tracks, and drives manual `switchToTrack` edits. The host forwards `visualSubtitlesEnabled`
 * writes back into the ViewModel's UiState.
 */
internal class PlayerTrackSelector(
    private val application: Application,
    private val appPreferences: AppPreferences,
    private val host: Host,
) {

    interface Host {
        val player: Player
        fun currentPlayerItem(): PlayerItem?
        fun setVisualSubtitlesEnabled(enabled: Boolean)
    }

    private var lastAutoLanguageSelectionMediaId: String? = null

    /** Called from `onMediaItemTransition` so the next track apply pass re-runs smart selection. */
    fun resetAutoSelection() {
        lastAutoLanguageSelectionMediaId = null
    }

    fun applySmart() {
        val player = host.player
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (lastAutoLanguageSelectionMediaId == mediaId) return

        val spokenLanguages = appPreferences.getSmartSpokenLanguageCodes(application)
        val currentItem = host.currentPlayerItem() ?: return
        val seriesOverride = host.currentPlayerItem()?.seriesId?.let {
            appPreferences.getSeriesLanguageOverride(it.toString())
        }
        val audioGroups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (audioGroups.isEmpty()) return
        val subtitleGroups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }

        val isAnime =
            currentItem.genres.any { genre -> genre.contains("anime", ignoreCase = true) } ||
                (
                    audioGroups.any { group -> groupMatchesLanguage(group, "jpn") } &&
                        subtitleGroups.any { group ->
                            val mime = group.getTrackFormat(0).sampleMimeType.orEmpty()
                            mime == "text/x-ssa" || mime == MimeTypes.TEXT_SSA
                        }
                )
        val preferredAudioForContent =
            if (isAnime) {
                appPreferences.getValue(appPreferences.animeAudioLanguage)
            } else {
                appPreferences.getValue(appPreferences.nonAnimeAudioLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredAudioLanguage)
            }
        val preferredSubtitleForContent =
            if (isAnime) {
                appPreferences.getValue(appPreferences.animeSubtitleLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
            } else {
                appPreferences.getValue(appPreferences.nonAnimeSubtitleLanguage)
                    ?: appPreferences.getValue(appPreferences.preferredSubtitleLanguage)
            }
        val defaultSubtitleDisabled = !isAnime && appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled)

        val inferredOriginalAudio = inferOriginalAudioLanguage(audioGroups, spokenLanguages)
        val seriesOverrideAudio = seriesOverride?.audioLanguageCode
        val seriesOverrideAudioSignature = seriesOverride?.audioTrackSignature
        val selectedAudioGroup =
            when {
                seriesOverrideAudioSignature != null ->
                    audioGroups.firstOrNull { group ->
                        audioTrackSignature(group) == seriesOverrideAudioSignature
                    } ?: audioGroups.firstOrNull {
                        seriesOverrideAudio != null && groupMatchesLanguage(it, seriesOverrideAudio)
                    } ?: audioGroups.first()
                seriesOverrideAudio != null ->
                    audioGroups.firstOrNull {
                        groupMatchesLanguage(it, seriesOverrideAudio)
                    } ?: audioGroups.first()
                !preferredAudioForContent.isNullOrBlank() ->
                    audioGroups.firstOrNull {
                        groupMatchesLanguage(it, preferredAudioForContent)
                    } ?: audioGroups.first()
                appPreferences.getValue(appPreferences.smartPreferOriginalAudio) &&
                    inferredOriginalAudio != null -> {
                    audioGroups.firstOrNull {
                        groupMatchesLanguage(it, inferredOriginalAudio)
                    } ?: audioGroups.first()
                }
                else ->
                    spokenLanguages.firstNotNullOfOrNull { preferredCode ->
                        audioGroups.firstOrNull { group ->
                            groupMatchesLanguage(group, preferredCode)
                        }
                    } ?: audioGroups.first()
            }

        val selectedAudioLanguage = groupPrimaryLanguage(selectedAudioGroup)
        val audioUnderstood =
            if (selectedAudioLanguage == null) {
                // No language tag on the audio track. Defaulting to false would flip the fallback
                // branch to "force-on spoken-language subs" for untagged English dubs, which is
                // the opposite of what a user who has "subs off by default" set actually wants.
                true
            } else {
                spokenLanguages.any { preferredCode ->
                    LanguageCatalog.matches(application, selectedAudioLanguage, preferredCode)
                }
            }

        val seriesOverrideSubtitle = seriesOverride?.subtitleLanguageCode
        val seriesOverrideSubtitleSignature = seriesOverride?.subtitleTrackSignature
        val selectedSubtitleGroup =
            if (seriesOverride?.subtitlesEnabled == false) {
                null
            } else if (seriesOverrideSubtitleSignature != null) {
                subtitleGroups.firstOrNull { group ->
                    subtitleTrackSignature(group) == seriesOverrideSubtitleSignature
                } ?: subtitleGroups
                    .filter { group ->
                        seriesOverrideSubtitle != null &&
                            groupMatchesLanguage(group, seriesOverrideSubtitle)
                    }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = seriesOverrideSubtitle) }
            } else if (seriesOverrideSubtitle != null) {
                subtitleGroups
                    .filter { group -> groupMatchesLanguage(group, seriesOverrideSubtitle) }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = seriesOverrideSubtitle) }
            } else if (defaultSubtitleDisabled && audioUnderstood) {
                null
            } else if (!preferredSubtitleForContent.isNullOrBlank()) {
                subtitleGroups
                    .filter { group -> groupMatchesLanguage(group, preferredSubtitleForContent) }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = preferredSubtitleForContent) }
                    ?.takeIf { !audioUnderstood || isAnime }
            } else if (audioUnderstood) {
                null
            } else {
                spokenLanguages.firstNotNullOfOrNull { preferredCode ->
                    subtitleGroups
                        .filter { group -> groupMatchesLanguage(group, preferredCode) }
                        .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = preferredCode) }
                }
            }

        val builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(selectedAudioLanguage)
            .setOverrideForType(
                TrackSelectionOverride(selectedAudioGroup.mediaTrackGroup, 0)
            )

        if (selectedSubtitleGroup != null) {
            host.setVisualSubtitlesEnabled(true)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(
                TrackSelectionOverride(selectedSubtitleGroup.mediaTrackGroup, 0)
            )
            builder.setIgnoredTextSelectionFlags(0)
        } else {
            host.setVisualSubtitlesEnabled(false)
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }

        player.trackSelectionParameters = builder.build()
        lastAutoLanguageSelectionMediaId = mediaId

        Timber.d(
            "Smart language track prefs: isAnime=%b preferOriginal=%b contentAudio=%s contentSubtitle=%s seriesOverride=%s inferredOriginal=%s spoken=%s selectedAudio=%s subtitlesDisabled=%b selectedSubtitle=%s subtitleSignature=%s",
            isAnime,
            appPreferences.getValue(appPreferences.smartPreferOriginalAudio),
            preferredAudioForContent,
            preferredSubtitleForContent,
            seriesOverride,
            inferredOriginalAudio,
            spokenLanguages.joinToString(","),
            selectedAudioLanguage,
            selectedSubtitleGroup == null,
            groupPrimaryLanguage(selectedSubtitleGroup),
            selectedSubtitleGroup?.let(::subtitleTrackSignature),
        )
    }

    fun switchToTrack(trackType: @C.TrackType Int, index: Int) {
        val player = host.player
        val groups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }

        if (trackType == C.TRACK_TYPE_TEXT) {
            host.setVisualSubtitlesEnabled(index != -1)
        }

        if (index == -1) {
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            persistSeriesLanguageOverride(
                trackType = trackType,
                languageCode = null,
                trackSignature = null,
                enabled = false,
            )
        } else {
            val selectedGroup = groups[index]
            val selectedLanguage = groupPrimaryLanguage(selectedGroup)
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(selectedGroup.mediaTrackGroup, 0),
                    )
                    .setTrackTypeDisabled(trackType, false)
                    .apply {
                        if (trackType == C.TRACK_TYPE_TEXT) {
                            setIgnoredTextSelectionFlags(0)
                        }
                    }
                    .build()

            if (trackType == C.TRACK_TYPE_AUDIO) {
                persistSeriesLanguageOverride(
                    trackType = trackType,
                    languageCode = selectedLanguage,
                    trackSignature = audioTrackSignature(selectedGroup),
                    enabled = true,
                )
                maybeEnableSubtitleForManualAudioSelection(selectedLanguage)
            } else {
                persistSeriesLanguageOverride(
                    trackType = trackType,
                    languageCode = selectedLanguage,
                    trackSignature = subtitleTrackSignature(selectedGroup),
                    enabled = true,
                )
            }
        }
    }

    private fun maybeEnableSubtitleForManualAudioSelection(audioLanguageCode: String?) {
        val normalizedAudio = LanguageCatalog.normalize(application, audioLanguageCode) ?: return
        val spokenLanguages = appPreferences.getSmartSpokenLanguageCodes(application)
        val audioUnderstood =
            spokenLanguages.any { preferred ->
                LanguageCatalog.matches(application, normalizedAudio, preferred)
            }
        if (audioUnderstood) return

        val player = host.player
        val subtitleGroups =
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
        val selectedSubtitleGroup =
            spokenLanguages.firstNotNullOfOrNull { preferredCode ->
                subtitleGroups
                    .filter { group -> groupMatchesLanguage(group, preferredCode) }
                    .maxByOrNull { scoreSubtitleGroup(it, preferredLanguageCode = preferredCode) }
            } ?: return

        val subtitleLanguage = groupPrimaryLanguage(selectedSubtitleGroup)
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setIgnoredTextSelectionFlags(0)
                .setOverrideForType(
                    TrackSelectionOverride(selectedSubtitleGroup.mediaTrackGroup, 0)
                )
                .build()

        persistSeriesLanguageOverride(
            trackType = C.TRACK_TYPE_TEXT,
            languageCode = subtitleLanguage,
            trackSignature = subtitleTrackSignature(selectedSubtitleGroup),
            enabled = true,
        )
        Timber.d(
            "Enabled spoken-language subtitles after manual audio switch audio=%s subtitle=%s",
            normalizedAudio,
            subtitleLanguage,
        )
    }

    private fun inferOriginalAudioLanguage(
        audioGroups: List<Tracks.Group>,
        spokenLanguages: List<String>,
    ): String? {
        val availableLanguages = audioGroups.mapNotNull { groupPrimaryLanguage(it) }.distinct()
        if (availableLanguages.isEmpty()) return null
        if (availableLanguages.size == 1) return availableLanguages.first()

        return availableLanguages.firstOrNull { available ->
            spokenLanguages.none { preferred ->
                LanguageCatalog.matches(application, available, preferred)
            }
        } ?: availableLanguages.first()
    }

    private fun groupPrimaryLanguage(group: Tracks.Group?): String? {
        if (group == null) return null
        return (0 until group.length)
            .mapNotNull { index ->
                LanguageCatalog.normalize(
                    application,
                    group.getTrackFormat(index).language ?: group.getTrackFormat(index).label,
                )
            }
            .firstOrNull()
    }

    private fun groupMatchesLanguage(group: Tracks.Group, languageCode: String): Boolean {
        return (0 until group.length).any { index ->
            val format = group.getTrackFormat(index)
            LanguageCatalog.matches(application, format.language, languageCode) ||
                LanguageCatalog.matches(application, format.label, languageCode)
        }
    }

    private fun persistSeriesLanguageOverride(
        trackType: @C.TrackType Int,
        languageCode: String?,
        trackSignature: String?,
        enabled: Boolean,
    ) {
        val seriesId = host.currentPlayerItem()?.seriesId?.toString() ?: return
        val existingOverride = appPreferences.getSeriesLanguageOverride(seriesId) ?: SeriesLanguageOverride()
        val updatedOverride =
            when (trackType) {
                C.TRACK_TYPE_AUDIO ->
                    existingOverride.copy(
                        audioLanguageCode = if (enabled) languageCode else null,
                        audioTrackSignature = if (enabled) trackSignature else null,
                    )
                C.TRACK_TYPE_TEXT ->
                    existingOverride.copy(
                        subtitleLanguageCode = if (enabled) languageCode else null,
                        subtitleTrackSignature = if (enabled) trackSignature else null,
                        subtitlesEnabled = enabled,
                    )
                else -> existingOverride
            }

        appPreferences.setSeriesLanguageOverride(seriesId, updatedOverride)
        Timber.d("Saved series language override seriesId=%s override=%s", seriesId, updatedOverride)
    }

    private fun subtitleTrackSignature(group: Tracks.Group): String =
        PlayerTrackSignatures.subtitle(group)

    private fun audioTrackSignature(group: Tracks.Group): String =
        PlayerTrackSignatures.audio(group)

    private fun scoreSubtitleGroup(
        group: Tracks.Group,
        preferredLanguageCode: String?,
    ): Int {
        val format = group.getTrackFormat(0)
        val label = format.label.orEmpty().lowercase()
        var score = 0

        if (preferredLanguageCode != null && groupMatchesLanguage(group, preferredLanguageCode)) {
            score += 100
        }
        if (label.contains("full") || label.contains("dialog")) {
            score += 35
        }
        if (label.contains("default")) {
            score += 30
        }
        if ((format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0) {
            score += 25
        }
        if ((format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) {
            score -= 40
        }
        if (label.contains("sign") || label.contains("song")) {
            score -= 60
        }
        if (label.contains("forced")) {
            score -= 50
        }
        if (label.contains("sdh") || label.contains("cc")) {
            score -= 15
        }
        return score
    }
}
