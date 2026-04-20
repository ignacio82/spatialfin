package dev.spatialfin.beam

import android.graphics.Color as AndroidColor
import android.widget.Toast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
import dev.jdtech.jellyfin.settings.presentation.models.IntSelectOption
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceLongInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceStringInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSwitch
import dev.spatialfin.presentation.settings.components.SubtitlePreviewCard
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockMode
import dev.spatialfin.unified.applock.PinSetupScreen
import kotlinx.coroutines.launch

/**
 * Categories exposed as drill-down cards on the Beam (and TV) settings hubs.
 * `keywords` is a space-separated search corpus used by the top-of-screen
 * filter box so typing "bitrate" or "spoiler" reveals the right card.
 */
internal data class BeamSettingsCategoryDef(
    val key: String,
    val title: String,
    val subtitle: String,
    val keywords: String,
)

internal val BEAM_SETTINGS_CATEGORIES = listOf(
    BeamSettingsCategoryDef(
        "language",
        "Language",
        "Audio & subtitle preferences",
        "audio subtitle language anime non-anime smart spoken",
    ),
    BeamSettingsCategoryDef(
        "playback",
        "Playback",
        "Quality, seek, chapters, skip behavior",
        "quality bitrate seek chapter trickplay skip segment libass rendering",
    ),
    BeamSettingsCategoryDef(
        "subtitles",
        "Subtitles",
        "Size, color, and background",
        "subtitle size color background text",
    ),
    BeamSettingsCategoryDef(
        "downloads",
        "Downloads",
        "Mobile data and roaming",
        "download mobile data roaming network",
    ),
    BeamSettingsCategoryDef(
        "security",
        "Security",
        "App lock, biometrics, encryption",
        "security lock pin biometric encrypt password",
    ),
    BeamSettingsCategoryDef(
        "seerr",
        "Jellyseerr",
        "Request integration",
        "seerr jellyseerr request overseerr",
    ),
    BeamSettingsCategoryDef(
        "voice",
        "Voice assistant",
        "Speech commands and replies",
        "voice speech verbosity spoiler gemini ai reply",
    ),
    BeamSettingsCategoryDef(
        "companion",
        "Companion",
        "Connection status",
        "companion connect device pair",
    ),
    BeamSettingsCategoryDef(
        "diagnostics",
        "Diagnostics",
        "Logs and telemetry",
        "diagnostic log telemetry upload debug",
    ),
)

private fun matchesSearch(category: BeamSettingsCategoryDef, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return category.title.lowercase().contains(q) ||
        category.subtitle.lowercase().contains(q) ||
        category.keywords.lowercase().contains(q)
}

@Composable
fun BeamSettingsScreen(
    contentPadding: PaddingValues,
    appPreferences: AppPreferences,
    onOpenCompanion: () -> Unit,
) {
    var preferredAudioLanguage by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.preferredAudioLanguage).orEmpty())
    }
    var preferredSubtitleLanguage by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.preferredSubtitleLanguage).orEmpty())
    }
    var animeAudioLanguage by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.animeAudioLanguage).orEmpty())
    }
    var animeSubtitleLanguage by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.animeSubtitleLanguage).orEmpty())
    }
    var nonAnimeAudioLanguage by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.nonAnimeAudioLanguage).orEmpty())
    }
    var nonAnimeSubtitleDisabled by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled))
    }
    var nonAnimeSubtitleLanguage by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.nonAnimeSubtitleLanguage).orEmpty())
    }
    var smartPreferOriginalAudio by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.smartPreferOriginalAudio))
    }
    var smartSpokenLanguages by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.smartSpokenLanguages).orEmpty())
    }
    var playerSeekBackInc by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.playerSeekBackInc) / 1000L)
    }
    var playerSeekForwardInc by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.playerSeekForwardInc) / 1000L)
    }
    var chapterMarkers by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.playerChapterMarkers))
    }
    var trickplay by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.playerTrickplay))
    }
    var playerMaxBitrate by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.playerMaxBitrate))
    }
    var skipButton by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton))
    }
    var autoSkip by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip))
    }
    var nextEpisodeThreshold by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsNextEpisodeThreshold) / 1000L)
    }
    var downloadOverMobileData by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.downloadOverMobileData))
    }
    var downloadWhenRoaming by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.downloadWhenRoaming))
    }
    var seerrEnabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrEnabled)) }
    var seerrUrl by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrUrl).orEmpty()) }
    var seerrApiKey by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrApiKey).orEmpty()) }
    var libassUsage by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.libassSubtitleUsage)) }
    var subtitleSize by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.xrSubtitleSize).toLong())
    }
    var subtitleTextColor by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.subtitleTextColor).toLong())
    }
    var subtitleBackgroundColor by rememberSaveable {
        mutableLongStateOf(appPreferences.getValue(appPreferences.subtitleBackgroundColor).toLong())
    }
    var voiceEnabled by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceControlEnabled))
    }
    var voiceVerbosity by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantVerbosity))
    }
    var voiceSpoilerPolicy by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy))
    }
    var spokenRepliesEnabled by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies))
    }
    var voiceCloudApiKey by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty())
    }
    var loggingEnabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.loggingEnabled)) }
    val context = LocalContext.current
    val appLockManager = remember(context) { AppLockManager.from(context) }
    val appLockScope = rememberCoroutineScope()
    var appLockMode by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.appLockMode))
    }
    var contentEncryption by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.contentEncryptionEnabled))
    }
    var pinSetupVisible by rememberSaveable { mutableStateOf(false) }

    val companionConnected =
        remember(appPreferences.getValue(appPreferences.companionUrl), appPreferences.getValue(appPreferences.companionToken)) {
            appPreferences.getValue(appPreferences.companionUrl).isNotBlank() &&
                appPreferences.getValue(appPreferences.companionToken).isNotBlank()
        }

    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // A category renders if (a) it's the selected drill-down, or (b) search is
    // active and the query hits this category's title/subtitle/keywords. The
    // hub (no selection, no search) shows the grid of cards instead.
    val showHubGrid = selectedCategory == null && searchQuery.isBlank()
    val matchingCategories = if (searchQuery.isBlank()) emptyList() else BEAM_SETTINGS_CATEGORIES.filter { matchesSearch(it, searchQuery) }
    fun shouldShow(key: String): Boolean {
        if (searchQuery.isNotBlank()) return matchingCategories.any { it.key == key }
        return selectedCategory == key
    }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Settings",
                body = "Playback, subtitles, downloads, and more.",
            )
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    selectedCategory = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search settings") },
                singleLine = true,
                placeholder = { Text("Try \"bitrate\" or \"subtitle\"") },
            )
        }
        if (selectedCategory != null) {
            item {
                TextButton(onClick = { selectedCategory = null }) {
                    Text("← Back to Settings")
                }
            }
        }
        if (showHubGrid) {
            items(BEAM_SETTINGS_CATEGORIES.size) { index ->
                val cat = BEAM_SETTINGS_CATEGORIES[index]
                BeamSettingsCategoryCard(
                    title = cat.title,
                    subtitle = cat.subtitle,
                    onClick = { selectedCategory = cat.key },
                )
            }
        } else if (searchQuery.isNotBlank() && matchingCategories.isEmpty()) {
            item {
                Text(
                    "No settings match \"$searchQuery\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (shouldShow("language")) {
            item {
                BeamSettingsSection(title = "Language") {
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_preferred_audio_language,
                            placeholderRes = SettingsR.string.language_code_placeholder,
                            backendPreference = appPreferences.preferredAudioLanguage,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_preferred_subtitle_language,
                            placeholderRes = SettingsR.string.language_code_placeholder,
                            backendPreference = appPreferences.preferredSubtitleLanguage,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_anime_audio_language,
                            descriptionStringRes = SettingsR.string.settings_anime_audio_language_summary,
                            placeholderRes = SettingsR.string.language_code_placeholder,
                            backendPreference = appPreferences.animeAudioLanguage,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_anime_subtitle_language,
                            descriptionStringRes = SettingsR.string.settings_anime_subtitle_language_summary,
                            placeholderRes = SettingsR.string.language_code_placeholder,
                            backendPreference = appPreferences.animeSubtitleLanguage,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_non_anime_audio_language,
                            descriptionStringRes = SettingsR.string.settings_non_anime_audio_language_summary,
                            placeholderRes = SettingsR.string.language_code_placeholder,
                            backendPreference = appPreferences.nonAnimeAudioLanguage,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.settings_non_anime_subtitle_disabled,
                            descriptionStringRes = SettingsR.string.settings_non_anime_subtitle_disabled_summary,
                            backendPreference = appPreferences.nonAnimeSubtitleDisabled,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_non_anime_subtitle_language,
                            descriptionStringRes = SettingsR.string.settings_non_anime_subtitle_language_summary,
                            placeholderRes = SettingsR.string.language_code_placeholder,
                            backendPreference = appPreferences.nonAnimeSubtitleLanguage,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.smart_prefer_original_audio,
                            descriptionStringRes = SettingsR.string.smart_prefer_original_audio_summary,
                            backendPreference = appPreferences.smartPreferOriginalAudio,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.smart_spoken_languages,
                            descriptionStringRes = SettingsR.string.smart_spoken_languages_summary,
                            placeholderRes = SettingsR.string.smart_spoken_languages_placeholder,
                            backendPreference = appPreferences.smartSpokenLanguages,
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("playback")) {
            item {
                BeamSettingsSection(title = "Playback") {
                    BeamPreferenceRow(
                        preference = PreferenceSelect(
                            nameStringResource = SettingsR.string.libass_subtitle_usage,
                            descriptionStringRes = SettingsR.string.libass_subtitle_usage_summary,
                            backendPreference = appPreferences.libassSubtitleUsage,
                            options = SettingsR.array.libass_subtitle_usage_options,
                            optionValues = SettingsR.array.libass_subtitle_usage_values,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.pref_player_chapter_markers,
                            descriptionStringRes = SettingsR.string.pref_player_chapter_markers_summary,
                            backendPreference = appPreferences.playerChapterMarkers,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.pref_player_trickplay,
                            descriptionStringRes = SettingsR.string.pref_player_trickplay_summary,
                            backendPreference = appPreferences.playerTrickplay,
                        ),
                        appPreferences = appPreferences,
                    )
                    // Store milliseconds, show seconds — displayDivisor = 1000.
                    BeamPreferenceRow(
                        preference = PreferenceLongInput(
                            nameStringResource = SettingsR.string.seek_back_increment,
                            backendPreference = appPreferences.playerSeekBackInc,
                            displayDivisor = 1000L,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceLongInput(
                            nameStringResource = SettingsR.string.seek_forward_increment,
                            backendPreference = appPreferences.playerSeekForwardInc,
                            displayDivisor = 1000L,
                        ),
                        appPreferences = appPreferences,
                    )
                    // Declarative playback quality: full option labels for XR /
                    // main Settings dialogs, shortOptionsRes supplies Beam's
                    // compact button labels ("4K", "1080p") so the Row doesn't
                    // overflow. Backend is the same Long-backed pref the rest
                    // of the app reads from.
                    @Suppress("UNCHECKED_CAST")
                    BeamPreferenceRow(
                        preference = PreferenceSelect(
                            nameStringResource = SettingsR.string.player_max_bitrate,
                            descriptionStringRes = SettingsR.string.player_max_bitrate_summary,
                            backendPreference = appPreferences.playerMaxBitrate
                                as dev.jdtech.jellyfin.settings.domain.models.Preference<String?>,
                            options = SettingsR.array.player_max_bitrate_options,
                            optionValues = SettingsR.array.player_max_bitrate_values,
                            shortOptionsRes = SettingsR.array.player_max_bitrate_short_options,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.pref_player_media_segments_skip_button,
                            descriptionStringRes = SettingsR.string.pref_player_media_segments_skip_button_summary,
                            backendPreference = appPreferences.playerMediaSegmentsSkipButton,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.pref_player_media_segments_auto_skip,
                            descriptionStringRes = SettingsR.string.pref_player_media_segments_auto_skip_summary,
                            backendPreference = appPreferences.playerMediaSegmentsAutoSkip,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceLongInput(
                            nameStringResource = SettingsR.string.pref_player_media_segments_next_episode_threshold,
                            backendPreference = appPreferences.playerMediaSegmentsNextEpisodeThreshold,
                            displayDivisor = 1000L,
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("subtitles")) {
            item {
                SubtitlePreviewCard(
                    appPreferences = appPreferences,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                BeamSettingsSection(title = "Subtitles") {
                    BeamPreferenceRow(
                        preference = PreferenceIntInput(
                            nameStringResource = SettingsR.string.xr_subtitle_size,
                            descriptionStringRes = SettingsR.string.xr_subtitle_size_summary,
                            backendPreference = appPreferences.xrSubtitleSize,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceIntSelect(
                            nameStringResource = SettingsR.string.subtitle_text_color,
                            backendPreference = appPreferences.subtitleTextColor,
                            options = listOf(
                                IntSelectOption(SettingsR.string.subtitle_color_white, AndroidColor.WHITE),
                                IntSelectOption(SettingsR.string.subtitle_color_yellow, AndroidColor.YELLOW),
                                IntSelectOption(SettingsR.string.subtitle_color_cyan, AndroidColor.CYAN),
                            ),
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceIntSelect(
                            nameStringResource = SettingsR.string.subtitle_background,
                            backendPreference = appPreferences.subtitleBackgroundColor,
                            options = listOf(
                                IntSelectOption(SettingsR.string.subtitle_bg_transparent, AndroidColor.TRANSPARENT),
                                IntSelectOption(SettingsR.string.subtitle_bg_black, AndroidColor.BLACK),
                                IntSelectOption(SettingsR.string.subtitle_bg_dim, 0x99000000.toInt()),
                            ),
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("downloads")) {
            item {
                BeamSettingsSection(title = "Downloads") {
                    // Phase B.2: declarative Preference objects rendered through
                    // BeamPreferenceRow. Backend values are shared with the main
                    // Settings tree — editing here flips the same SharedPreferences
                    // entry that XR Settings does.
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.download_mobile_data,
                            backendPreference = appPreferences.downloadOverMobileData,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.download_roaming,
                            backendPreference = appPreferences.downloadWhenRoaming,
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("security")) {
            item {
                BeamSettingsSection(title = "Security") {
                    Text(
                        "Lock the app behind biometrics or a PIN, and optionally encrypt downloaded media on disk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // App lock mode uses the declarative select with autoPersist=false —
                    // biometric enrollment / PIN setup may be cancelled or fail, so
                    // onUpdate owns both the persistence and the rollback. Passing
                    // `value = appLockMode` keeps the renderer in sync with the
                    // authoritative state after async results settle.
                    BeamPreferenceRow(
                        preference = PreferenceSelect(
                            nameStringResource = SettingsR.string.settings_app_lock_mode_title,
                            descriptionStringRes = SettingsR.string.settings_app_lock_mode_summary,
                            backendPreference = appPreferences.appLockMode,
                            options = SettingsR.array.app_lock_mode,
                            optionValues = SettingsR.array.app_lock_mode_values,
                            autoPersist = false,
                            value = appLockMode,
                            onUpdate = { choice ->
                                when (AppLockMode.fromKey(choice)) {
                                    AppLockMode.Off -> {
                                        appLockManager.disable()
                                        appLockMode = AppLockMode.Off.backendKey
                                    }
                                    AppLockMode.Biometric -> {
                                        val activity = context as? FragmentActivity
                                        if (activity == null) {
                                            Toast.makeText(context, "Biometric not available on this screen.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            appLockScope.launch {
                                                val result = appLockManager.enroll(activity)
                                                when (result) {
                                                    is AppLockManager.EnrollResult.Success -> {
                                                        appLockMode = AppLockMode.Biometric.backendKey
                                                    }
                                                    is AppLockManager.EnrollResult.DeviceNotSecure -> {
                                                        appLockManager.disable()
                                                        appLockMode = AppLockMode.Off.backendKey
                                                        Toast.makeText(context, "Set a device screen lock first.", Toast.LENGTH_LONG).show()
                                                    }
                                                    is AppLockManager.EnrollResult.Cancelled -> {
                                                        appLockManager.disable()
                                                        appLockMode = AppLockMode.Off.backendKey
                                                    }
                                                    is AppLockManager.EnrollResult.Failed -> {
                                                        appLockManager.disable()
                                                        appLockMode = AppLockMode.Off.backendKey
                                                        Toast.makeText(context, "Biometric setup failed: ${result.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    AppLockMode.Pin -> {
                                        pinSetupVisible = true
                                    }
                                }
                            },
                        ),
                        appPreferences = appPreferences,
                    )
                    if (AppLockMode.fromKey(appLockMode) == AppLockMode.Pin && appLockManager.isConfigured()) {
                        BeamPreferenceRow(
                            preference = PreferenceCategory(
                                nameStringResource = SettingsR.string.settings_app_lock_pin_change_title,
                                descriptionStringRes = SettingsR.string.settings_app_lock_pin_change_summary,
                                onClick = { pinSetupVisible = true },
                            ),
                            appPreferences = appPreferences,
                        )
                    }
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.settings_content_encryption_title,
                            descriptionStringRes = SettingsR.string.settings_content_encryption_summary,
                            backendPreference = appPreferences.contentEncryptionEnabled,
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("seerr")) {
            item {
                BeamSettingsSection(title = "Jellyseerr") {
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.settings_seerr_enabled,
                            descriptionStringRes = SettingsR.string.settings_seerr_enabled_summary,
                            backendPreference = appPreferences.seerrEnabled,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_seerr_url,
                            descriptionStringRes = SettingsR.string.settings_seerr_url_summary,
                            placeholderRes = SettingsR.string.seerr_url_placeholder,
                            backendPreference = appPreferences.seerrUrl,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.settings_seerr_api_key,
                            descriptionStringRes = SettingsR.string.settings_seerr_api_key_summary,
                            placeholderRes = SettingsR.string.seerr_api_key_placeholder,
                            secret = true,
                            backendPreference = appPreferences.seerrApiKey,
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("voice")) {
            item {
                BeamSettingsSection(title = "Voice Assistant") {
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.voice_control_enabled_short,
                            descriptionStringRes = SettingsR.string.voice_control_enabled_short_summary,
                            backendPreference = appPreferences.voiceControlEnabled,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.voice_assistant_spoken_replies,
                            descriptionStringRes = SettingsR.string.voice_assistant_spoken_replies_summary,
                            backendPreference = appPreferences.voiceAssistantSpokenReplies,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSelect(
                            nameStringResource = SettingsR.string.voice_assistant_verbosity,
                            descriptionStringRes = SettingsR.string.voice_assistant_verbosity_summary,
                            backendPreference = appPreferences.voiceAssistantVerbosity,
                            options = SettingsR.array.voice_assistant_verbosity_options,
                            optionValues = SettingsR.array.voice_assistant_verbosity_values,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceSelect(
                            nameStringResource = SettingsR.string.voice_assistant_spoiler_policy,
                            descriptionStringRes = SettingsR.string.voice_assistant_spoiler_policy_summary,
                            backendPreference = appPreferences.voiceAssistantSpoilerPolicy,
                            options = SettingsR.array.voice_assistant_spoiler_policy_options,
                            optionValues = SettingsR.array.voice_assistant_spoiler_policy_values,
                        ),
                        appPreferences = appPreferences,
                    )
                    BeamPreferenceRow(
                        preference = PreferenceStringInput(
                            nameStringResource = SettingsR.string.voice_assistant_cloud_api_key,
                            descriptionStringRes = SettingsR.string.voice_assistant_cloud_api_key_summary,
                            placeholderRes = SettingsR.string.voice_assistant_cloud_api_key_placeholder,
                            secret = true,
                            backendPreference = appPreferences.voiceAssistantCloudApiKey,
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("companion")) {
            item {
                BeamSettingsSection(title = "Companion") {
                    // Recreating the PreferenceCategory on each recomposition
                    // lets the name/description swap with connection state.
                    BeamPreferenceRow(
                        preference = PreferenceCategory(
                            nameStringResource = if (companionConnected) SettingsR.string.companion_reconnect_action
                                else SettingsR.string.companion_connect_action,
                            descriptionStringRes = if (companionConnected) SettingsR.string.companion_connection_saved
                                else SettingsR.string.companion_not_paired,
                            onClick = { onOpenCompanion() },
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
        if (shouldShow("diagnostics")) {
            item {
                BeamSettingsSection(title = "Diagnostics") {
                    BeamPreferenceRow(
                        preference = PreferenceSwitch(
                            nameStringResource = SettingsR.string.logging_enabled,
                            descriptionStringRes = SettingsR.string.logging_enabled_summary,
                            backendPreference = appPreferences.loggingEnabled,
                        ),
                        appPreferences = appPreferences,
                    )
                    // Dynamic `enabled` is recomputed on each recomposition —
                    // the PreferenceCategory object is rebuilt cheaply and
                    // BeamRenderCategoryAction gates on `preference.enabled`.
                    BeamPreferenceRow(
                        preference = PreferenceCategory(
                            nameStringResource = SettingsR.string.diagnostics_upload_logs_now,
                            descriptionStringRes = SettingsR.string.diagnostics_upload_logs_now_summary,
                            enabled = loggingEnabled && companionConnected,
                            onClick = { BeamCompanionLogUploader.flushNow() },
                        ),
                        appPreferences = appPreferences,
                    )
                }
            }
        }
    }

    if (pinSetupVisible) {
        Dialog(
            onDismissRequest = { pinSetupVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f),
                color = MaterialTheme.colorScheme.surface,
            ) {
                PinSetupScreen(
                    onConfirmed = { newPin ->
                        appLockScope.launch {
                            val ok = appLockManager.setPin(newPin)
                            pinSetupVisible = false
                            if (ok) {
                                appLockMode = AppLockMode.Pin.backendKey
                                Toast.makeText(context, "PIN saved.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Couldn't save PIN.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCancel = {
                        pinSetupVisible = false
                        if (appLockManager.mode() == AppLockMode.Pin && !appLockManager.isConfigured()) {
                            appLockManager.disable()
                            appLockMode = AppLockMode.Off.backendKey
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BeamSettingsCategoryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BeamSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun BeamSettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BeamSettingChoiceRow(
    title: String,
    value: String,
    actions: List<String>,
    onAction: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$title: $value", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                Button(onClick = { onAction(action) }) {
                    Text(action)
                }
            }
        }
    }
}

@Composable
private fun BeamSettingTextField(
    title: String,
    value: String,
    label: String,
    secret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    var revealed by rememberSaveable(title) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            visualTransformation =
                if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon =
                if (secret) {
                    {
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "Hide" else "Show")
                        }
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun BeamSettingNumberRow(
    title: String,
    value: Long,
    onValueChange: (Long) -> Unit,
) {
    BeamSettingTextField(
        title = title,
        value = value.toString(),
        label = "Numeric value",
        onValueChange = { raw -> onValueChange(raw.toLongOrNull() ?: 0L) },
    )
}

private fun beamColorName(color: Int): String =
    when (color) {
        AndroidColor.YELLOW -> "Yellow"
        AndroidColor.CYAN -> "Cyan"
        else -> "White"
    }

private fun beamColorFromName(name: String): Int =
    when (name) {
        "Yellow" -> AndroidColor.YELLOW
        "Cyan" -> AndroidColor.CYAN
        else -> AndroidColor.WHITE
    }

private fun beamBackgroundName(color: Int): String =
    when (color) {
        AndroidColor.BLACK -> "Black"
        0x99000000.toInt() -> "Dim"
        else -> "Transparent"
    }

private fun beamBackgroundFromName(name: String): Int =
    when (name) {
        "Black" -> AndroidColor.BLACK
        "Dim" -> 0x99000000.toInt()
        else -> AndroidColor.TRANSPARENT
    }
