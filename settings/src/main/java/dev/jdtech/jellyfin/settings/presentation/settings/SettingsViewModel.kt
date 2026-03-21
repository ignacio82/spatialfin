package dev.jdtech.jellyfin.settings.presentation.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.settings.R
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceAppLanguage
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceFloatInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceInfo
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceLongInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceMultiSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSmartLanguage
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSwitch
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    @ApplicationContext private val context: Context,
    private val voiceTelemetryStore: VoiceTelemetryStore,
) :
    ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()
    private var currentIndexes: IntArray = intArrayOf()
    private var currentDeviceType: DeviceType = DeviceType.XR

    private val eventsChannel = Channel<SettingsEvent>()
    val events = eventsChannel.receiveAsFlow()

    private val topLevelPreferences =
        listOf(
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceSwitch(
                            nameStringResource = R.string.offline_mode,
                            descriptionStringRes = R.string.offline_mode_summary,
                            iconDrawableId = R.drawable.ic_server_off,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.RestartActivity)
                                }
                            },
                            backendPreference = appPreferences.offlineMode,
                        ),
                        PreferenceSwitch(
                            nameStringResource = R.string.logging_enabled,
                            descriptionStringRes = R.string.logging_enabled_summary,
                            backendPreference = appPreferences.loggingEnabled,
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_language,
                            iconDrawableId = R.drawable.ic_languages,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceAppLanguage(
                                                    nameStringResource = R.string.app_language,
                                                    iconDrawableId = R.drawable.ic_languages,
                                                    enabled =
                                                        Build.VERSION.SDK_INT >=
                                                            Build.VERSION_CODES.TIRAMISU,
                                                )
                                            )
                                    ),
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSmartLanguage(
                                                    nameStringResource =
                                                        R.string.settings_smart_audio_subtitles,
                                                    descriptionStringRes =
                                                        R.string.settings_smart_audio_subtitles_summary,
                                                    iconDrawableId = R.drawable.ic_languages,
                                                    summary = smartLanguageSummary(),
                                                    onClick = { showSmartLanguageDialog() },
                                                ),
                                            )
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_interface,
                            iconDrawableId = R.drawable.ic_layout_dashboard,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        nameStringResource = R.string.settings_category_appearance,
                                        preferences =
                                            listOf(
                                                PreferenceSelect(
                                                    nameStringResource = R.string.theme,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference = appPreferences.theme,
                                                    onUpdate = { value ->
                                                        viewModelScope.launch {
                                                            eventsChannel.send(
                                                                SettingsEvent.UpdateTheme(
                                                                    value ?: "system"
                                                                )
                                                            )
                                                        }
                                                    },
                                                    options = R.array.theme,
                                                    optionValues = R.array.theme_values,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.dynamic_colors,
                                                    descriptionStringRes =
                                                        R.string.dynamic_colors_summary,
                                                    enabled =
                                                        Build.VERSION.SDK_INT >=
                                                            Build.VERSION_CODES.S,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference = appPreferences.dynamicColors,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.home,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.home_suggestions,
                                                    backendPreference =
                                                        appPreferences.homeSuggestions,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.home_continue_watching,
                                                    backendPreference =
                                                        appPreferences.homeContinueWatching,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.home_next_up,
                                                    backendPreference = appPreferences.homeNextUp,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.home_latest,
                                                    backendPreference = appPreferences.homeLatest,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.extra_info,
                                                    descriptionStringRes =
                                                        R.string.extra_info_summary,
                                                    backendPreference =
                                                        appPreferences.displayExtraInfo,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.display_ratings,
                                                    descriptionStringRes =
                                                        R.string.display_ratings_summary,
                                                    backendPreference =
                                                        appPreferences.displayRatings,
                                                )
                                            )
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_player,
                            iconDrawableId = R.drawable.ic_play,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceCategory(
                                                    nameStringResource = R.string.subtitles,
                                                    descriptionStringRes =
                                                        R.string.subtitles_summary,
                                                    iconDrawableId = R.drawable.ic_closed_caption,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE, DeviceType.TV),
                                                    onClick = {
                                                        viewModelScope.launch {
                                                            eventsChannel.send(
                                                                SettingsEvent.LaunchIntent(
                                                                    Intent(
                                                                        Settings
                                                                            .ACTION_CAPTIONING_SETTINGS
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    },
                                                ),
                                                PreferenceIntInput(
                                                    nameStringResource = R.string.xr_subtitle_size,
                                                    descriptionStringRes = R.string.xr_subtitle_size_summary,
                                                    backendPreference = appPreferences.xrSubtitleSize,
                                                    supportedDeviceTypes = listOf(DeviceType.XR),
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource = R.string.libass_subtitle_usage,
                                                    descriptionStringRes = R.string.libass_subtitle_usage_summary,
                                                    backendPreference = appPreferences.libassSubtitleUsage,
                                                    options = R.array.libass_subtitle_usage_options,
                                                    optionValues = R.array.libass_subtitle_usage_values,
                                                    supportedDeviceTypes = listOf(DeviceType.XR),
                                                ),
                                                @Suppress("UNCHECKED_CAST")
                                                PreferenceSelect(
                                                    nameStringResource = R.string.player_max_bitrate,
                                                    descriptionStringRes = R.string.player_max_bitrate_summary,
                                                    backendPreference = appPreferences.playerMaxBitrate as Preference<String?>,
                                                    options = R.array.player_max_bitrate_options,
                                                    optionValues = R.array.player_max_bitrate_values,
                                                ),
                                                PreferenceCategory(
                                                    nameStringResource = R.string.voice_controls,
                                                    descriptionStringRes = R.string.voice_controls_summary,
                                                    iconDrawableId = R.drawable.ic_microphone,
                                                    supportedDeviceTypes = listOf(DeviceType.XR),
                                                    onClick = {
                                                        viewModelScope.launch {
                                                            eventsChannel.send(
                                                                SettingsEvent.NavigateToSettings(
                                                                    intArrayOf(
                                                                        R.string.settings_category_player,
                                                                        R.string.voice_controls,
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    },
                                                    nestedPreferenceGroups = emptyList(),
                                                ),
                                            )
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.seeking,
                                        preferences =
                                            listOf(
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.seek_back_increment,
                                                    backendPreference =
                                                        appPreferences.playerSeekBackInc,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.seek_forward_increment,
                                                    backendPreference =
                                                        appPreferences.playerSeekForwardInc,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.pref_player_chapter_markers,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_chapter_markers_summary,
                                                    backendPreference =
                                                        appPreferences.playerChapterMarkers,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.media_segments,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_skip_button,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_media_segments_skip_button_summary,
                                                    backendPreference =
                                                        appPreferences.playerMediaSegmentsSkipButton,
                                                ),
                                                PreferenceMultiSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_skip_button_type,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsSkipButton
                                                        ),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsSkipButtonType,
                                                    options = R.array.media_segments_type,
                                                    optionValues =
                                                        R.array.media_segments_type_values,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_skip_button_duration,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsSkipButton
                                                        ),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsSkipButtonDuration,
                                                    suffixRes = R.string.seconds,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip_summary,
                                                    backendPreference =
                                                        appPreferences.playerMediaSegmentsAutoSkip,
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip_mode,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsAutoSkip
                                                        ),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsAutoSkipMode,
                                                    options = R.array.media_segments_auto_skip,
                                                    optionValues =
                                                        R.array.media_segments_auto_skip_values,
                                                ),
                                                PreferenceMultiSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip_type,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsAutoSkip
                                                        ),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsAutoSkipType,
                                                    options = R.array.media_segments_type,
                                                    optionValues =
                                                        R.array.media_segments_type_values,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_next_episode_threshold,
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsNextEpisodeThreshold,
                                                    suffixRes = R.string.ms,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.trickplay,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.pref_player_trickplay,
                                                    descriptionStringRes =
                                                        R.string.pref_player_trickplay_summary,
                                                    backendPreference =
                                                        appPreferences.playerTrickplay,
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.users,
                            iconDrawableId = R.drawable.ic_user,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.NavigateToUsers)
                                }
                            },
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_servers,
                            iconDrawableId = R.drawable.ic_server,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.NavigateToServers)
                                }
                            },
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.title_download,
                            iconDrawableId = R.drawable.ic_download,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.download_mobile_data,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.downloadOverMobileData,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.download_roaming,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences.downloadOverMobileData
                                                        ),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.downloadWhenRoaming,
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_seerr,
                            iconDrawableId = R.drawable.ic_server,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.settings_seerr_enabled,
                                                    descriptionStringRes = R.string.settings_seerr_enabled_summary,
                                                    backendPreference = appPreferences.seerrEnabled,
                                                ),
                                                PreferenceCategory(
                                                    nameStringResource = R.string.settings_seerr_url,
                                                    descriptionStringRes = R.string.settings_seerr_url_summary,
                                                    iconDrawableId = R.drawable.ic_network,
                                                    dependencies = listOf(appPreferences.seerrEnabled),
                                                    onClick = { showSeerrUrlDialog() },
                                                ),
                                                PreferenceCategory(
                                                    nameStringResource = R.string.settings_seerr_api_key,
                                                    descriptionStringRes = R.string.settings_seerr_api_key_summary,
                                                    iconDrawableId = R.drawable.ic_info,
                                                    dependencies = listOf(appPreferences.seerrEnabled),
                                                    onClick = { showSeerrApiKeyDialog() },
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_network,
                            iconDrawableId = R.drawable.ic_network,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceCategory(
                                                    nameStringResource = R.string.settings_tmdb_api_key,
                                                    descriptionStringRes = R.string.settings_tmdb_api_key_summary,
                                                    iconDrawableId = R.drawable.ic_info,
                                                    onClick = { showTmdbApiKeyDialog() },
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.settings_tmdb_auto_match,
                                                    descriptionStringRes = R.string.settings_tmdb_auto_match_summary,
                                                    backendPreference = appPreferences.tmdbAutoMatch,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.settings_request_timeout,
                                                    backendPreference =
                                                        appPreferences.requestTimeout,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.settings_connect_timeout,
                                                    backendPreference =
                                                        appPreferences.connectTimeout,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.settings_socket_timeout,
                                                    backendPreference =
                                                        appPreferences.socketTimeout,
                                                    suffixRes = R.string.ms,
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_cache,
                            iconDrawableId = R.drawable.ic_hard_drive,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_use_cache_title,
                                                    descriptionStringRes =
                                                        R.string.settings_use_cache_summary,
                                                    backendPreference = appPreferences.imageCache,
                                                ),
                                                PreferenceIntInput(
                                                    nameStringResource =
                                                        R.string.settings_cache_size,
                                                    descriptionStringRes =
                                                        R.string.settings_cache_size_message,
                                                    dependencies =
                                                        listOf(appPreferences.imageCache),
                                                    backendPreference =
                                                        appPreferences.imageCacheSize,
                                                    suffixRes = R.string.mb,
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.welcome_companion_title,
                            iconDrawableId = R.drawable.ic_network,
                            supportedDeviceTypes = listOf(DeviceType.XR),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.ShowCompanionDiscoveryDialog)
                                }
                            },
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.about,
                            iconDrawableId = R.drawable.ic_info,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.NavigateToAbout)
                                }
                            },
                        )
                    )
            ),
        )

    fun loadPreferences(indexes: IntArray = intArrayOf(), deviceType: DeviceType) {
        currentIndexes = indexes
        currentDeviceType = deviceType
        refreshLoadedPreferences()
    }

    private fun refreshLoadedPreferences() {
        viewModelScope.launch {
            var preferences = topLevelPreferences

            // Show preferences based on the name of the parent
            for (index in currentIndexes) {
                // If index is root (Settings) don't search for category
                if (index == R.string.title_settings) {
                    break
                }
                if (index == R.string.voice_controls) {
                    preferences = voicePreferenceGroups()
                    continue
                }
                val preference =
                    preferences
                        .flatMap { it.preferences }
                        .filterIsInstance<PreferenceCategory>()
                        .find { it.nameStringResource == index }
                if (preference != null) {
                    preferences = preference.nestedPreferenceGroups
                }
            }

            // Update all (visible) preferences with there current values
            preferences =
                preferences
                    .map { preferenceGroup ->
                        preferenceGroup.copy(
                            preferences =
                                preferenceGroup.preferences
                                    .filter { it.supportedDeviceTypes.contains(currentDeviceType) }
                                    .map { preference ->
                                        when (preference) {
                                            is PreferenceSwitch -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceSelect -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ).toString(),
                                                )
                                            }
                                            is PreferenceMultiSelect -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceIntInput -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceLongInput -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceSmartLanguage -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    summary = smartLanguageSummary(),
                                                )
                                            }
                                            is PreferenceCategory -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                )
                                            }
                                            else -> preference
                                        }
                                    }
                        )
                    }
                    .filter { it.preferences.isNotEmpty() }

            _state.emit(
                _state.value.copy(
                    preferenceGroups = preferences,
                    companionSyncStatus = appPreferences.getValue(appPreferences.companionUrl),
                    lastSyncTime = appPreferences.getValue(appPreferences.lastCompanionSyncTime),
                )
            )
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.OnPreferenceClick -> {
                when (action.preference) {
                    is PreferenceCategory -> action.preference.onClick(action.preference)
                    is PreferenceSmartLanguage -> action.preference.onClick(action.preference)
                    else -> Unit
                }
            }
            is SettingsAction.OnUpdate -> {
                when (action.preference) {
                    is PreferenceSwitch ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceSelect -> {
                        @Suppress("UNCHECKED_CAST")
                        if (action.preference.backendPreference.defaultValue?.let { it::class } == Long::class) {
                            appPreferences.setValue(
                                action.preference.backendPreference as Preference<Long>,
                                action.preference.value?.toLongOrNull() ?: 0L
                            )
                        } else {
                            appPreferences.setValue(
                                action.preference.backendPreference,
                                action.preference.value,
                            )
                        }
                    }
                    is PreferenceMultiSelect ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceIntInput ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceLongInput ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                }
                refreshLoadedPreferences()
            }
            else -> Unit
        }
    }

    fun showCloudApiKeyDialog() {
        viewModelScope.launch {
            eventsChannel.send(
                SettingsEvent.ShowCloudApiKeyDialog(
                    appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey)
                )
            )
        }
    }

    fun saveCloudApiKey(value: String) {
        appPreferences.setValue(
            appPreferences.voiceAssistantCloudApiKey,
            value.trim().takeIf { it.isNotEmpty() },
        )
        refreshLoadedPreferences()
    }

    fun showTmdbApiKeyDialog() {
        viewModelScope.launch {
            eventsChannel.send(
                SettingsEvent.ShowTmdbApiKeyDialog(
                    appPreferences.getValue(appPreferences.tmdbApiKey)
                )
            )
        }
    }

    fun saveTmdbApiKey(value: String) {
        appPreferences.setValue(
            appPreferences.tmdbApiKey,
            value.trim().takeIf { it.isNotEmpty() },
        )
        refreshLoadedPreferences()
    }

    fun showSeerrUrlDialog() {
        viewModelScope.launch {
            eventsChannel.send(
                SettingsEvent.ShowSeerrUrlDialog(
                    appPreferences.getValue(appPreferences.seerrUrl)
                )
            )
        }
    }

    fun saveSeerrUrl(value: String) {
        appPreferences.setValue(
            appPreferences.seerrUrl,
            value.trim().takeIf { it.isNotEmpty() },
        )
        refreshLoadedPreferences()
    }

    fun showSeerrApiKeyDialog() {
        viewModelScope.launch {
            eventsChannel.send(
                SettingsEvent.ShowSeerrApiKeyDialog(
                    appPreferences.getValue(appPreferences.seerrApiKey)
                )
            )
        }
    }

    fun saveSeerrApiKey(value: String) {
        appPreferences.setValue(
            appPreferences.seerrApiKey,
            value.trim().takeIf { it.isNotEmpty() },
        )
        refreshLoadedPreferences()
    }

    fun showSmartLanguageDialog() {
        viewModelScope.launch {
            eventsChannel.send(
                SettingsEvent.ShowSmartLanguageDialog(
                    appPreferences.getSmartLanguageSettings(context)
                )
            )
        }
    }

    fun saveSmartLanguageSettings(settings: SmartLanguageSettings) {
        appPreferences.setSmartLanguageSettings(
            settings.copy(
                spokenLanguageCodes =
                    settings.spokenLanguageCodes
                        .mapNotNull { LanguageCatalog.normalize(context, it) }
                        .distinct()
                        .ifEmpty { listOf(LanguageCatalog.defaultDeviceLanguageCode(context)) },
            )
        )
        refreshLoadedPreferences()
    }

    private fun smartLanguageSummary(): String {
        val settings = appPreferences.getSmartLanguageSettings(context)
        val spokenLanguages = LanguageCatalog.summarize(context, settings.spokenLanguageCodes)
        val audioMode =
            if (settings.preferOriginalAudio) {
                context.getString(R.string.settings_original_audio_enabled)
            } else {
                context.getString(R.string.settings_original_audio_disabled)
            }
        return "$audioMode. $spokenLanguages."
    }

    private fun voicePreferenceGroups(): List<PreferenceGroup> {
        val handTrackingPermission = "android.permission.HAND_TRACKING"
        val hasMicPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasHandPermission =
            ContextCompat.checkSelfPermission(context, handTrackingPermission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        val aicoreInstalled = isPackageInstalled("com.google.android.aicore")
        val cloudApiKeyConfigured =
            !appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).isNullOrBlank()
        val telemetry = voiceTelemetryStore.summary()
        val recentEntries =
            telemetry.recentEntries.joinToString("\n") { entry ->
                buildString {
                    append("${entry.action} via ${entry.strategy} in ${entry.latencyMs}ms")
                    if (entry.details.isNotBlank()) {
                        append("\n")
                        append(entry.details)
                    }
                }
            }.ifBlank { "No voice sessions recorded yet." }

        return listOf(
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceSwitch(
                            nameStringResource = R.string.voice_controls,
                            descriptionStringRes = R.string.voice_controls_summary,
                            iconDrawableId = R.drawable.ic_microphone,
                            backendPreference = appPreferences.voiceControlEnabled,
                        ),
                        PreferenceSelect(
                            nameStringResource = R.string.voice_gesture_hand,
                            descriptionStringRes = R.string.voice_gesture_hand_summary,
                            iconDrawableId = R.drawable.ic_microphone,
                            backendPreference = appPreferences.voiceGestureHand,
                            options = R.array.voice_gesture_hand_options,
                            optionValues = R.array.voice_gesture_hand_values,
                            value = appPreferences.getValue(appPreferences.voiceGestureHand),
                            dependencies = listOf(appPreferences.voiceControlEnabled),
                        ),
                        PreferenceSelect(
                            nameStringResource = R.string.voice_assistant_verbosity,
                            descriptionStringRes = R.string.voice_assistant_verbosity_summary,
                            iconDrawableId = R.drawable.ic_info,
                            backendPreference = appPreferences.voiceAssistantVerbosity,
                            options = R.array.voice_assistant_verbosity_options,
                            optionValues = R.array.voice_assistant_verbosity_values,
                            value = appPreferences.getValue(appPreferences.voiceAssistantVerbosity),
                            dependencies = listOf(appPreferences.voiceControlEnabled),
                        ),
                        PreferenceSelect(
                            nameStringResource = R.string.voice_assistant_spoiler_policy,
                            descriptionStringRes = R.string.voice_assistant_spoiler_policy_summary,
                            iconDrawableId = R.drawable.ic_info,
                            backendPreference = appPreferences.voiceAssistantSpoilerPolicy,
                            options = R.array.voice_assistant_spoiler_policy_options,
                            optionValues = R.array.voice_assistant_spoiler_policy_values,
                            value = appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy),
                            dependencies = listOf(appPreferences.voiceControlEnabled),
                        ),
                        PreferenceSwitch(
                            nameStringResource = R.string.voice_assistant_spoken_replies,
                            descriptionStringRes = R.string.voice_assistant_spoken_replies_summary,
                            iconDrawableId = R.drawable.ic_microphone,
                            backendPreference = appPreferences.voiceAssistantSpokenReplies,
                        ),
                        PreferenceSelect(
                            nameStringResource = R.string.voice_assistant_voice,
                            descriptionStringRes = R.string.voice_assistant_voice_summary,
                            iconDrawableId = R.drawable.ic_microphone,
                            backendPreference = appPreferences.voiceAssistantVoice,
                            options = R.array.voice_assistant_voice_options,
                            optionValues = R.array.voice_assistant_voice_values,
                            value = appPreferences.getValue(appPreferences.voiceAssistantVoice),
                            dependencies = listOf(appPreferences.voiceControlEnabled),
                        ),
                        PreferenceCategory(
                            nameStringResource = R.string.voice_cloud_api_key,
                            descriptionStringRes = R.string.voice_cloud_api_key_summary,
                            iconDrawableId = R.drawable.ic_info,
                            onClick = { showCloudApiKeyDialog() },
                        ),
                        PreferenceCategory(
                            nameStringResource = R.string.voice_permissions,
                            descriptionStringRes = R.string.voice_permissions_summary,
                            iconDrawableId = R.drawable.ic_info,
                            onClick = {
                                viewModelScope.launch {
                                    val intent =
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = android.net.Uri.parse("package:${context.packageName}")
                                        }
                                    eventsChannel.send(SettingsEvent.LaunchIntent(intent))
                                }
                            },
                        ),
                    )
            ),
            PreferenceGroup(
                nameStringResource = R.string.voice_status,
                preferences =
                    listOf(
                        PreferenceInfo(
                            title = "Permissions",
                            description =
                                "Microphone: ${if (hasMicPermission) "Granted" else "Missing"}\n" +
                                    "Hand tracking: ${if (hasHandPermission) "Granted" else "Missing"}",
                            iconDrawableId = R.drawable.ic_info,
                        ),
                        PreferenceInfo(
                            title = "On-device availability",
                            description =
                                "Offline speech recognition: ${if (speechAvailable) "Available" else "Unavailable"}\n" +
                                    "AICore package: ${if (aicoreInstalled) "Installed" else "Missing on this device"}\n" +
                                    "Gemini Nano parsing: ${if (aicoreInstalled) "Check voice telemetry after a test run for exact AICore/model status" else "Unavailable until AICore is present on the device"}",
                            iconDrawableId = R.drawable.ic_microphone,
                        ),
                        PreferenceInfo(
                            title = "Cloud AI fallback",
                            description =
                                "API key: ${if (cloudApiKeyConfigured) "Configured" else "Not configured"}\n" +
                                    "When configured, SpatialFin uses Gemini 3.1 Flash-Lite Preview as a cloud fallback for AI parsing and chat on devices without AICore.",
                            iconDrawableId = R.drawable.ic_microphone,
                        ),
                        PreferenceInfo(
                            title = "Example commands",
                            description =
                                "pause\nrewind 30 seconds\nwhat just happened?\nwho is that character?\nshow me cyberpunk anime\nrecommend something like Dune\nsearch for Cowboy Bebop",
                            iconDrawableId = R.drawable.ic_info,
                        ),
                    )
            ),
            PreferenceGroup(
                nameStringResource = R.string.voice_telemetry_dashboard,
                preferences =
                    listOf(
                        PreferenceInfo(
                            title = "Summary",
                            description =
                                "Attempts: ${telemetry.totalAttempts}\n" +
                                    "Successful: ${telemetry.successfulAttempts}\n" +
                                    "Average latency: ${telemetry.averageLatencyMs}ms\n" +
                                    "Last transcript: ${telemetry.lastTranscript}\n" +
                                    "Last action: ${telemetry.lastAction}",
                            iconDrawableId = R.drawable.ic_info,
                        ),
                        PreferenceInfo(
                            title = "Recent sessions",
                            description = recentEntries,
                            iconDrawableId = R.drawable.ic_info,
                        ),
                    )
            ),
        )
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
