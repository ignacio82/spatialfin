package dev.spatialfin.tv

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption

/**
 * Categories mirror the Beam settings hub so users hopping between a phone and
 * a TV find the same surface organized the same way. A handful of TV-hostile
 * rows (PIN setup via dpad, text-heavy API keys) are elided from the detail
 * panes below — users can pair a phone to set those.
 */
internal data class TvSettingsCategoryDef(val key: String, val title: String, val subtitle: String)

internal val TV_SETTINGS_CATEGORIES = listOf(
    TvSettingsCategoryDef("playback", "Playback", "Quality, seek, chapters"),
    TvSettingsCategoryDef("subtitles", "Subtitles", "Size, color, background"),
    TvSettingsCategoryDef("language", "Language", "Preferred audio & subtitles"),
    TvSettingsCategoryDef("voice", "Voice assistant", "Commands & replies"),
    TvSettingsCategoryDef("security", "Security", "App lock & encryption"),
    TvSettingsCategoryDef("seerr", "Jellyseerr", "Request integration"),
    TvSettingsCategoryDef("diagnostics", "Diagnostics", "Logs & telemetry"),
)

/**
 * TV settings two-pane: focusable category list on the left, scrollable detail
 * pane on the right. Selecting a category via dpad updates the right pane.
 * Each row re-uses the same preference keys as Beam / main Settings so state
 * round-trips correctly through AppPreferences.
 */
@Composable
internal fun TvSettingsPreferences(
    appPreferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    var selectedCategoryKey by rememberSaveable { mutableStateOf(TV_SETTINGS_CATEGORIES.first().key) }

    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 420.dp, max = 640.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Left pane: category list
        Column(
            modifier = Modifier.width(280.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TV_SETTINGS_CATEGORIES.forEach { cat ->
                TvSettingsCategoryTile(
                    title = cat.title,
                    subtitle = cat.subtitle,
                    selected = selectedCategoryKey == cat.key,
                    onSelect = { selectedCategoryKey = cat.key },
                )
            }
        }
        // Right pane: detail
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedCategoryKey) {
                "playback" -> TvPlaybackPrefs(appPreferences)
                "subtitles" -> TvSubtitlePrefs(appPreferences)
                "language" -> TvLanguagePrefs(appPreferences)
                "voice" -> TvVoicePrefs(appPreferences)
                "security" -> TvSecurityPrefs(appPreferences)
                "seerr" -> TvSeerrPrefs(appPreferences)
                "diagnostics" -> TvDiagnosticsPrefs(appPreferences)
            }
        }
    }
}

@Composable
private fun TvSettingsCategoryTile(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else -> Color.White.copy(alpha = 0.12f)
    }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        selected -> Color.White.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(16.dp))
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TvPrefSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TvPrefSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TvPrefChoiceRow(title: String, value: String, actions: List<String>, onAction: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$title · $value", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                val selected = action.equals(value, ignoreCase = true)
                Button(onClick = { onAction(action) }) {
                    Text(if (selected) "• $action" else action)
                }
            }
        }
    }
}

@Composable
private fun TvPrefTextField(
    title: String,
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TvPlaybackPrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Playback")
    var playerMaxBitrate by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.playerMaxBitrate)) }
    var forceDirectPlay by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerForceDirectPlay)) }
    var chapterMarkers by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerChapterMarkers)) }
    var trickplay by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerTrickplay)) }
    var skipButton by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)) }
    var autoSkip by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)) }
    var seekBack by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.playerSeekBackInc) / 1000L) }
    var seekFwd by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.playerSeekForwardInc) / 1000L) }
    val currentQuality = QualityOption.fromBps(playerMaxBitrate)
    val shortLabel: (QualityOption) -> String = {
        when (it) {
            QualityOption.AUTO -> "Auto"
            QualityOption.UHD -> "4K"
            QualityOption.FHD -> "1080p"
            QualityOption.HD -> "720p"
            QualityOption.SD -> "480p"
            QualityOption.LOW -> "360p"
        }
    }
    TvPrefChoiceRow(
        title = "Quality",
        value = stringResource(currentQuality.labelRes),
        actions = QualityOption.entries.map(shortLabel),
        onAction = { choice ->
            val picked = QualityOption.entries.firstOrNull { shortLabel(it) == choice } ?: QualityOption.AUTO
            playerMaxBitrate = picked.bps
            appPreferences.setValue(appPreferences.playerMaxBitrate, playerMaxBitrate)
        },
    )
    TvPrefSwitchRow(
        title = "Always attempt direct play",
        checked = forceDirectPlay,
        onCheckedChange = { forceDirectPlay = it; appPreferences.setValue(appPreferences.playerForceDirectPlay, it) },
    )
    TvPrefSwitchRow(
        title = "Show chapter markers",
        checked = chapterMarkers,
        onCheckedChange = { chapterMarkers = it; appPreferences.setValue(appPreferences.playerChapterMarkers, it) },
    )
    TvPrefSwitchRow(
        title = "Enable trickplay",
        checked = trickplay,
        onCheckedChange = { trickplay = it; appPreferences.setValue(appPreferences.playerTrickplay, it) },
    )
    TvPrefSwitchRow(
        title = "Show segment skip button",
        checked = skipButton,
        onCheckedChange = { skipButton = it; appPreferences.setValue(appPreferences.playerMediaSegmentsSkipButton, it) },
    )
    TvPrefSwitchRow(
        title = "Auto-skip intro / outro",
        checked = autoSkip,
        onCheckedChange = { autoSkip = it; appPreferences.setValue(appPreferences.playerMediaSegmentsAutoSkip, it) },
    )
    TvPrefTextField(
        title = "Seek back seconds",
        value = seekBack.toString(),
        label = "Numeric value",
        onValueChange = { raw ->
            val v = raw.toLongOrNull() ?: 0L
            seekBack = v
            appPreferences.setValue(appPreferences.playerSeekBackInc, v * 1000L)
        },
    )
    TvPrefTextField(
        title = "Seek forward seconds",
        value = seekFwd.toString(),
        label = "Numeric value",
        onValueChange = { raw ->
            val v = raw.toLongOrNull() ?: 0L
            seekFwd = v
            appPreferences.setValue(appPreferences.playerSeekForwardInc, v * 1000L)
        },
    )
}

@Composable
private fun TvSubtitlePrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Subtitles")
    var libassUsage by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.libassSubtitleUsage)) }
    var subtitleSize by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.xrSubtitleSize).toLong()) }
    var subtitleTextColor by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.subtitleTextColor).toLong()) }
    var subtitleBackgroundColor by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.subtitleBackgroundColor).toLong()) }

    TvPrefChoiceRow(
        title = "libass rendering",
        value = libassUsage.replaceFirstChar(Char::uppercase),
        actions = listOf("Auto", "Always", "Never"),
        onAction = { choice ->
            libassUsage = choice.lowercase()
            appPreferences.setValue(appPreferences.libassSubtitleUsage, libassUsage)
        },
    )
    TvPrefTextField(
        title = "Subtitle size",
        value = subtitleSize.toString(),
        label = "28–96",
        onValueChange = { raw ->
            val v = (raw.toLongOrNull() ?: 28L).coerceIn(28L, 96L)
            subtitleSize = v
            appPreferences.setValue(appPreferences.xrSubtitleSize, v.toInt())
        },
    )
    TvPrefChoiceRow(
        title = "Text color",
        value = when (subtitleTextColor.toInt()) {
            AndroidColor.YELLOW -> "Yellow"
            AndroidColor.CYAN -> "Cyan"
            else -> "White"
        },
        actions = listOf("White", "Yellow", "Cyan"),
        onAction = { choice ->
            val c = when (choice) { "Yellow" -> AndroidColor.YELLOW; "Cyan" -> AndroidColor.CYAN; else -> AndroidColor.WHITE }
            subtitleTextColor = c.toLong()
            appPreferences.setValue(appPreferences.subtitleTextColor, c)
        },
    )
    TvPrefChoiceRow(
        title = "Background",
        value = when (subtitleBackgroundColor.toInt()) {
            AndroidColor.BLACK -> "Black"
            0x99000000.toInt() -> "Dim"
            else -> "Transparent"
        },
        actions = listOf("Transparent", "Black", "Dim"),
        onAction = { choice ->
            val c = when (choice) { "Black" -> AndroidColor.BLACK; "Dim" -> 0x99000000.toInt(); else -> AndroidColor.TRANSPARENT }
            subtitleBackgroundColor = c.toLong()
            appPreferences.setValue(appPreferences.subtitleBackgroundColor, c)
        },
    )
}

@Composable
private fun TvLanguagePrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Language")
    var audioLang by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.preferredAudioLanguage).orEmpty()) }
    var subtitleLang by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.preferredSubtitleLanguage).orEmpty()) }
    var nonAnimeSubDisabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled)) }

    TvPrefTextField(
        title = "Preferred audio language",
        value = audioLang,
        label = "Language code, e.g. eng",
        onValueChange = {
            audioLang = it
            appPreferences.setValue(appPreferences.preferredAudioLanguage, it.ifBlank { null })
        },
    )
    TvPrefTextField(
        title = "Preferred subtitle language",
        value = subtitleLang,
        label = "Language code, e.g. eng",
        onValueChange = {
            subtitleLang = it
            appPreferences.setValue(appPreferences.preferredSubtitleLanguage, it.ifBlank { null })
        },
    )
    TvPrefSwitchRow(
        title = "Disable subtitles for non-anime by default",
        checked = nonAnimeSubDisabled,
        onCheckedChange = {
            nonAnimeSubDisabled = it
            appPreferences.setValue(appPreferences.nonAnimeSubtitleDisabled, it)
        },
    )
}

@Composable
private fun TvVoicePrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Voice assistant")
    var voiceEnabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceControlEnabled)) }
    var spokenReplies by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)) }
    var verbosity by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantVerbosity)) }
    var spoiler by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy)) }

    TvPrefSwitchRow(
        title = "Enable voice commands",
        checked = voiceEnabled,
        onCheckedChange = { voiceEnabled = it; appPreferences.setValue(appPreferences.voiceControlEnabled, it) },
    )
    TvPrefSwitchRow(
        title = "Speak assistant replies",
        checked = spokenReplies,
        onCheckedChange = { spokenReplies = it; appPreferences.setValue(appPreferences.voiceAssistantSpokenReplies, it) },
    )
    TvPrefChoiceRow(
        title = "Verbosity",
        value = verbosity.replaceFirstChar(Char::uppercase),
        actions = listOf("Brief", "Balanced", "Detailed"),
        onAction = { choice ->
            verbosity = choice.lowercase()
            appPreferences.setValue(appPreferences.voiceAssistantVerbosity, verbosity)
        },
    )
    TvPrefChoiceRow(
        title = "Spoiler policy",
        value = spoiler.replaceFirstChar(Char::uppercase),
        actions = listOf("Strict", "Cautious", "Relaxed"),
        onAction = { choice ->
            spoiler = choice.lowercase()
            appPreferences.setValue(appPreferences.voiceAssistantSpoilerPolicy, spoiler)
        },
    )
}

@Composable
private fun TvSecurityPrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Security")
    var contentEncryption by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.contentEncryptionEnabled)) }
    Text(
        "App lock with PIN or biometric is easiest to configure from the phone companion — TV remote PIN entry is cumbersome.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TvPrefSwitchRow(
        title = "Encrypt downloads on disk",
        checked = contentEncryption,
        onCheckedChange = { contentEncryption = it; appPreferences.setValue(appPreferences.contentEncryptionEnabled, it) },
    )
}

@Composable
private fun TvSeerrPrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Jellyseerr")
    var seerrEnabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrEnabled)) }
    var seerrUrl by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrUrl).orEmpty()) }

    TvPrefSwitchRow(
        title = "Enable Jellyseerr requests",
        checked = seerrEnabled,
        onCheckedChange = { seerrEnabled = it; appPreferences.setValue(appPreferences.seerrEnabled, it) },
    )
    TvPrefTextField(
        title = "Server URL",
        value = seerrUrl,
        label = "https://seerr.example.com",
        onValueChange = {
            seerrUrl = it
            appPreferences.setValue(appPreferences.seerrUrl, it.ifBlank { null })
        },
    )
    Text(
        "API keys are easier to enter from the phone companion.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TvDiagnosticsPrefs(appPreferences: AppPreferences) {
    TvPrefSectionTitle("Diagnostics")
    var logging by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.loggingEnabled)) }
    TvPrefSwitchRow(
        title = "Upload diagnostics to companion",
        checked = logging,
        onCheckedChange = { logging = it; appPreferences.setValue(appPreferences.loggingEnabled, it) },
    )
}
