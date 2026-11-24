package dev.jdtech.jellyfin.settings.domain

import android.content.SharedPreferences

private val SYNCABLE_COMPANION_PREFERENCE_KEYS: Set<String> = setOf(
    "pref_audio_language",
    "pref_subtitle_language",
    "pref_anime_audio_language",
    "pref_anime_subtitle_language",
    "pref_non_anime_audio_language",
    "pref_non_anime_subtitle_language",
    "pref_non_anime_subtitle_disabled",
    "pref_smart_prefer_original_audio",
    "pref_smart_forced_subtitles",
    "pref_smart_spoken_languages",
    "pref_theme",
    "pref_dynamic_colors",
    "pref_display_extra_info",
    "pref_display_ratings",
    "pref_player_seek_back_inc",
    "pref_player_seek_forward_inc",
    "pref_player_chapter_markers",
    "pref_player_trickplay",
    "pref_player_max_bitrate",
    "pref_libass_subtitle_usage",
    "pref_logging_enabled",
    "pref_voice_control_enabled",
    "pref_voice_gesture_hand",
    "pref_voice_assistant_verbosity",
    "pref_voice_assistant_spoiler_policy",
    "pref_voice_assistant_spoken_replies",
    "pref_voice_assistant_voice",
    "pref_voice_assistant_cloud_api_key",
    "pref_voice_assistant_gemma_enabled",
    "pref_voice_assistant_gemma_backend",
    "pref_seerr_enabled",
    "pref_seerr_url",
    "pref_seerr_api_key",
    "pref_tmdb_api_key",
    "pref_omdb_api_key",
    "pref_tmdb_auto_match",
    "pref_app_lock_wipe_on_fail",
    "pref_app_lock_max_attempts",
)

/**
 * Pure, static allowlist of Companion-syncable preference keys.
 *
 * Local-only App Lock enrollment (`pref_app_lock_mode`, `pref_app_lock_enabled`,
 * `pref_app_lock_pin_*`, `pref_app_lock_pending_*`, `pref_app_lock_dek_reset_pending`),
 * offline download encryption (`pref_content_encryption_enabled`), local `HomeRowPreferences`
 * `home_*` keys, `pref_xr_app_panel_scale`, the 15 `spatialfin_xr_player_prefs` keys,
 * `pref_pending_syncplay_leave_*`, and internal reconciliation/digest JSON keys are
 * explicitly excluded.
 */
fun isSyncableCompanionPreferenceKey(key: String): Boolean =
    key in SYNCABLE_COMPANION_PREFERENCE_KEYS

/**
 * Type-safe raw preference reader avoiding `ClassCastException` on mixed SharedPreferences types.
 */
fun AppPreferences.readCompanionPreferenceRaw(key: String): String? {
    if (!isSyncableCompanionPreferenceKey(key)) return null
    return sharedPreferences.all[key]?.toString()
}

fun AppPreferences.applyCompanionPreference(key: String, value: String?): Boolean {
    val staging = CompanionPreferenceStaging(this)
    if (!staging.applyCompanionPreference(key, value)) return false
    val editor = sharedPreferences.edit()
    staging.writeTo(editor)
    editor.apply()
    return true
}

/**
 * Reverts an allowlisted preference key back to [localPreOverrideValue] (or removes it on `null`).
 * No-op on non-allowlisted keys. Bypasses the local App Lock enrollment gate ONLY when restoring
 * a non-null local snapshot so disabling App Lock cleanly restores prior local policy values.
 */
fun AppPreferences.revertCompanionPreference(
    key: String,
    localPreOverrideValue: String?,
): Boolean {
    val staging = CompanionPreferenceStaging(this)
    if (!staging.revertCompanionPreference(key, localPreOverrideValue)) return false
    val editor = sharedPreferences.edit()
    staging.writeTo(editor)
    editor.apply()
    return true
}

/**
 * In-memory staging overlay for multi-pass Companion preference reconciliation (Step A1f).
 * Reads reflect prior staged writes before falling back to [AppPreferences.sharedPreferences],
 * and [writeTo] emits the complete net diff onto a single [SharedPreferences.Editor] so the
 * caller can flush all preference and metadata updates in one atomic `Editor.commit()`.
 */
class CompanionPreferenceStaging(
    private val appPreferences: AppPreferences,
) {
    private sealed interface StagedOp {
        data object Remove : StagedOp
        data class PutString(val value: String?) : StagedOp
        data class PutBoolean(val value: Boolean) : StagedOp
        data class PutInt(val value: Int) : StagedOp
        data class PutLong(val value: Long) : StagedOp
    }

    private val staged = LinkedHashMap<String, StagedOp>()

    fun readRaw(key: String): String? {
        val op = staged[key]
        if (op != null) {
            return when (op) {
                StagedOp.Remove -> null
                is StagedOp.PutString -> op.value
                is StagedOp.PutBoolean -> op.value.toString()
                is StagedOp.PutInt -> op.value.toString()
                is StagedOp.PutLong -> op.value.toString()
            }
        }
        return appPreferences.sharedPreferences.all[key]?.toString()
    }

    fun applyCompanionPreference(key: String, value: String?): Boolean =
        stageAllowlisted(key, value, bypassAppLockGate = false)

    fun revertCompanionPreference(key: String, localPreOverrideValue: String?): Boolean {
        if (!isSyncableCompanionPreferenceKey(key)) return false
        if (localPreOverrideValue == null) {
            staged[key] = StagedOp.Remove
            return true
        }
        return stageAllowlisted(key, localPreOverrideValue, bypassAppLockGate = true)
    }

    fun stageMetadataString(key: String, value: String?) {
        staged[key] = if (value == null) StagedOp.Remove else StagedOp.PutString(value)
    }

    fun stageMetadataBoolean(key: String, value: Boolean) {
        staged[key] = StagedOp.PutBoolean(value)
    }

    fun writeTo(editor: SharedPreferences.Editor) {
        for ((key, op) in staged) {
            when (op) {
                StagedOp.Remove -> editor.remove(key)
                is StagedOp.PutString -> {
                    if (op.value == null) editor.remove(key) else editor.putString(key, op.value)
                }
                is StagedOp.PutBoolean -> editor.putBoolean(key, op.value)
                is StagedOp.PutInt -> editor.putInt(key, op.value)
                is StagedOp.PutLong -> editor.putLong(key, op.value)
            }
        }
    }

    private fun stageAllowlisted(
        key: String,
        value: String?,
        bypassAppLockGate: Boolean,
    ): Boolean {
        if (!isSyncableCompanionPreferenceKey(key)) return false
        return when (key) {
            appPreferences.preferredAudioLanguage.backendName,
            appPreferences.preferredSubtitleLanguage.backendName,
            appPreferences.animeAudioLanguage.backendName,
            appPreferences.animeSubtitleLanguage.backendName,
            appPreferences.nonAnimeAudioLanguage.backendName,
            appPreferences.nonAnimeSubtitleLanguage.backendName,
            appPreferences.smartSpokenLanguages.backendName,
            appPreferences.voiceGestureHand.backendName,
            appPreferences.voiceAssistantVoice.backendName,
            appPreferences.voiceAssistantCloudApiKey.backendName,
            appPreferences.seerrUrl.backendName,
            appPreferences.seerrApiKey.backendName,
            appPreferences.tmdbApiKey.backendName,
            appPreferences.omdbApiKey.backendName -> {
                staged[key] = StagedOp.PutString(value)
                true
            }
            appPreferences.theme.backendName,
            appPreferences.libassSubtitleUsage.backendName,
            appPreferences.voiceAssistantVerbosity.backendName,
            appPreferences.voiceAssistantSpoilerPolicy.backendName,
            appPreferences.voiceAssistantGemmaBackend.backendName -> {
                if (value == null) return false
                staged[key] = StagedOp.PutString(value)
                true
            }
            appPreferences.nonAnimeSubtitleDisabled.backendName,
            appPreferences.smartPreferOriginalAudio.backendName,
            appPreferences.smartForcedSubtitles.backendName,
            appPreferences.dynamicColors.backendName,
            appPreferences.displayExtraInfo.backendName,
            appPreferences.displayRatings.backendName,
            appPreferences.playerChapterMarkers.backendName,
            appPreferences.playerTrickplay.backendName,
            appPreferences.loggingEnabled.backendName,
            appPreferences.voiceControlEnabled.backendName,
            appPreferences.voiceAssistantSpokenReplies.backendName,
            appPreferences.voiceAssistantGemmaEnabled.backendName,
            appPreferences.seerrEnabled.backendName,
            appPreferences.tmdbAutoMatch.backendName -> stageBoolean(key, value)
            appPreferences.playerSeekBackInc.backendName,
            appPreferences.playerSeekForwardInc.backendName,
            appPreferences.playerMaxBitrate.backendName -> stageLong(key, value)
            appPreferences.appLockWipeOnFail.backendName -> {
                if (!bypassAppLockGate && !appPreferences.isLocalAppLockEnrolled()) return false
                stageBoolean(key, value)
            }
            appPreferences.appLockMaxAttempts.backendName -> {
                if (!bypassAppLockGate && !appPreferences.isLocalAppLockEnrolled()) return false
                stageInt(key, value)
            }
            else -> false
        }
    }

    private fun stageBoolean(key: String, value: String?): Boolean {
        val parsed = value?.toBooleanStrictOrNull() ?: return false
        staged[key] = StagedOp.PutBoolean(parsed)
        return true
    }

    private fun stageLong(key: String, value: String?): Boolean {
        val parsed = value?.toLongOrNull() ?: return false
        staged[key] = StagedOp.PutLong(parsed)
        return true
    }

    private fun stageInt(key: String, value: String?): Boolean {
        val parsed = value?.toIntOrNull() ?: return false
        staged[key] = StagedOp.PutInt(parsed)
        return true
    }
}

private fun AppPreferences.isLocalAppLockEnrolled(): Boolean {
    return when (getValue(appLockMode)) {
        "pin" -> !getValue(appLockPinHash).isNullOrBlank()
        "biometric" -> getValue(appLockEnabled)
        else -> false
    }
}
