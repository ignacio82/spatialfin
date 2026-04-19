package dev.jdtech.jellyfin.settings.domain

import dev.jdtech.jellyfin.settings.domain.models.Preference

fun AppPreferences.applyCompanionPreference(key: String, value: String?): Boolean =
    when (key) {
        preferredAudioLanguage.backendName -> {
            setValue(preferredAudioLanguage, value)
            true
        }
        preferredSubtitleLanguage.backendName -> {
            setValue(preferredSubtitleLanguage, value)
            true
        }
        animeAudioLanguage.backendName -> {
            setValue(animeAudioLanguage, value)
            true
        }
        animeSubtitleLanguage.backendName -> {
            setValue(animeSubtitleLanguage, value)
            true
        }
        nonAnimeAudioLanguage.backendName -> {
            setValue(nonAnimeAudioLanguage, value)
            true
        }
        nonAnimeSubtitleLanguage.backendName -> {
            setValue(nonAnimeSubtitleLanguage, value)
            true
        }
        nonAnimeSubtitleDisabled.backendName -> applyBoolean(nonAnimeSubtitleDisabled, value)
        smartPreferOriginalAudio.backendName -> applyBoolean(smartPreferOriginalAudio, value)
        smartSpokenLanguages.backendName -> {
            setValue(smartSpokenLanguages, value)
            true
        }
        theme.backendName -> applyString(theme, value)
        dynamicColors.backendName -> applyBoolean(dynamicColors, value)
        homeSuggestions.backendName -> applyBoolean(homeSuggestions, value)
        homeContinueWatching.backendName -> applyBoolean(homeContinueWatching, value)
        homeNextUp.backendName -> applyBoolean(homeNextUp, value)
        homeLatest.backendName -> applyBoolean(homeLatest, value)
        displayExtraInfo.backendName -> applyBoolean(displayExtraInfo, value)
        displayRatings.backendName -> applyBoolean(displayRatings, value)
        playerSeekBackInc.backendName -> applyLong(playerSeekBackInc, value)
        playerSeekForwardInc.backendName -> applyLong(playerSeekForwardInc, value)
        playerChapterMarkers.backendName -> applyBoolean(playerChapterMarkers, value)
        playerTrickplay.backendName -> applyBoolean(playerTrickplay, value)
        playerMaxBitrate.backendName -> applyLong(playerMaxBitrate, value)
        libassSubtitleUsage.backendName -> applyString(libassSubtitleUsage, value)
        loggingEnabled.backendName -> applyBoolean(loggingEnabled, value)
        voiceControlEnabled.backendName -> applyBoolean(voiceControlEnabled, value)
        voiceGestureHand.backendName -> {
            setValue(voiceGestureHand, value)
            true
        }
        voiceAssistantVerbosity.backendName -> applyString(voiceAssistantVerbosity, value)
        voiceAssistantSpoilerPolicy.backendName -> applyString(voiceAssistantSpoilerPolicy, value)
        voiceAssistantSpokenReplies.backendName -> applyBoolean(voiceAssistantSpokenReplies, value)
        voiceAssistantVoice.backendName -> {
            setValue(voiceAssistantVoice, value)
            true
        }
        voiceAssistantCloudApiKey.backendName -> {
            setValue(voiceAssistantCloudApiKey, value)
            true
        }
        voiceAssistantGemmaEnabled.backendName -> applyBoolean(voiceAssistantGemmaEnabled, value)
        voiceAssistantGemmaBackend.backendName -> applyString(voiceAssistantGemmaBackend, value)
        seerrEnabled.backendName -> applyBoolean(seerrEnabled, value)
        seerrUrl.backendName -> {
            setValue(seerrUrl, value)
            true
        }
        seerrApiKey.backendName -> {
            setValue(seerrApiKey, value)
            true
        }
        tmdbApiKey.backendName -> {
            setValue(tmdbApiKey, value)
            true
        }
        omdbApiKey.backendName -> {
            setValue(omdbApiKey, value)
            true
        }
        tmdbAutoMatch.backendName -> applyBoolean(tmdbAutoMatch, value)
        appLockMode.backendName -> applyString(appLockMode, value)
        appLockPinHash.backendName -> {
            setValue(appLockPinHash, value)
            true
        }
        appLockPinSalt.backendName -> {
            setValue(appLockPinSalt, value)
            true
        }
        appLockPinKdfParams.backendName -> {
            setValue(appLockPinKdfParams, value)
            true
        }
        appLockWipeOnFail.backendName -> applyBoolean(appLockWipeOnFail, value)
        appLockMaxAttempts.backendName -> applyInt(appLockMaxAttempts, value)
        contentEncryptionEnabled.backendName -> applyBoolean(contentEncryptionEnabled, value)
        else -> false
    }

private fun AppPreferences.applyString(preference: Preference<String>, value: String?): Boolean {
    if (value == null) return false
    setValue(preference, value)
    return true
}

private fun AppPreferences.applyBoolean(preference: Preference<Boolean>, value: String?): Boolean {
    val parsed = value?.toBooleanStrictOrNull() ?: return false
    setValue(preference, parsed)
    return true
}

private fun AppPreferences.applyLong(preference: Preference<Long>, value: String?): Boolean {
    val parsed = value?.toLongOrNull() ?: return false
    setValue(preference, parsed)
    return true
}

private fun AppPreferences.applyInt(preference: Preference<Int>, value: String?): Boolean {
    val parsed = value?.toIntOrNull() ?: return false
    setValue(preference, parsed)
    return true
}
