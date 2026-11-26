package dev.spatialfin.tv

import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.EntryPointAccessors
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.models.Preference
import dev.jdtech.jellyfin.settings.presentation.enums.QualityOption
import dev.jdtech.jellyfin.core.llm.LlmManagerEntryPoint
import dev.jdtech.jellyfin.presentation.ai.BeamAiCoreManagementCard
import dev.jdtech.jellyfin.presentation.ai.BeamGemmaManagementCard
import kotlinx.coroutines.launch

/**
 * Categories mirror the Beam settings hub so users hopping between a phone and
 * a TV find the same surface organized the same way. A handful of TV-hostile
 * rows (PIN setup via dpad, text-heavy API keys) are elided from the detail
 * panes below — users can pair a phone to set those.
 */
internal data class TvSettingsCategoryDef(val key: String, val title: String, val subtitle: String)

internal val TV_SETTINGS_CATEGORIES = listOf(
    TvSettingsCategoryDef("device_setup", "Device setup", "Pair another device, servers, shares, account"),
    TvSettingsCategoryDef("playback", "Playback", "Quality, seek, chapters"),
    TvSettingsCategoryDef("subtitles", "Subtitles", "Size, color, background"),
    TvSettingsCategoryDef("language", "Language", "Preferred audio & subtitles"),
    TvSettingsCategoryDef("interface", "Interface", "Theme, home sections, display"),
    TvSettingsCategoryDef("voice", "Voice assistant", "Commands, voice, & on-device AI"),
    TvSettingsCategoryDef("security", "Security", "App lock & encryption"),
    TvSettingsCategoryDef("seerr", "Jellyseerr", "Request integration"),
    TvSettingsCategoryDef("network", "Network", "Metadata keys & timeouts"),
    TvSettingsCategoryDef("cache", "Cache", "Image cache & size"),
    TvSettingsCategoryDef("cast", "Cast", "Receiver name & enabled"),
    TvSettingsCategoryDef("diagnostics", "Diagnostics", "Logs & telemetry"),
)

private data class TvPrefChanges(
    val revision: Int = 0,
    val changedKeys: Set<String> = emptySet(),
    val reloadAll: Boolean = false,
) {
    fun matches(pref: Preference<*>): Boolean = reloadAll || pref.backendName in changedKeys
}

/**
 * TV settings two-pane: focusable category list on the left, scrollable detail
 * pane on the right. Selecting a category via dpad updates the right pane.
 * Each row re-uses the same preference keys as Beam / main Settings so state
 * round-trips correctly through AppPreferences.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSettingsPreferences(
    appPreferences: AppPreferences,
    isServerAuthenticated: Boolean,
    hasServers: Boolean,
    onOpenSetUpFromDevice: () -> Unit,
    onOpenAddShare: () -> Unit,
    onConnectOrSwitchServer: () -> Unit,
    onOpenUsers: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategoryKey by rememberSaveable { mutableStateOf(TV_SETTINGS_CATEGORIES.first().key) }
    var prefsRevision by remember { mutableIntStateOf(0) }
    val pendingChangedKeys = remember { LinkedHashSet<String>() }
    var pendingReloadAll by remember { mutableStateOf(false) }
    var prefChanges by remember { mutableStateOf(TvPrefChanges()) }
    val firstTileFocus = remember { FocusRequester() }

    DisposableEffect(appPreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            synchronized(pendingChangedKeys) {
                if (key == null) {
                    pendingReloadAll = true
                } else {
                    pendingChangedKeys.add(key)
                }
            }
            prefsRevision++
        }
        appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            appPreferences.sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(prefsRevision) {
        if (prefsRevision == 0) return@LaunchedEffect
        val (snapshotKeys, fullReload) = synchronized(pendingChangedKeys) {
            val keysCopy = pendingChangedKeys.toSet()
            pendingChangedKeys.clear()
            val all = pendingReloadAll
            pendingReloadAll = false
            keysCopy to all
        }
        prefChanges = TvPrefChanges(
            revision = prefsRevision,
            changedKeys = snapshotKeys,
            reloadAll = fullReload,
        )
    }

    Row(
        // Cap kept tight enough that the LazyColumn always has a viewport to
        // scroll within; focusRestorer() drives DPAD focus traversal, the
        // LazyColumn itself drives the bring-into-view scroll.
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 420.dp, max = 640.dp)
            .focusGroup()
            .focusRestorer { firstTileFocus },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LazyColumn(
            modifier = Modifier.width(280.dp).focusRestorer(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(TV_SETTINGS_CATEGORIES, key = { _, cat -> cat.key }) { index, cat ->
                TvSettingsCategoryTile(
                    title = cat.title,
                    subtitle = cat.subtitle,
                    selected = selectedCategoryKey == cat.key,
                    onSelect = { selectedCategoryKey = cat.key },
                    modifier = if (index == 0) Modifier.focusRequester(firstTileFocus) else Modifier,
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
                "device_setup" -> TvDeviceSetupPrefs(
                    appPreferences = appPreferences,
                    prefsRevision = prefChanges.revision,
                    isServerAuthenticated = isServerAuthenticated,
                    hasServers = hasServers,
                    onOpenSetUpFromDevice = onOpenSetUpFromDevice,
                    onOpenAddShare = onOpenAddShare,
                    onConnectOrSwitchServer = onConnectOrSwitchServer,
                    onOpenUsers = onOpenUsers,
                    onLogout = onLogout,
                )
                "playback" -> TvPlaybackPrefs(appPreferences, prefChanges)
                "subtitles" -> TvSubtitlePrefs(appPreferences, prefChanges)
                "language" -> TvLanguagePrefs(appPreferences, prefChanges)
                "interface" -> TvInterfacePrefs(appPreferences, prefChanges)
                "voice" -> TvVoicePrefs(appPreferences, prefChanges)
                "security" -> TvSecurityPrefs(appPreferences, prefChanges)
                "seerr" -> TvSeerrPrefs(appPreferences, prefChanges)
                "network" -> TvNetworkPrefs(appPreferences, prefChanges)
                "cache" -> TvCachePrefs(appPreferences, prefChanges)
                "cast" -> TvCastPrefs(appPreferences, prefChanges)
                "diagnostics" -> TvDiagnosticsPrefs(appPreferences, prefChanges)
            }
        }
    }
}

@Composable
private fun TvDeviceSetupPrefs(
    appPreferences: AppPreferences,
    prefsRevision: Int,
    isServerAuthenticated: Boolean,
    hasServers: Boolean,
    onOpenSetUpFromDevice: () -> Unit,
    onOpenAddShare: () -> Unit,
    onConnectOrSwitchServer: () -> Unit,
    onOpenUsers: () -> Unit,
    onLogout: () -> Unit,
) {
    val companionUrl = remember(prefsRevision, appPreferences) {
        appPreferences.getValue(appPreferences.companionUrl)
    }
    TvPrefSectionTitle("Device setup")
    Text(
        text = if (companionUrl.isNotBlank()) {
            "SpatialFin Companion server: $companionUrl"
        } else {
            "Set up this TV from your phone or computer, connect a Jellyfin server, or add SMB/NFS network shares."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onOpenSetUpFromDevice) {
        Text("Set up with Companion app")
    }
    Button(onClick = onConnectOrSwitchServer) {
        Text(if (hasServers) "Switch server" else "Connect to Jellyfin server")
    }
    Button(onClick = onOpenAddShare) {
        Text("Add network share")
    }
    if (isServerAuthenticated) {
        Button(onClick = onOpenUsers) {
            Text("Switch user")
        }
        Button(onClick = onLogout) {
            Text("Sign out")
        }
    }
}

@Composable
private fun TvSettingsCategoryTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    // Foundation Box + clickable() — TV-material3 Card.onClick was not
    // exposing the tile as focusable to the spatial focus search, so
    // DPAD_DOWN from the focused first tile would skip every subsequent
    // tile and land on the right-pane switches. The explicit clickable()
    // chain integrates cleanly with the LazyColumn's focus traversal and
    // bring-into-view behavior.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .onFocusChanged { 
                focused = it.isFocused 
                if (it.isFocused) onSelect()
            }
            .clickable(onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
            label = { androidx.compose.material3.Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.border,
            ),
        )
    }
}

@Composable
private fun TvPlaybackPrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Playback")
    var playerMaxBitrate by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.playerMaxBitrate)) }
    var forceDirectPlay by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerForceDirectPlay)) }
    var audioPassthrough by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.playerAudioPassthrough) ?: "auto")
    }
    var chapterMarkers by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerChapterMarkers)) }
    var trickplay by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerTrickplay)) }
    var skipButton by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)) }
    var autoSkip by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)) }
    var chapterDetection by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerChapterIntroDetection)) }
    var aniSkip by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.playerAniSkipEnabled)) }
    var nonSeamlessFrameRateSwitch by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.playerTvNonSeamlessFrameRateSwitch))
    }
    var seekBack by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.playerSeekBackInc) / 1000L) }
    var seekFwd by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.playerSeekForwardInc) / 1000L) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.playerMaxBitrate)) {
            playerMaxBitrate = appPreferences.getValue(appPreferences.playerMaxBitrate)
        }
        if (prefChanges.matches(appPreferences.playerForceDirectPlay)) {
            forceDirectPlay = appPreferences.getValue(appPreferences.playerForceDirectPlay)
        }
        if (prefChanges.matches(appPreferences.playerAudioPassthrough)) {
            audioPassthrough = appPreferences.getValue(appPreferences.playerAudioPassthrough) ?: "auto"
        }
        if (prefChanges.matches(appPreferences.playerChapterMarkers)) {
            chapterMarkers = appPreferences.getValue(appPreferences.playerChapterMarkers)
        }
        if (prefChanges.matches(appPreferences.playerTrickplay)) {
            trickplay = appPreferences.getValue(appPreferences.playerTrickplay)
        }
        if (prefChanges.matches(appPreferences.playerMediaSegmentsSkipButton)) {
            skipButton = appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton)
        }
        if (prefChanges.matches(appPreferences.playerMediaSegmentsAutoSkip)) {
            autoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip)
        }
        if (prefChanges.matches(appPreferences.playerChapterIntroDetection)) {
            chapterDetection = appPreferences.getValue(appPreferences.playerChapterIntroDetection)
        }
        if (prefChanges.matches(appPreferences.playerAniSkipEnabled)) {
            aniSkip = appPreferences.getValue(appPreferences.playerAniSkipEnabled)
        }
        if (prefChanges.matches(appPreferences.playerTvNonSeamlessFrameRateSwitch)) {
            nonSeamlessFrameRateSwitch = appPreferences.getValue(appPreferences.playerTvNonSeamlessFrameRateSwitch)
        }
        if (prefChanges.matches(appPreferences.playerSeekBackInc)) {
            seekBack = appPreferences.getValue(appPreferences.playerSeekBackInc) / 1000L
        }
        if (prefChanges.matches(appPreferences.playerSeekForwardInc)) {
            seekFwd = appPreferences.getValue(appPreferences.playerSeekForwardInc) / 1000L
        }
    }
    val currentQuality = QualityOption.fromBps(playerMaxBitrate)
    val shortLabel: (QualityOption) -> String = {
        when (it) {
            QualityOption.AUTO -> "Auto"
            QualityOption.DIRECT_PLAY -> "Direct Play"
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
    // TV is the form factor where this actually matters — a Chromecast/Android TV box in
    // front of an AV receiver is the only common SpatialFin setup with a real HDMI/eARC
    // bitstream path. Kept on the other shells for parity.
    val passthroughLabels = mapOf("auto" to "Auto", "off" to "Off", "force" to "Force on")
    TvPrefChoiceRow(
        title = "Audio passthrough",
        value = passthroughLabels[audioPassthrough] ?: "Auto",
        actions = passthroughLabels.values.toList(),
        onAction = { choice ->
            val picked = passthroughLabels.entries.firstOrNull { it.value == choice }?.key ?: "auto"
            audioPassthrough = picked
            appPreferences.setValue(appPreferences.playerAudioPassthrough, picked)
        },
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
    TvPrefSwitchRow(
        title = "Fallback chapter intro detection",
        checked = chapterDetection,
        onCheckedChange = { chapterDetection = it; appPreferences.setValue(appPreferences.playerChapterIntroDetection, it) },
    )
    TvPrefSwitchRow(
        title = "Community anime skip times (AniSkip)",
        checked = aniSkip,
        onCheckedChange = { aniSkip = it; appPreferences.setValue(appPreferences.playerAniSkipEnabled, it) },
    )
    TvPrefSwitchRow(
        title = "Allow HDMI mode-switch for frame-rate matching",
        checked = nonSeamlessFrameRateSwitch,
        onCheckedChange = {
            nonSeamlessFrameRateSwitch = it
            appPreferences.setValue(appPreferences.playerTvNonSeamlessFrameRateSwitch, it)
        },
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
private fun TvSubtitlePrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Subtitles")
    var libassUsage by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.libassSubtitleUsage)) }
    var subtitleSize by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.xrSubtitleSize).toLong()) }
    var subtitleTextColor by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.subtitleTextColor).toLong()) }
    var subtitleBackgroundColor by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.subtitleBackgroundColor).toLong()) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.libassSubtitleUsage)) {
            libassUsage = appPreferences.getValue(appPreferences.libassSubtitleUsage)
        }
        if (prefChanges.matches(appPreferences.xrSubtitleSize)) {
            subtitleSize = appPreferences.getValue(appPreferences.xrSubtitleSize).toLong()
        }
        if (prefChanges.matches(appPreferences.subtitleTextColor)) {
            subtitleTextColor = appPreferences.getValue(appPreferences.subtitleTextColor).toLong()
        }
        if (prefChanges.matches(appPreferences.subtitleBackgroundColor)) {
            subtitleBackgroundColor = appPreferences.getValue(appPreferences.subtitleBackgroundColor).toLong()
        }
    }

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
private fun TvLanguagePrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Language")
    var audioLang by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.preferredAudioLanguage).orEmpty()) }
    var subtitleLang by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.preferredSubtitleLanguage).orEmpty()) }
    var nonAnimeSubDisabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled)) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.preferredAudioLanguage)) {
            val stored = appPreferences.getValue(appPreferences.preferredAudioLanguage).orEmpty()
            if (stored != audioLang.trim()) audioLang = stored
        }
        if (prefChanges.matches(appPreferences.preferredSubtitleLanguage)) {
            val stored = appPreferences.getValue(appPreferences.preferredSubtitleLanguage).orEmpty()
            if (stored != subtitleLang.trim()) subtitleLang = stored
        }
        if (prefChanges.matches(appPreferences.nonAnimeSubtitleDisabled)) {
            nonAnimeSubDisabled = appPreferences.getValue(appPreferences.nonAnimeSubtitleDisabled)
        }
    }

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
private fun TvVoicePrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Voice assistant")
    val context = LocalContext.current
    // Single Hilt entry point supplies the download / model managers —
    // same ones Beam uses, same ones SettingsViewModel uses. Gets us the
    // live DownloadState / ModelState / AICoreStatus flows without
    // rebuilding any plumbing just for TV.
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            LlmManagerEntryPoint::class.java,
        )
    }
    val llmDownloadManager = remember(entryPoint) { entryPoint.llmDownloadManager() }
    val llmModelManager = remember(entryPoint) { entryPoint.llmModelManager() }
    val llmScope = rememberCoroutineScope()
    val downloadState by llmDownloadManager.downloadState.collectAsStateWithLifecycle()
    val modelState by llmDownloadManager.modelState.collectAsStateWithLifecycle()
    val aiCoreStatus by llmModelManager.aiCoreStatus.collectAsStateWithLifecycle()

    var voiceEnabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceControlEnabled)) }
    var spokenReplies by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)) }
    var verbosity by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantVerbosity)) }
    var spoiler by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy)) }
    var assistantVoice by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantVoice).orEmpty())
    }
    var voicePickerVisible by rememberSaveable { mutableStateOf(false) }
    var cloudApiKey by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty())
    }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.voiceControlEnabled)) {
            voiceEnabled = appPreferences.getValue(appPreferences.voiceControlEnabled)
        }
        if (prefChanges.matches(appPreferences.voiceAssistantSpokenReplies)) {
            spokenReplies = appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)
        }
        if (prefChanges.matches(appPreferences.voiceAssistantVerbosity)) {
            verbosity = appPreferences.getValue(appPreferences.voiceAssistantVerbosity)
        }
        if (prefChanges.matches(appPreferences.voiceAssistantSpoilerPolicy)) {
            spoiler = appPreferences.getValue(appPreferences.voiceAssistantSpoilerPolicy)
        }
        if (prefChanges.matches(appPreferences.voiceAssistantVoice)) {
            assistantVoice = appPreferences.getValue(appPreferences.voiceAssistantVoice).orEmpty()
        }
        if (prefChanges.matches(appPreferences.voiceAssistantCloudApiKey)) {
            val stored = appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty()
            if (stored != cloudApiKey.trim()) cloudApiKey = stored
        }
    }

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
    // TV TTS voice selection: same dialog shell as XR/Beam. Dpad navigation
    // inside the radio-button list works out of the box — RadioButton has
    // focus state — though it's noticeably slower than a touch screen to
    // scrub through every installed voice.
    TvVoicePickerRow(
        currentVoice = assistantVoice,
        onClick = { voicePickerVisible = true },
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
    // Cloud fallback key. Dpad text entry is tedious but works with the
    // Google TV on-screen keyboard — previously this was gated behind
    // "use the phone companion" which is a worse UX when no phone is
    // around.
    TvPrefSecretField(
        title = "Cloud AI API key",
        value = cloudApiKey,
        label = "Optional Gemini API key",
        onValueChange = {
            cloudApiKey = it
            appPreferences.setValue(
                appPreferences.voiceAssistantCloudApiKey,
                it.trim().ifBlank { null },
            )
        },
    )
    // Reuse Beam's on-device model management cards verbatim — they use
    // androidx.compose.material3 primitives which render fine in the TV
    // settings pane. Users can download Gemma / AICore without needing a
    // companion app.
    BeamAiCoreManagementCard(
        status = aiCoreStatus,
        onDownload = { llmModelManager.downloadAiCoreFeature() },
        onReprobe = { llmScope.launch { llmModelManager.reprobeAiCore() } },
    )
    BeamGemmaManagementCard(
        downloadManager = llmDownloadManager,
        downloadScope = llmScope,
        downloadState = downloadState,
        modelState = modelState,
        appPreferences = appPreferences,
    )

    if (voicePickerVisible) {
        Dialog(
            onDismissRequest = { voicePickerVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.85f),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            ) {
                dev.jdtech.jellyfin.presentation.settings.LocalVoicePickerDialog.current?.invoke(
                    assistantVoice.ifBlank { null },
                    { selected ->
                        val normalized = selected?.trim()?.takeIf { it.isNotEmpty() }
                        appPreferences.setValue(
                            appPreferences.voiceAssistantVoice,
                            normalized,
                        )
                        assistantVoice = normalized.orEmpty()
                        voicePickerVisible = false
                    },
                    { voicePickerVisible = false },
                )
            }
        }
    }
}

@Composable
private fun TvVoicePickerRow(currentVoice: String, onClick: () -> Unit) {
    val summary = if (currentVoice.isBlank() || currentVoice in tvLegacyVoiceValues) {
        "System default"
    } else {
        currentVoice
    }
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Assistant voice", style = MaterialTheme.typography.bodyLarge)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val tvLegacyVoiceValues = setOf("male", "female", "system")

@Composable
private fun TvPrefSecretField(
    title: String,
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    var revealed by rememberSaveable(title) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = { revealed = !revealed }) {
                    androidx.compose.material3.Text(if (revealed) "Hide" else "Show", color = MaterialTheme.colorScheme.primary)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.border,
            ),
        )
    }
}

@Composable
private fun TvSecurityPrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Security")
    var contentEncryption by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.contentEncryptionEnabled)) }
    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision != 0 && prefChanges.matches(appPreferences.contentEncryptionEnabled)) {
            contentEncryption = appPreferences.getValue(appPreferences.contentEncryptionEnabled)
        }
    }
    Text(
        "App Lock and offline download encryption are configured locally on each device for security. Companion can only sync lock policy settings (wipe-on-fail and max attempts) when App Lock is enabled locally.",
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
private fun TvSeerrPrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Jellyseerr")
    var seerrEnabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrEnabled)) }
    var seerrUrl by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrUrl).orEmpty()) }
    var seerrApiKey by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.seerrApiKey).orEmpty()) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.seerrEnabled)) {
            seerrEnabled = appPreferences.getValue(appPreferences.seerrEnabled)
        }
        if (prefChanges.matches(appPreferences.seerrUrl)) {
            val stored = appPreferences.getValue(appPreferences.seerrUrl).orEmpty()
            if (stored != seerrUrl.trim()) seerrUrl = stored
        }
        if (prefChanges.matches(appPreferences.seerrApiKey)) {
            val stored = appPreferences.getValue(appPreferences.seerrApiKey).orEmpty()
            if (stored != seerrApiKey.trim()) seerrApiKey = stored
        }
    }

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
    // API key is secret-masked with show/hide, same as the cloud AI key.
    // Google TV's on-screen keyboard handles the typing; previously this
    // was punted to the phone companion which meant "you need another
    // device to finish setting this up."
    TvPrefSecretField(
        title = "API key",
        value = seerrApiKey,
        label = "Jellyseerr API key",
        onValueChange = {
            seerrApiKey = it
            appPreferences.setValue(appPreferences.seerrApiKey, it.trim().ifBlank { null })
        },
    )
}

@Composable
private fun TvDiagnosticsPrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Diagnostics")
    var logging by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.loggingEnabled)) }
    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision != 0 && prefChanges.matches(appPreferences.loggingEnabled)) {
            logging = appPreferences.getValue(appPreferences.loggingEnabled)
        }
    }
    TvPrefSwitchRow(
        title = "Upload diagnostics to companion",
        checked = logging,
        onCheckedChange = { logging = it; appPreferences.setValue(appPreferences.loggingEnabled, it) },
    )
}

@Composable
private fun TvInterfacePrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Interface")
    // TV still benefits from theme selection — a bright home screen behind
    // the settings pane is jarring at night. Dynamic colors are XR-only and
    // not exposed here.
    var theme by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.theme).orEmpty().ifBlank { "system" }) }
    var homeSuggestions by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.homeSuggestions)) }
    var homeContinue by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.homeContinueWatching)) }
    var homeNextUp by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.homeNextUp)) }
    var homeLatest by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.homeLatest)) }
    var extraInfo by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.displayExtraInfo)) }
    var ratings by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.displayRatings)) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.theme)) {
            theme = appPreferences.getValue(appPreferences.theme).orEmpty().ifBlank { "system" }
        }
        if (prefChanges.matches(appPreferences.homeSuggestions)) {
            homeSuggestions = appPreferences.getValue(appPreferences.homeSuggestions)
        }
        if (prefChanges.matches(appPreferences.homeContinueWatching)) {
            homeContinue = appPreferences.getValue(appPreferences.homeContinueWatching)
        }
        if (prefChanges.matches(appPreferences.homeNextUp)) {
            homeNextUp = appPreferences.getValue(appPreferences.homeNextUp)
        }
        if (prefChanges.matches(appPreferences.homeLatest)) {
            homeLatest = appPreferences.getValue(appPreferences.homeLatest)
        }
        if (prefChanges.matches(appPreferences.displayExtraInfo)) {
            extraInfo = appPreferences.getValue(appPreferences.displayExtraInfo)
        }
        if (prefChanges.matches(appPreferences.displayRatings)) {
            ratings = appPreferences.getValue(appPreferences.displayRatings)
        }
    }

    TvPrefChoiceRow(
        title = "Theme",
        value = theme.replaceFirstChar(Char::uppercase),
        actions = listOf("System", "Light", "Dark"),
        onAction = { choice ->
            theme = choice.lowercase()
            appPreferences.setValue(appPreferences.theme, theme)
        },
    )
    TvPrefSwitchRow(
        title = "Display suggestions",
        checked = homeSuggestions,
        onCheckedChange = { homeSuggestions = it; appPreferences.setValue(appPreferences.homeSuggestions, it) },
    )
    TvPrefSwitchRow(
        title = "Display continue watching",
        checked = homeContinue,
        onCheckedChange = { homeContinue = it; appPreferences.setValue(appPreferences.homeContinueWatching, it) },
    )
    TvPrefSwitchRow(
        title = "Display next up",
        checked = homeNextUp,
        onCheckedChange = { homeNextUp = it; appPreferences.setValue(appPreferences.homeNextUp, it) },
    )
    TvPrefSwitchRow(
        title = "Display latest items",
        checked = homeLatest,
        onCheckedChange = { homeLatest = it; appPreferences.setValue(appPreferences.homeLatest, it) },
    )
    TvPrefSwitchRow(
        title = "Display extra info",
        checked = extraInfo,
        onCheckedChange = { extraInfo = it; appPreferences.setValue(appPreferences.displayExtraInfo, it) },
    )
    TvPrefSwitchRow(
        title = "Display ratings",
        checked = ratings,
        onCheckedChange = { ratings = it; appPreferences.setValue(appPreferences.displayRatings, it) },
    )
}

@Composable
private fun TvNetworkPrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Network")
    // TV metadata enrichment uses the same TMDB / OMDB keys as the other
    // form factors — configuring them here means users don't need a phone
    // companion just to fill in an API key.
    var tmdbKey by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.tmdbApiKey).orEmpty()) }
    var omdbKey by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.omdbApiKey).orEmpty()) }
    var autoMatch by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.tmdbAutoMatch)) }
    var requestTimeout by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.requestTimeout)) }
    var connectTimeout by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.connectTimeout)) }
    var socketTimeout by rememberSaveable { mutableLongStateOf(appPreferences.getValue(appPreferences.socketTimeout)) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.tmdbApiKey)) {
            val stored = appPreferences.getValue(appPreferences.tmdbApiKey).orEmpty()
            if (stored != tmdbKey.trim()) tmdbKey = stored
        }
        if (prefChanges.matches(appPreferences.omdbApiKey)) {
            val stored = appPreferences.getValue(appPreferences.omdbApiKey).orEmpty()
            if (stored != omdbKey.trim()) omdbKey = stored
        }
        if (prefChanges.matches(appPreferences.tmdbAutoMatch)) {
            autoMatch = appPreferences.getValue(appPreferences.tmdbAutoMatch)
        }
        if (prefChanges.matches(appPreferences.requestTimeout)) {
            requestTimeout = appPreferences.getValue(appPreferences.requestTimeout)
        }
        if (prefChanges.matches(appPreferences.connectTimeout)) {
            connectTimeout = appPreferences.getValue(appPreferences.connectTimeout)
        }
        if (prefChanges.matches(appPreferences.socketTimeout)) {
            socketTimeout = appPreferences.getValue(appPreferences.socketTimeout)
        }
    }

    TvPrefSecretField(
        title = "TMDB API key",
        value = tmdbKey,
        label = "Paste your TMDB key",
        onValueChange = {
            tmdbKey = it
            appPreferences.setValue(appPreferences.tmdbApiKey, it.trim().ifBlank { null })
        },
    )
    TvPrefSecretField(
        title = "OMDb API key",
        value = omdbKey,
        label = "Paste your OMDb key",
        onValueChange = {
            omdbKey = it
            appPreferences.setValue(appPreferences.omdbApiKey, it.trim().ifBlank { null })
        },
    )
    TvPrefSwitchRow(
        title = "Automatic TMDB matching",
        checked = autoMatch,
        onCheckedChange = { autoMatch = it; appPreferences.setValue(appPreferences.tmdbAutoMatch, it) },
    )
    TvPrefTextField(
        title = "Request timeout (ms)",
        value = requestTimeout.toString(),
        label = "Milliseconds",
        onValueChange = { raw ->
            val v = raw.toLongOrNull() ?: 0L
            requestTimeout = v
            appPreferences.setValue(appPreferences.requestTimeout, v)
        },
    )
    TvPrefTextField(
        title = "Connect timeout (ms)",
        value = connectTimeout.toString(),
        label = "Milliseconds",
        onValueChange = { raw ->
            val v = raw.toLongOrNull() ?: 0L
            connectTimeout = v
            appPreferences.setValue(appPreferences.connectTimeout, v)
        },
    )
    TvPrefTextField(
        title = "Socket timeout (ms)",
        value = socketTimeout.toString(),
        label = "Milliseconds",
        onValueChange = { raw ->
            val v = raw.toLongOrNull() ?: 0L
            socketTimeout = v
            appPreferences.setValue(appPreferences.socketTimeout, v)
        },
    )
}

@Composable
private fun TvCachePrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    TvPrefSectionTitle("Cache")
    var enabled by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.imageCache)) }
    var sizeMb by rememberSaveable { mutableStateOf(appPreferences.getValue(appPreferences.imageCacheSize).toString()) }
    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.imageCache)) {
            enabled = appPreferences.getValue(appPreferences.imageCache)
        }
        if (prefChanges.matches(appPreferences.imageCacheSize)) {
            val stored = appPreferences.getValue(appPreferences.imageCacheSize).toString()
            if (stored != sizeMb.trim()) sizeMb = stored
        }
    }
    TvPrefSwitchRow(
        title = "Cache images",
        checked = enabled,
        onCheckedChange = { enabled = it; appPreferences.setValue(appPreferences.imageCache, it) },
    )
    TvPrefTextField(
        title = "Cache size (MB)",
        value = sizeMb,
        label = "Size in megabytes",
        onValueChange = { raw ->
            val v = raw.toIntOrNull() ?: 0
            sizeMb = v.toString()
            appPreferences.setValue(appPreferences.imageCacheSize, v)
        },
    )
}

@Composable
private fun TvCastPrefs(appPreferences: AppPreferences, prefChanges: TvPrefChanges) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fcastController = dev.jdtech.jellyfin.presentation.shell.LocalFCastReceiverController.current
    TvPrefSectionTitle("Cast")
    var enabled by rememberSaveable {
        mutableStateOf(fcastController?.isReceiverEnabled(appPreferences) ?: true)
    }
    var name by rememberSaveable {
        mutableStateOf(fcastController?.resolveDisplayName(appPreferences).orEmpty())
    }
    var dirty by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(prefChanges.revision) {
        if (prefChanges.revision == 0) return@LaunchedEffect
        if (prefChanges.matches(appPreferences.fcastReceiverEnabled)) {
            enabled = fcastController?.isReceiverEnabled(appPreferences) ?: true
        }
        if (prefChanges.matches(appPreferences.fcastReceiverDisplayName)) {
            val stored = fcastController?.resolveDisplayName(appPreferences).orEmpty()
            if (stored != name.trim()) name = stored
        }
    }

    TvPrefSwitchRow(
        title = "Receive casts on this TV",
        checked = enabled,
        onCheckedChange = {
            enabled = it
            appPreferences.setValue(appPreferences.fcastReceiverEnabled, it)
            dirty = true
        },
    )
    TvPrefTextField(
        title = "Receiver name",
        value = name,
        label = "Shown to other devices on the network",
        onValueChange = {
            name = it
            appPreferences.setValue(appPreferences.fcastReceiverDisplayName, it)
            dirty = true
        },
    )
    androidx.compose.material3.Button(
        onClick = {
            fcastController?.applyReceiverConfig(context, appPreferences)
            dirty = false
        },
        enabled = dirty,
    ) {
        androidx.compose.material3.Text(
            if (dirty) "Apply (re-advertise)" else "Up to date",
        )
    }
}
