package dev.spatialfin.beam

import android.graphics.Color as AndroidColor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.settings.domain.AppPreferences

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
    val companionConnected =
        remember(appPreferences.getValue(appPreferences.companionUrl), appPreferences.getValue(appPreferences.companionToken)) {
            appPreferences.getValue(appPreferences.companionUrl).isNotBlank() &&
                appPreferences.getValue(appPreferences.companionToken).isNotBlank()
        }

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Settings",
                body = "Playback, subtitles, downloads, and more.",
            )
        }
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
                BeamSettingChoiceRow(
                    title = "Max bitrate",
                    value = if (playerMaxBitrate == 0L) "Auto" else "${playerMaxBitrate / 1_000_000L} Mbps",
                    actions = listOf("Auto", "40 Mbps", "20 Mbps", "10 Mbps", "5 Mbps"),
                    onAction = { choice ->
                        playerMaxBitrate =
                            when (choice) {
                                "40 Mbps" -> 40_000_000L
                                "20 Mbps" -> 20_000_000L
                                "10 Mbps" -> 10_000_000L
                                "5 Mbps" -> 5_000_000L
                                else -> 0L
                            }
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
        item {
            BeamSettingsSection(title = "Downloads") {
                BeamSettingSwitchRow(
                    title = "Allow downloads over mobile data",
                    checked = downloadOverMobileData,
                    onCheckedChange = {
                        downloadOverMobileData = it
                        appPreferences.setValue(appPreferences.downloadOverMobileData, it)
                    },
                )
                BeamSettingSwitchRow(
                    title = "Allow downloads while roaming",
                    checked = downloadWhenRoaming,
                    onCheckedChange = {
                        downloadWhenRoaming = it
                        appPreferences.setValue(appPreferences.downloadWhenRoaming, it)
                    },
                )
            }
        }
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
        item {
            BeamSettingsSection(title = "Diagnostics") {
                BeamSettingSwitchRow(
                    title = "Upload diagnostics to companion",
                    checked = loggingEnabled,
                    onCheckedChange = {
                        loggingEnabled = it
                        appPreferences.setValue(appPreferences.loggingEnabled, it)
                    },
                )
                Button(
                    onClick = { BeamCompanionLogUploader.flushNow() },
                    enabled = loggingEnabled && companionConnected,
                ) {
                    Text("Upload Logs Now")
                }
            }
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
