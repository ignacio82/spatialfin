package dev.jdtech.jellyfin.settings.domain

import android.content.Context
import android.content.SharedPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SeriesLanguageOverride
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject
import timber.log.Timber

class AppPreferences(
    val sharedPreferences: SharedPreferences,
    val xrPlayerSharedPreferences: SharedPreferences = sharedPreferences,
    val isMainProcess: Boolean = true,
    val isDebuggable: Boolean = true,
) {
    private val appLockPolicyLock = ReentrantLock()
    private val xrWriteLock = Any()
    private val pendingXrWrites = LinkedHashMap<String, Any?>()
    private var xrFlushScheduled = false

    private val xrPrefsExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "spatialfin-xr-prefs").apply { isDaemon = true }
        }
    }

    // Server & onboarding state
    val currentServer = Preference<String?>("pref_current_server", null)
    val standaloneModeChosen = Preference("pref_standalone_mode_chosen", false)
    val pendingServerIdMigrationsJson = Preference("pref_pending_server_id_migrations_json", "{}")

    val sendspinClientId = Preference<String?>("pref_sendspin_client_id", null)

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
    val dynamicColors = Preference("pref_dynamic_colors", false)
    val homeSuggestions = Preference<Boolean>("home_suggestions", true)
    val homeContinueWatching = Preference<Boolean>("home_continue_watching", true)
    val homeNextUp = Preference<Boolean>("home_next_up", true)
    val homeLatest = Preference<Boolean>("home_latest", true)

    /**
     * User-arranged home screen: a JSON blob of `{"order": [...], "hidden": [...]}`
     * over the row ids in `HomeRowIds`. Read and written exclusively through
     * `HomeRowPreferences` — do not poke at it directly.
     */
    val homeRowLayout = Preference<String?>("pref_home_row_layout", null)
    val displayExtraInfo = Preference("pref_display_extra_info", false)
    val displayRatings = Preference("pref_display_ratings", true)

    fun networkShareHomeVisiblePreference(shareId: String) =
        Preference("${NETWORK_SHARE_HOME_VISIBLE_PREFIX}$shareId", true)

    fun isNetworkShareHomeVisible(shareId: String): Boolean =
        getValue(networkShareHomeVisiblePreference(shareId))

    fun setNetworkShareHomeVisible(shareId: String, visible: Boolean) {
        setValue(networkShareHomeVisiblePreference(shareId), visible)
    }

    fun isNetworkShareHomeVisiblePreferenceKey(key: String?): Boolean =
        key?.startsWith(NETWORK_SHARE_HOME_VISIBLE_PREFIX) == true

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

    val playerChapterIntroDetection
        get() = Preference("pref_player_chapter_intro_detection", true)

    val playerAniSkipEnabled
        get() = Preference("pref_player_aniskip_enabled", false)

    // Player - trickplay
    val playerTrickplay = Preference("pref_player_trickplay", true)

    // Player - Bitrate
    val playerMaxBitrate = Preference("pref_player_max_bitrate", 0L)
    val playerForceDirectPlay = Preference("pref_player_force_direct_play", false)

    // Player - Audio passthrough ("auto" | "off" | "force")
    val playerAudioPassthrough = Preference<String?>("pref_player_audio_passthrough", "auto")

    // SyncPlay — scoped pending leave marker (presence of both indicates a pending leave).
    val pendingSyncPlayLeaveServerId = Preference<String?>("pref_pending_syncplay_leave_server_id", null)
    val pendingSyncPlayLeaveUserId = Preference<String?>("pref_pending_syncplay_leave_user_id", null)

    // Downloads
    val downloadOverMobileData = Preference("pref_downloads_mobile_data", false)
    val downloadWhenRoaming = Preference("pref_downloads_roaming", false)
    val downloadBatteryOptPrompted = Preference("pref_downloads_batt_opt_prompted", false)
    val downloadPreferTranscoded = Preference("pref_downloads_prefer_transcoded", false)
    val downloadTranscodeBitrate = Preference("pref_downloads_transcode_bitrate", 8_000_000)

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
    val companionDeviceOverrodeSeerrUrl = Preference("pref_companion_device_overrode_seerr_url", false)

    // Stage 2 Companion preference reconciliation & token-digest tracking (raw JSON; parsed in :core)
    val companionPreferenceBaselineJson = Preference<String?>("pref_companion_preference_baseline_json", null)
    val preBaselineLocalSnapshotsJson = Preference<String?>("pref_pre_baseline_local_snapshots_json", null)
    val appliedUserOverrideSnapshotsJson = Preference<String?>("pref_applied_user_override_snapshots_json", null)
    val dedicatedAccessTokenDigestsJson = Preference<String?>("pref_dedicated_access_token_digests_json", null)
    val revokedDownloadTokenDigestsJson = Preference<String?>("pref_revoked_download_token_digests_json", null)

    // FCast
    val fcastSenderEnabled = Preference("pref_fcast_sender_enabled", true)
    val fcastReceiverEnabled = Preference("pref_fcast_receiver_enabled", false)
    val fcastReceiverDisplayName = Preference("pref_fcast_receiver_display_name", "SpatialFin")
    val fcastReceiverAutopromote = Preference("pref_fcast_receiver_autopromote", true)
    val lastUsedFCastReceiverHostPort = Preference<String?>("pref_fcast_last_receiver", null)

    val castSubtitleHandling = Preference("pref_cast_subtitle_handling", "best_fidelity")
    val castShowTranscodingIndicator = Preference("pref_cast_show_transcoding_indicator", true)
    val castShowSubtitleFidelityWarning = Preference("pref_cast_show_subtitle_fidelity_warning", true)

    val castShowFCast = Preference("pref_cast_show_fcast", true)
    val castShowGoogleCast = Preference("pref_cast_show_googlecast", true)
    val castShowAirPlay = Preference("pref_cast_show_airplay", true)

    val castAudioFallback = Preference("pref_cast_audio_fallback", "auto")

    // XR Subtitles & Player panel poses (15 xrPlayer* keys below route to spatialfin_xr_player_prefs)
    val xrSubtitleSize = Preference("pref_xr_subtitle_size", 72)
    val xrPlayerPanelX = Preference("pref_xr_player_panel_x", 0f)
    val xrPlayerPanelY = Preference("pref_xr_player_panel_y", 0f)
    val xrPlayerPanelZ = Preference("pref_xr_player_panel_z", 0f)
    val xrPlayerPanelRotX = Preference("pref_xr_player_panel_rot_x", 0f)
    val xrPlayerPanelRotY = Preference("pref_xr_player_panel_rot_y", 0f)
    val xrPlayerPanelRotZ = Preference("pref_xr_player_panel_rot_z", 0f)
    val xrPlayerPanelRotW = Preference("pref_xr_player_panel_rot_w", 1f)
    val xrPlayerPanelScale = Preference("pref_xr_player_panel_scale", 1.39f)
    val xrPlayerPanelDepth = Preference("pref_xr_player_panel_depth", 6.0f)
    val xrPlayerPanelPoseVersion = Preference("pref_xr_player_panel_pose_version", 0)
    val xrAppPanelX = Preference("pref_xr_app_panel_x", 0f)
    val xrAppPanelY = Preference("pref_xr_app_panel_y", 0f)
    val xrAppPanelZ = Preference("pref_xr_app_panel_z", -6f)
    val xrAppPanelRotX = Preference("pref_xr_app_panel_rot_x", 0f)
    val xrAppPanelRotY = Preference("pref_xr_app_panel_rot_y", 0f)
    val xrAppPanelRotZ = Preference("pref_xr_app_panel_rot_z", 0f)
    val xrAppPanelRotW = Preference("pref_xr_app_panel_rot_w", 1f)
    val xrAppPanelScale = Preference("pref_xr_app_panel_scale", 1.0f)
    val xrAppPanelPoseVersion = Preference("pref_xr_app_panel_pose_version", 0)

    // XR Wall Anchoring (pin the flat cinema panel to a real wall via ARCore)
    val xrPlayerWallPinned = Preference("pref_xr_player_wall_pinned", false)
    val xrPlayerWallAnchorUuid = Preference("pref_xr_player_wall_anchor_uuid", "")
    // Main-process preference controlling Theater Audio Room LCR lock vs free screen placement.
    val xrTheaterLcrScreenLock = Preference("pref_xr_theater_lcr_screen_lock", true)

    // XR Cinema Immersion (graduated passthrough dimming opacity in [0.0f, 1.0f] and curved screen)
    val xrPlayerPassthroughOpacity = Preference("pref_xr_player_passthrough_opacity", 0.0f)
    val xrPlayerCurvedScreen = Preference("pref_xr_player_curved_screen", false)
    val xrPlayerScreenCurvePreset = Preference("pref_xr_player_screen_curve_preset", "flat")

    // Automatic Episode Download Hygiene
    val autoDeleteWatchedDownloads = Preference("pref_auto_delete_watched_downloads", false)
    val autoDownloadNextEpisodesCount = Preference("pref_auto_download_next_episodes_count", 0)

    // TV Frame-Rate Matching (opt-in non-seamless HDMI mode switch on non-QMS displays)
    val playerTvNonSeamlessFrameRateSwitch = Preference("pref_player_tv_non_seamless_frame_rate_switch", false)

    // XR Space Mode
    val xrLaunchMode = Preference("pref_xr_launch_mode", "home")
    val xrLastUsedMode = Preference("pref_xr_last_used_mode", "home")
    val xrAutoEnterFullSpaceOnPlayback = Preference("pref_xr_auto_enter_full_space_on_playback", true)
    val xrReturnHomeSpaceAfterPlayback = Preference("pref_xr_return_home_space_after_playback", false)

    val subtitleTextColor = Preference("pref_subtitle_text_color", android.graphics.Color.WHITE)
    val subtitleBackgroundColor = Preference("pref_subtitle_background_color", android.graphics.Color.TRANSPARENT)
    val libassSubtitleUsage = Preference("pref_libass_subtitle_usage", "auto")
    val voiceControlEnabled = Preference("pref_voice_control_enabled", true)
    val voiceGestureHand = Preference<String?>("pref_voice_gesture_hand", "left")
    val voiceAssistantVerbosity = Preference("pref_voice_assistant_verbosity", "balanced")
    val voiceAssistantSpoilerPolicy = Preference("pref_voice_assistant_spoiler_policy", "cautious")
    val voiceAssistantSpokenReplies = Preference("pref_voice_assistant_spoken_replies", true)
    val voiceAssistantVoice = Preference<String?>("pref_voice_assistant_voice", null)
    val voiceAssistantCloudApiKey = Preference<String?>("pref_voice_assistant_cloud_api_key", null)
    val voiceAssistantGemmaEnabled = Preference("pref_voice_assistant_gemma_enabled", false)
    val voiceAssistantGemmaBackend = Preference("pref_voice_assistant_gemma_backend", "Requires Model")
    val voiceAssistantCpuInference = Preference("pref_voice_assistant_cpu_inference", false)

    val voiceAssistantStoreTranscripts = Preference("pref_voice_assistant_store_transcripts", false)
    val voiceAssistantWebSearchEnabled = Preference("pref_voice_assistant_web_search_enabled", true)
    val voiceAssistantSearxngUrl = Preference<String?>("pref_voice_assistant_searxng_url", null)

    val smartPreferOriginalAudio = Preference("pref_smart_prefer_original_audio", true)
    val smartForcedSubtitles = Preference("pref_smart_forced_subtitles", true)
    val smartSpokenLanguages = Preference<String?>("pref_smart_spoken_languages", null)
    val seriesLanguageOverrides = Preference<String?>("pref_series_language_overrides", null)

    // "Finish setup" home prompt card (only languages remains in Stage 2).
    val finishSetupLanguagesDismissed = Preference("pref_finish_setup_languages_dismissed", false)

    // Seerr (Jellyseerr/Overseerr)
    val seerrEnabled = Preference("pref_seerr_enabled", false)
    val seerrUrl = Preference<String?>("pref_seerr_url", null)
    val seerrApiKey = Preference<String?>("pref_seerr_api_key", null)

    // TMDB
    val tmdbApiKey = Preference<String?>("pref_tmdb_api_key", null)
    val tmdbAutoMatch = Preference("pref_tmdb_auto_match", true)

    // OMDb (IMDb-backed)
    val omdbApiKey = Preference<String?>("pref_omdb_api_key", null)

    // App lock (local-only passkey / PIN gate)
    val appLockEnabled = Preference("pref_app_lock_enabled", false)
    val appLockCredentialId = Preference<String?>("pref_app_lock_credential_id", null)
    val appLockUserHandle = Preference<String?>("pref_app_lock_user_handle", null)
    val appLockMode = Preference("pref_app_lock_mode", "off")
    val appLockPinHash = Preference<String?>("pref_app_lock_pin_hash", null)
    val appLockPinSalt = Preference<String?>("pref_app_lock_pin_salt", null)
    val appLockPinKdfParams = Preference<String?>("pref_app_lock_pin_kdf_params", null)
    val appLockWipeOnFail = Preference("pref_app_lock_wipe_on_fail", false)
    val appLockMaxAttempts = Preference("pref_app_lock_max_attempts", 3)

    // Two-phase App Lock enrollment / DEK reset state (main process only)
    val appLockDekResetPending = Preference("pref_app_lock_dek_reset_pending", false)
    val appLockPendingMode = Preference<String?>("pref_app_lock_pending_mode", null)
    val appLockPendingDekDigest = Preference<String?>("pref_app_lock_pending_dek_digest", null)
    val appLockPendingPinHash = Preference<String?>("pref_app_lock_pending_pin_hash", null)
    val appLockPendingPinSalt = Preference<String?>("pref_app_lock_pending_pin_salt", null)
    val appLockPendingPinKdfParams = Preference<String?>("pref_app_lock_pending_pin_kdf_params", null)

    val contentEncryptionEnabled = Preference("pref_content_encryption_enabled", false)

    // Logging
    val loggingEnabled = Preference("pref_logging_enabled", false)

    /**
     * Read-only legacy helper for Stage 2 upgrade migration (`SessionActivationCoordinator`).
     * Never written in Stage 2; superseded by [standaloneModeChosen] and Room server rows.
     */
    fun hasLegacyOnboardingCompleted(): Boolean =
        runCatching { sharedPreferences.getBoolean(LEGACY_ONBOARDING_COMPLETED_KEY, false) }
            .getOrDefault(false)

    /**
     * Serializes App Lock enrollment commits (`AppLockManager`) and Companion preference
     * reconciliation (`SessionActivationCoordinator.reconcileActiveUserPreferences`) on default
     * [sharedPreferences].
     */
    fun <T> withAppLockPolicyLock(block: () -> T): T = appLockPolicyLock.withLock(block)

    /**
     * Atomically commits a batch of main-process [sharedPreferences] writes via synchronous
     * `Editor.commit()`. Forbidden in non-main processes (`:xrplayer`).
     */
    fun commitMainBatch(block: (SharedPreferences.Editor) -> Unit): Boolean {
        if (!isMainProcess) {
            val message = "Non-main process attempted to commit main SharedPreferences batch"
            if (isDebuggable) throw IllegalStateException(message)
            Timber.e(message)
            return false
        }
        val editor = sharedPreferences.edit()
        block(editor)
        return editor.commit()
    }

    /**
     * Routing `contains` check: respects the 15-key `spatialfin_xr_player_prefs` split
     * (including queued writes and one-time fallback to default [sharedPreferences]).
     */
    fun contains(preference: Preference<*>): Boolean {
        val key = preference.backendName
        if (key in XR_PLAYER_PREF_KEYS) {
            synchronized(xrWriteLock) {
                if (pendingXrWrites.containsKey(key)) {
                    return pendingXrWrites[key] != null
                }
            }
            return xrPlayerSharedPreferences.contains(key) || sharedPreferences.contains(key)
        }
        return sharedPreferences.contains(key)
    }

    inline fun <reified T> getValue(preference: Preference<T>): T = getValueInternal(preference)

    inline fun <reified T> setValue(preference: Preference<T>, value: T) =
        setValueInternal(preference, value)

    @PublishedApi
    internal fun <T> getValueInternal(preference: Preference<T>): T {
        val key = preference.backendName
        if (key in XR_PLAYER_PREF_KEYS) {
            synchronized(xrWriteLock) {
                if (pendingXrWrites.containsKey(key)) {
                    @Suppress("UNCHECKED_CAST")
                    return (pendingXrWrites[key] ?: preference.defaultValue) as T
                }
            }
            val targetPrefs =
                if (xrPlayerSharedPreferences.contains(key)) {
                    xrPlayerSharedPreferences
                } else if (sharedPreferences.contains(key)) {
                    sharedPreferences
                } else {
                    xrPlayerSharedPreferences
                }
            return readTyped(targetPrefs, preference, allowResetOnFailure = isMainProcess)
        }
        return readTyped(sharedPreferences, preference, allowResetOnFailure = isMainProcess)
    }

    @PublishedApi
    internal fun <T> setValueInternal(preference: Preference<T>, value: T) {
        val key = preference.backendName
        if (key in XR_PLAYER_PREF_KEYS) {
            writeXrPlayerPrefsBatchInternal(mapOf(preference to value))
            return
        }
        if (!isMainProcess) {
            val message = "Non-main process attempted to write main preference: $key"
            if (isDebuggable) throw IllegalStateException(message)
            Timber.e(message)
            return
        }
        val editor = sharedPreferences.edit()
        putTyped(editor, preference, value)
        editor.apply()
    }

    /**
     * Atomically writes a batch of `:xrplayer`-mutable UI preferences (e.g., 8-key player pose
     * updates) into `spatialfin_xr_player_prefs`. Unchanged values are skipped as no-ops.
     * In the main process, flushes synchronously via `Editor.commit()`. In `:xrplayer`,
     * coalesces writes onto a dedicated single-thread IO executor that uses `Editor.commit()`.
     */
    fun writeXrPlayerPrefsBatch(writes: Map<Preference<*>, Any?>) {
        writeXrPlayerPrefsBatchInternal(writes)
    }

    private fun writeXrPlayerPrefsBatchInternal(writes: Map<Preference<*>, Any?>) {
        val changed = LinkedHashMap<Preference<*>, Any?>()
        for ((pref, rawValue) in writes) {
            require(pref.backendName in XR_PLAYER_PREF_KEYS) {
                "Preference ${pref.backendName} is not routed to $XR_PLAYER_PREFS_NAME"
            }
            val current = runCatching { getValueInternal(pref) }.getOrNull()
            if (current != rawValue) {
                changed[pref] = rawValue
            }
        }
        if (changed.isEmpty()) return

        if (isMainProcess) {
            val editor = xrPlayerSharedPreferences.edit()
            for ((pref, value) in changed) {
                putTyped(editor, pref, value)
            }
            editor.commit()
            return
        }

        val shouldSchedule: Boolean
        synchronized(xrWriteLock) {
            for ((pref, value) in changed) {
                pendingXrWrites[pref.backendName] = value
            }
            if (!xrFlushScheduled) {
                xrFlushScheduled = true
                shouldSchedule = true
            } else {
                shouldSchedule = false
            }
        }
        if (shouldSchedule) {
            xrPrefsExecutor.execute { drainPendingXrWrites() }
        }
    }

    private fun drainPendingXrWrites() {
        while (true) {
            val batch: Map<String, Any?>
            synchronized(xrWriteLock) {
                if (pendingXrWrites.isEmpty()) {
                    xrFlushScheduled = false
                    return
                }
                batch = LinkedHashMap(pendingXrWrites)
                pendingXrWrites.clear()
            }
            val editor = xrPlayerSharedPreferences.edit()
            for ((key, value) in batch) {
                putRaw(editor, key, value)
            }
            editor.commit()
        }
    }

    /**
     * Blocks up to [timeoutMs] until all queued `spatialfin_xr_player_prefs` commits on the
     * single-thread IO executor have finished. Call before `:xrplayer` process exit.
     */
    fun flushXrPlayerPrefsSync(timeoutMs: Long = 500L): Boolean {
        if (isMainProcess) return true
        val hasPending =
            synchronized(xrWriteLock) {
                pendingXrWrites.isNotEmpty() || xrFlushScheduled
            }
        if (!hasPending) return true
        val latch = CountDownLatch(1)
        return try {
            xrPrefsExecutor.execute {
                try {
                    drainPendingXrWrites()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Timber.w(e, "AppPreferences: flushXrPlayerPrefsSync failed")
            false
        }
    }

    private fun <T> readTyped(
        prefs: SharedPreferences,
        preference: Preference<T>,
        allowResetOnFailure: Boolean,
    ): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            when (preference.defaultValue) {
                is Boolean -> prefs.getBoolean(preference.backendName, preference.defaultValue) as T
                is Int -> prefs.getInt(preference.backendName, preference.defaultValue) as T
                is Long -> prefs.getLong(preference.backendName, preference.defaultValue) as T
                is Float -> prefs.getFloat(preference.backendName, preference.defaultValue) as T
                is String? -> prefs.getString(preference.backendName, preference.defaultValue) as T
                is Set<*> ->
                    prefs.getStringSet(
                        preference.backendName,
                        preference.defaultValue as Set<String>,
                    ) as T
                else -> preference.defaultValue
            }
        } catch (_: Exception) {
            Timber.w(
                "Failed to load ${preference.backendName} preference. Returning default value..."
            )
            if (allowResetOnFailure) {
                runCatching { setValueInternal(preference, preference.defaultValue) }
            }
            preference.defaultValue
        }
    }

    private fun putTyped(editor: SharedPreferences.Editor, preference: Preference<*>, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        when (preference.defaultValue) {
            is Boolean -> editor.putBoolean(preference.backendName, value as Boolean)
            is Int -> editor.putInt(preference.backendName, value as Int)
            is Long -> editor.putLong(preference.backendName, value as Long)
            is Float -> editor.putFloat(preference.backendName, value as Float)
            is String? -> editor.putString(preference.backendName, value as String?)
            is Set<*> -> editor.putStringSet(preference.backendName, value as Set<String>)
            else -> throw IllegalArgumentException("Unsupported preference type for ${preference.backendName}")
        }
    }

    private fun putRaw(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
            else -> throw IllegalArgumentException("Unsupported raw preference type for $key")
        }
    }

    fun getSmartLanguageSettings(context: Context): SmartLanguageSettings {
        return SmartLanguageSettings(
            preferOriginalAudio = getValue(smartPreferOriginalAudio),
            forcedSubtitlesInUnderstoodAudio = getValue(smartForcedSubtitles),
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
        setValue(smartForcedSubtitles, settings.forcedSubtitlesInUnderstoodAudio)
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

    companion object {
        const val NETWORK_SHARE_HOME_VISIBLE_PREFIX = "pref_network_share_home_visible_"
        const val XR_PLAYER_PREFS_NAME = "spatialfin_xr_player_prefs"
        private const val LEGACY_ONBOARDING_COMPLETED_KEY = "pref_onboarding_completed"

        /**
         * Exact 15 `:xrplayer`-mutable UI preference keys routed to [XR_PLAYER_PREFS_NAME].
         * `pref_xr_app_panel_scale` remains in default `sharedPreferences` (main process only).
         */
        val XR_PLAYER_PREF_KEYS: Set<String> = setOf(
            "pref_xr_player_panel_x",
            "pref_xr_player_panel_y",
            "pref_xr_player_panel_z",
            "pref_xr_player_panel_rot_x",
            "pref_xr_player_panel_rot_y",
            "pref_xr_player_panel_rot_z",
            "pref_xr_player_panel_rot_w",
            "pref_xr_player_panel_pose_version",
            "pref_xr_player_panel_scale",
            "pref_xr_player_panel_depth",
            "pref_xr_player_passthrough_opacity",
            "pref_xr_player_screen_curve_preset",
            "pref_xr_player_curved_screen",
            "pref_xr_player_wall_pinned",
            "pref_xr_player_wall_anchor_uuid",
        )
    }
}
