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
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
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
                    BeamSettingTextField(
                        title = "Preferred audio language",
                        value = preferredAudioLanguage,
                        label = "Language code, e.g. eng",
                    ) {
                        preferredAudioLanguage = it
                        appPreferences.setValue(appPreferences.preferredAudioLanguage, it.ifBlank { null })
                    }
                    BeamSettingTextField(
                        title = "Preferred subtitle language",
                        value = preferredSubtitleLanguage,
                        label = "Language code, e.g. eng",
                    ) {
                        preferredSubtitleLanguage = it
                        appPreferences.setValue(appPreferences.preferredSubtitleLanguage, it.ifBlank { null })
                    }
                    BeamSettingTextField(
                        title = "Anime audio language",
                        value = animeAudioLanguage,
                        label = "Language code",
                    ) {
                        animeAudioLanguage = it
                        appPreferences.setValue(appPreferences.animeAudioLanguage, it.ifBlank { null })
                    }
                    BeamSettingTextField(
                        title = "Anime subtitle language",
                        value = animeSubtitleLanguage,
                        label = "Language code",
                    ) {
                        animeSubtitleLanguage = it
                        appPreferences.setValue(appPreferences.animeSubtitleLanguage, it.ifBlank { null })
                    }
                    BeamSettingTextField(
                        title = "Non-anime audio language",
                        value = nonAnimeAudioLanguage,
                        label = "Language code",
                    ) {
                        nonAnimeAudioLanguage = it
                        appPreferences.setValue(appPreferences.nonAnimeAudioLanguage, it.ifBlank { null })
                    }
                    BeamSettingSwitchRow(
                        title = "Disable subtitles for non-anime by default",
                        checked = nonAnimeSubtitleDisabled,
                        onCheckedChange = {
                            nonAnimeSubtitleDisabled = it
                            appPreferences.setValue(appPreferences.nonAnimeSubtitleDisabled, it)
                        },
                    )
                    BeamSettingTextField(
                        title = "Non-anime subtitle language",
                        value = nonAnimeSubtitleLanguage,
                        label = "Language code",
                    ) {
                        nonAnimeSubtitleLanguage = it
                        appPreferences.setValue(appPreferences.nonAnimeSubtitleLanguage, it.ifBlank { null })
                    }
                    BeamSettingSwitchRow(
                        title = "Prefer original audio when smart language is active",
                        checked = smartPreferOriginalAudio,
                        onCheckedChange = {
                            smartPreferOriginalAudio = it
                            appPreferences.setValue(appPreferences.smartPreferOriginalAudio, it)
                        },
                    )
                    BeamSettingTextField(
                        title = "Smart spoken languages",
                        value = smartSpokenLanguages,
                        label = "Comma-separated language codes",
                    ) {
                        smartSpokenLanguages = it
                        appPreferences.setValue(appPreferences.smartSpokenLanguages, it.ifBlank { null })
                    }
                }
            }
        }
        if (shouldShow("playback")) {
            item {
                BeamSettingsSection(title = "Playback") {
                    BeamSettingChoiceRow(
                        title = "libass subtitle rendering",
                        value = libassUsage.uppercase(),
                        actions = listOf("Auto", "Always", "Never"),
                        onAction = { choice ->
                            libassUsage = choice.lowercase()
                            appPreferences.setValue(appPreferences.libassSubtitleUsage, libassUsage)
                        },
                    )
                    BeamSettingSwitchRow(
                        title = "Show chapter markers",
                        checked = chapterMarkers,
                        onCheckedChange = {
                            chapterMarkers = it
                            appPreferences.setValue(appPreferences.playerChapterMarkers, it)
                        },
                    )
                    BeamSettingSwitchRow(
                        title = "Enable trickplay",
                        checked = trickplay,
                        onCheckedChange = {
                            trickplay = it
                            appPreferences.setValue(appPreferences.playerTrickplay, it)
                        },
                    )
                    BeamSettingNumberRow(
                        title = "Seek back seconds",
                        value = playerSeekBackInc,
                        onValueChange = {
                            playerSeekBackInc = it
                            appPreferences.setValue(appPreferences.playerSeekBackInc, it * 1000L)
                        },
                    )
                    BeamSettingNumberRow(
                        title = "Seek forward seconds",
                        value = playerSeekForwardInc,
                        onValueChange = {
                            playerSeekForwardInc = it
                            appPreferences.setValue(appPreferences.playerSeekForwardInc, it * 1000L)
                        },
                    )
                    val currentQualityOption = QualityOption.fromBps(playerMaxBitrate)
                    val shortQualityLabel: (QualityOption) -> String = { option ->
                        when (option) {
                            QualityOption.AUTO -> "Auto"
                            QualityOption.UHD -> "4K"
                            QualityOption.FHD -> "1080p"
                            QualityOption.HD -> "720p"
                            QualityOption.SD -> "480p"
                            QualityOption.LOW -> "360p"
                        }
                    }
                    BeamSettingChoiceRow(
                        title = "Playback quality",
                        value = stringResource(currentQualityOption.labelRes),
                        actions = QualityOption.entries.map(shortQualityLabel),
                        onAction = { choice ->
                            val picked = QualityOption.entries.firstOrNull { shortQualityLabel(it) == choice }
                                ?: QualityOption.AUTO
                            playerMaxBitrate = picked.bps
                            appPreferences.setValue(appPreferences.playerMaxBitrate, playerMaxBitrate)
                        },
                    )
                    BeamSettingSwitchRow(
                        title = "Show segment skip button",
                        checked = skipButton,
                        onCheckedChange = {
                            skipButton = it
                            appPreferences.setValue(appPreferences.playerMediaSegmentsSkipButton, it)
                        },
                    )
                    BeamSettingSwitchRow(
                        title = "Auto-skip intro and outro segments",
                        checked = autoSkip,
                        onCheckedChange = {
                            autoSkip = it
                            appPreferences.setValue(appPreferences.playerMediaSegmentsAutoSkip, it)
                        },
                    )
                    BeamSettingNumberRow(
                        title = "Next-episode threshold seconds",
                        value = nextEpisodeThreshold,
                        onValueChange = {
                            nextEpisodeThreshold = it
                            appPreferences.setValue(
                                appPreferences.playerMediaSegmentsNextEpisodeThreshold,
                                it * 1000L,
                            )
                        },
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
                    BeamSettingNumberRow(
                        title = "Subtitle size",
                        value = subtitleSize,
                        onValueChange = {
                            subtitleSize = it.coerceIn(28L, 96L)
                            appPreferences.setValue(appPreferences.xrSubtitleSize, subtitleSize.toInt())
                        },
                    )
                    BeamSettingChoiceRow(
                        title = "Subtitle text color",
                        value = beamColorName(subtitleTextColor.toInt()),
                        actions = listOf("White", "Yellow", "Cyan"),
                        onAction = { choice ->
                            subtitleTextColor = beamColorFromName(choice).toLong()
                            appPreferences.setValue(appPreferences.subtitleTextColor, subtitleTextColor.toInt())
                        },
                    )
                    BeamSettingChoiceRow(
                        title = "Subtitle background",
                        value = beamBackgroundName(subtitleBackgroundColor.toInt()),
                        actions = listOf("Transparent", "Black", "Dim"),
                        onAction = { choice ->
                            subtitleBackgroundColor = beamBackgroundFromName(choice).toLong()
                            appPreferences.setValue(appPreferences.subtitleBackgroundColor, subtitleBackgroundColor.toInt())
                        },
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
                    val modeLabel = when (AppLockMode.fromKey(appLockMode)) {
                        AppLockMode.Off -> "Off"
                        AppLockMode.Biometric -> "Biometric"
                        AppLockMode.Pin -> "PIN"
                    }
                    BeamSettingChoiceRow(
                        title = "App lock",
                        value = modeLabel,
                        actions = listOf("Off", "Biometric", "PIN"),
                        onAction = { choice ->
                            val targetMode = when (choice) {
                                "Biometric" -> AppLockMode.Biometric
                                "PIN" -> AppLockMode.Pin
                                else -> AppLockMode.Off
                            }
                            when (targetMode) {
                                AppLockMode.Off -> {
                                    appLockManager.disable()
                                    appLockMode = AppLockMode.Off.backendKey
                                }
                                AppLockMode.Biometric -> {
                                    val activity = context as? FragmentActivity
                                    if (activity == null) {
                                        Toast.makeText(context, "Biometric not available on this screen.", Toast.LENGTH_SHORT).show()
                                        return@BeamSettingChoiceRow
                                    }
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
                                AppLockMode.Pin -> {
                                    pinSetupVisible = true
                                }
                            }
                        },
                    )
                    if (AppLockMode.fromKey(appLockMode) == AppLockMode.Pin && appLockManager.isConfigured()) {
                        TextButton(onClick = { pinSetupVisible = true }) {
                            Text("Change PIN")
                        }
                    }
                    BeamSettingSwitchRow(
                        title = "Encrypt downloads on disk",
                        checked = contentEncryption,
                        onCheckedChange = { enabled ->
                            contentEncryption = enabled
                            appPreferences.setValue(appPreferences.contentEncryptionEnabled, enabled)
                        },
                    )
                }
            }
        }
        if (shouldShow("seerr")) {
            item {
                BeamSettingsSection(title = "Jellyseerr") {
                    BeamSettingSwitchRow(
                        title = "Enable Jellyseerr",
                        checked = seerrEnabled,
                        onCheckedChange = {
                            seerrEnabled = it
                            appPreferences.setValue(appPreferences.seerrEnabled, it)
                        },
                    )
                    BeamSettingTextField(
                        title = "Server URL",
                        value = seerrUrl,
                        label = "https://seerr.example.com",
                    ) {
                        seerrUrl = it
                        appPreferences.setValue(appPreferences.seerrUrl, it.ifBlank { null })
                    }
                    BeamSettingTextField(
                        title = "API key",
                        value = seerrApiKey,
                        label = "Jellyseerr API key",
                        secret = true,
                    ) {
                        seerrApiKey = it
                        appPreferences.setValue(appPreferences.seerrApiKey, it.ifBlank { null })
                    }
                }
            }
        }
        if (shouldShow("voice")) {
            item {
                BeamSettingsSection(title = "Voice Assistant") {
                    BeamSettingSwitchRow(
                        title = "Enable voice commands",
                        checked = voiceEnabled,
                        onCheckedChange = {
                            voiceEnabled = it
                            appPreferences.setValue(appPreferences.voiceControlEnabled, it)
                        },
                    )
                    BeamSettingSwitchRow(
                        title = "Speak assistant replies",
                        checked = spokenRepliesEnabled,
                        onCheckedChange = {
                            spokenRepliesEnabled = it
                            appPreferences.setValue(appPreferences.voiceAssistantSpokenReplies, it)
                        },
                    )
                    BeamSettingChoiceRow(
                        title = "Assistant verbosity",
                        value = voiceVerbosity.replaceFirstChar(Char::uppercase),
                        actions = listOf("Brief", "Balanced", "Detailed"),
                        onAction = { choice ->
                            voiceVerbosity = choice.lowercase()
                            appPreferences.setValue(appPreferences.voiceAssistantVerbosity, voiceVerbosity)
                        },
                    )
                    BeamSettingChoiceRow(
                        title = "Spoiler policy",
                        value = voiceSpoilerPolicy.replaceFirstChar(Char::uppercase),
                        actions = listOf("Strict", "Cautious", "Relaxed"),
                        onAction = { choice ->
                            voiceSpoilerPolicy = choice.lowercase()
                            appPreferences.setValue(appPreferences.voiceAssistantSpoilerPolicy, voiceSpoilerPolicy)
                        },
                    )
                    BeamSettingTextField(
                        title = "Cloud AI API key",
                        value = voiceCloudApiKey,
                        label = "Optional Gemini API key",
                        secret = true,
                    ) {
                        voiceCloudApiKey = it
                        appPreferences.setValue(appPreferences.voiceAssistantCloudApiKey, it.ifBlank { null })
                    }
                }
            }
        }
        if (shouldShow("companion")) {
            item {
                BeamSettingsSection(title = "Companion") {
                    Text(
                        if (companionConnected) "Companion connection saved" else "No companion connection saved",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenCompanion) {
                        Text(if (companionConnected) "Reconnect Companion" else "Connect Companion")
                    }
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
