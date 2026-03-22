package dev.jdtech.jellyfin.settings.domain

import android.content.SharedPreferences
import android.content.Context
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings
import dev.jdtech.jellyfin.settings.domain.models.Preference
import javax.inject.Inject
import org.json.JSONObject
import timber.log.Timber

class AppPreferences @Inject constructor(val sharedPreferences: SharedPreferences) {
    // Server
    val currentServer = Preference<String?>("pref_current_server", null)

    // Language — global fallback
    val preferredAudioLanguage = Preference<String?>("pref_audio_language", null)
    val preferredSubtitleLanguage = Preference<String?>("pref_subtitle_language", null)

    // Language — content-type overrides
    // Anime: detected when genres contain "Anime" (case-insensitive)
    val animeAudioLanguage = Preference<String?>("pref_anime_audio_language", "jpn")
    val animeSubtitleLanguage = Preference<String?>("pref_anime_subtitle_language", "eng")
    // Non-anime: movies and TV shows that are not tagged as Anime
    val nonAnimeAudioLanguage = Preference<String?>("pref_non_anime_audio_language", "eng")
    val nonAnimeSubtitleDisabled = Preference("pref_non_anime_subtitle_disabled", true)
    val nonAnimeSubtitleLanguage = Preference<String?>("pref_non_anime_subtitle_language", null)

    // Interface
    val theme = Preference("pref_theme", "system")
    val dynamicColors = Preference("pref_dynamic_colors", true)
    val homeSuggestions = Preference<Boolean>("home_suggestions", true)
    val homeContinueWatching = Preference<Boolean>("home_continue_watching", true)
    val homeNextUp = Preference<Boolean>("home_next_up", true)
    val homeLatest = Preference<Boolean>("home_latest", true)
    val displayExtraInfo = Preference("pref_display_extra_info", false)
    val displayRatings = Preference("pref_display_ratings", true)

    // Player - seeking
    val playerSeekBackInc = Preference("pref_player_seek_back_inc", 5_000L)
    val playerSeekForwardInc = Preference("pref_player_seek_forward_inc", 15_000L)
    val playerChapterMarkers = Preference("pref_player_chapter_markers", true)

    // Player - Media Segments
    val playerMediaSegmentsSkipButton
        get() = Preference("pref_player_media_segments_skip_button", true)

    val playerMediaSegmentsSkipButtonType
        get() = Preference("pref_player_media_segments_skip_button_type", setOf("INTRO", "OUTRO"))

    val playerMediaSegmentsSkipButtonDuration
        get() = Preference("pref_player_media_segments_skip_button_duration", 5L)

    val playerMediaSegmentsAutoSkip
        get() = Preference("pref_player_media_segments_auto_skip", false)

    val playerMediaSegmentsAutoSkipMode
        get() =
            Preference(
                "pref_player_media_segments_auto_skip_mode",
                Constants.PlayerMediaSegmentsAutoSkip.ALWAYS,
            )

    val playerMediaSegmentsAutoSkipType
        get() = Preference("pref_player_media_segments_auto_skip_type", setOf("INTRO", "OUTRO"))

    val playerMediaSegmentsNextEpisodeThreshold
        get() = Preference("pref_player_media_segments_next_episode_threshold", 5_000L)

    // Player - trickplay
    val playerTrickplay = Preference("pref_player_trickplay", true)

    // Player - Bitrate
    val playerMaxBitrate = Preference("pref_player_max_bitrate", 0L)

    // Downloads
    val downloadOverMobileData = Preference("pref_downloads_mobile_data", false)
    val downloadWhenRoaming = Preference("pref_downloads_roaming", false)

    // Network
    val requestTimeout =
        Preference("pref_network_request_timeout", Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT)
    val connectTimeout =
        Preference("pref_network_connect_timeout", Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT)
    val socketTimeout =
        Preference("pref_network_socket_timeout", Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT)

    // Cache
    val imageCache = Preference("pref_image_cache", true)
    val imageCacheSize = Preference("pref_image_cache_size", 20)

    // Sorting
    val sortBy = Preference("pref_sort_by", "SortName")
    val sortOrder = Preference("pref_sort_order", "Ascending")

    // Offline mode
    val offlineMode = Preference("pref_offline_mode", false)

    // Companion Hub
    val companionUrl = Preference("pref_companion_url", "")
    val companionToken = Preference("pref_companion_token", "")
    val lastCompanionSyncTime = Preference("pref_last_companion_sync_time", 0L)

    // XR Subtitles
    val xrSubtitleSize = Preference("pref_xr_subtitle_size", 72)
    val xrPlayerPanelX = Preference("pref_xr_player_panel_x", 0f)
    val xrPlayerPanelY = Preference("pref_xr_player_panel_y", 0f)
    val xrPlayerPanelZ = Preference("pref_xr_player_panel_z", 0f)
    val xrPlayerPanelRotX = Preference("pref_xr_player_panel_rot_x", 0f)
    val xrPlayerPanelRotY = Preference("pref_xr_player_panel_rot_y", 0f)
    val xrPlayerPanelRotZ = Preference("pref_xr_player_panel_rot_z", 0f)
    val xrPlayerPanelRotW = Preference("pref_xr_player_panel_rot_w", 1f)
    val xrAppPanelX = Preference("pref_xr_app_panel_x", 0f)
    val xrAppPanelY = Preference("pref_xr_app_panel_y", 0f)
    val xrAppPanelZ = Preference("pref_xr_app_panel_z", -3f)
    val xrAppPanelRotX = Preference("pref_xr_app_panel_rot_x", 0f)
    val xrAppPanelRotY = Preference("pref_xr_app_panel_rot_y", 0f)
    val xrAppPanelRotZ = Preference("pref_xr_app_panel_rot_z", 0f)
    val xrAppPanelRotW = Preference("pref_xr_app_panel_rot_w", 1f)
    val subtitleTextColor = Preference("pref_subtitle_text_color", android.graphics.Color.WHITE)
    val subtitleBackgroundColor = Preference("pref_subtitle_background_color", android.graphics.Color.TRANSPARENT)
    val libassSubtitleUsage = Preference("pref_libass_subtitle_usage", "auto")
    val voiceControlEnabled = Preference("pref_voice_control_enabled", true)
    val voiceGestureHand = Preference<String?>("pref_voice_gesture_hand", "left")
    val voiceAssistantVerbosity = Preference("pref_voice_assistant_verbosity", "balanced")
    val voiceAssistantSpoilerPolicy = Preference("pref_voice_assistant_spoiler_policy", "cautious")
    val voiceAssistantSpokenReplies = Preference("pref_voice_assistant_spoken_replies", true)
    val voiceAssistantVoice = Preference<String?>("pref_voice_assistant_voice", "male")
    val voiceAssistantCloudApiKey = Preference<String?>("pref_voice_assistant_cloud_api_key", null)
    val smartPreferOriginalAudio = Preference("pref_smart_prefer_original_audio", true)
    val smartSpokenLanguages = Preference<String?>("pref_smart_spoken_languages", null)
    val onboardingCompleted = Preference("pref_onboarding_completed", false)
    val seriesLanguageOverrides = Preference<String?>("pref_series_language_overrides", null)

    // Seerr (Jellyseerr/Overseerr)
    val seerrEnabled = Preference("pref_seerr_enabled", false)
    val seerrUrl = Preference<String?>("pref_seerr_url", null)
    val seerrApiKey = Preference<String?>("pref_seerr_api_key", null)

    // TMDB
    val tmdbApiKey = Preference<String?>("pref_tmdb_api_key", null)
    val tmdbAutoMatch = Preference("pref_tmdb_auto_match", true)

    // Logging
    val loggingEnabled = Preference("pref_logging_enabled", false)

    inline fun <reified T> getValue(preference: Preference<T>): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            when (preference.defaultValue) {
                is Boolean ->
                    sharedPreferences.getBoolean(preference.backendName, preference.defaultValue)
                        as T
                is Int ->
                    sharedPreferences.getInt(preference.backendName, preference.defaultValue) as T
                is Long ->
                    sharedPreferences.getLong(preference.backendName, preference.defaultValue) as T
                is Float ->
                    sharedPreferences.getFloat(preference.backendName, preference.defaultValue) as T
                is String? ->
                    sharedPreferences.getString(preference.backendName, preference.defaultValue)
                        as T
                is Set<*> ->
                    sharedPreferences.getStringSet(
                        preference.backendName,
                        preference.defaultValue as Set<String>,
                    ) as T
                else -> preference.defaultValue
            }
        } catch (_: Exception) {
            Timber.w(
                "Failed to load ${preference.backendName} preference. Resetting to default value..."
            )
            setValue(preference, preference.defaultValue)
            preference.defaultValue
        }
    }

    inline fun <reified T> setValue(preference: Preference<T>, value: T) {
        val editor = sharedPreferences.edit()
        @Suppress("UNCHECKED_CAST")
        when (preference.defaultValue) {
            is Boolean -> editor.putBoolean(preference.backendName, value as Boolean)
            is Int -> editor.putInt(preference.backendName, value as Int)
            is Long -> editor.putLong(preference.backendName, value as Long)
            is Float -> editor.putFloat(preference.backendName, value as Float)
            is String? -> editor.putString(preference.backendName, value as String?)
            is Set<*> -> editor.putStringSet(preference.backendName, value as Set<String>)
            else -> throw Exception()
        }
        editor.apply()
    }

    fun getSmartLanguageSettings(context: Context): SmartLanguageSettings {
        return SmartLanguageSettings(
            preferOriginalAudio = getValue(smartPreferOriginalAudio),
            spokenLanguageCodes = getSmartSpokenLanguageCodes(context),
        )
    }

    fun getSmartSpokenLanguageCodes(context: Context): List<String> {
        val stored =
            getValue(smartSpokenLanguages)
                ?.split(",")
                ?.mapNotNull { LanguageCatalog.normalize(context, it) }
                ?.distinct()
                .orEmpty()

        return if (stored.isNotEmpty()) {
            stored
        } else {
            listOf(LanguageCatalog.defaultDeviceLanguageCode(context))
        }
    }

    fun setSmartLanguageSettings(settings: SmartLanguageSettings) {
        setValue(smartPreferOriginalAudio, settings.preferOriginalAudio)
        setValue(
            smartSpokenLanguages,
            settings.spokenLanguageCodes.distinct().joinToString(",").ifBlank { null },
        )
    }

    fun getSeriesLanguageOverride(seriesId: String): SeriesLanguageOverride? {
        val root = getSeriesOverridesJson() ?: return null
        val entry = root.optJSONObject(seriesId) ?: return null
        return SeriesLanguageOverride(
            audioLanguageCode = entry.optString("audio", "").ifBlank { null },
            audioTrackSignature = entry.optString("audioSignature", "").ifBlank { null },
            subtitleLanguageCode = entry.optString("subtitle", "").ifBlank { null },
            subtitleTrackSignature = entry.optString("subtitleSignature", "").ifBlank { null },
            subtitlesEnabled =
                if (entry.has("subtitlesEnabled")) entry.optBoolean("subtitlesEnabled") else null,
        )
    }

    fun setSeriesLanguageOverride(seriesId: String, override: SeriesLanguageOverride?) {
        val root = getSeriesOverridesJson() ?: JSONObject()
        if (override == null) {
            root.remove(seriesId)
        } else {
            root.put(
                seriesId,
                JSONObject().apply {
                    if (override.audioLanguageCode != null) {
                        put("audio", override.audioLanguageCode)
                    }
                    if (override.audioTrackSignature != null) {
                        put("audioSignature", override.audioTrackSignature)
                    }
                    if (override.subtitleLanguageCode != null) {
                        put("subtitle", override.subtitleLanguageCode)
                    }
                    if (override.subtitleTrackSignature != null) {
                        put("subtitleSignature", override.subtitleTrackSignature)
                    }
                    if (override.subtitlesEnabled != null) {
                        put("subtitlesEnabled", override.subtitlesEnabled)
                    }
                }
            )
        }
        setValue(
            seriesLanguageOverrides,
            root.takeIf { it.length() > 0 }?.toString()
        )
    }

    private fun getSeriesOverridesJson(): JSONObject? {
        return getValue(seriesLanguageOverrides)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                runCatching { JSONObject(raw) }
                    .onFailure { Timber.w(it, "Failed to parse series language overrides") }
                    .getOrNull()
            }
    }
}
