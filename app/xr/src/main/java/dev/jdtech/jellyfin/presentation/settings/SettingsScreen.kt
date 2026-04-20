package dev.jdtech.jellyfin.presentation.settings

import android.app.Activity
import android.content.Intent
import android.app.UiModeManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockMode
import dev.spatialfin.unified.applock.PinSetupScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.SpatialDialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.settings.components.SettingsGroupCard
import dev.jdtech.jellyfin.presentation.settings.components.SmartLanguageSettingsDialog
import dev.jdtech.jellyfin.presentation.settings.components.SettingsTextInputDialog
import dev.jdtech.jellyfin.presentation.settings.components.VoicePickerDialog
import dev.spatialfin.presentation.settings.components.SubtitlePreviewCard
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceIntInput
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.presentation.utils.rememberSafePadding
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.presentation.theme.spacings
import dev.jdtech.jellyfin.presentation.utils.plus
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceGroup
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsAction
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsEvent
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsState
import dev.jdtech.jellyfin.settings.presentation.settings.SettingsViewModel
import dev.jdtech.jellyfin.utils.ObserveAsEvents
import dev.jdtech.jellyfin.utils.restart
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionScanner
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionState
import dev.jdtech.jellyfin.presentation.setup.welcome.CompanionViewModel
import android.text.format.DateFormat
import java.util.Date
import timber.log.Timber

@Composable
fun SettingsScreen(
    indexes: IntArray = intArrayOf(),
    navigateToSettings: (indexes: IntArray) -> Unit,
    navigateToServers: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToAbout: () -> Unit,
    navigateBack: () -> Unit,
    appPreferences: AppPreferences? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
    companionViewModel: CompanionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    val companionState by companionViewModel.state.collectAsStateWithLifecycle()
    var showCompanionDiscovery by remember { mutableStateOf(false) }

    var cloudApiKeyDraft by remember { mutableStateOf<String?>(null) }
    var seerrUrlDraft by remember { mutableStateOf<String?>(null) }
    var seerrApiKeyDraft by remember { mutableStateOf<String?>(null) }
    var tmdbApiKeyDraft by remember { mutableStateOf<String?>(null) }
    var omdbApiKeyDraft by remember { mutableStateOf<String?>(null) }
    var smartLanguageDraft by remember { mutableStateOf<SmartLanguageSettings?>(null) }
    var voicePickerVisible by remember { mutableStateOf(false) }
    var voicePickerInitial by remember { mutableStateOf<String?>(null) }

    val appLockManager = remember(context) { AppLockManager.from(context) }
    val appLockScope = rememberCoroutineScope()
    var pinSetupVisible by remember { mutableStateOf(false) }
    var deleteUnencryptedCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(true) { viewModel.loadPreferences(indexes, DeviceType.XR) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsEvent.NavigateToSettings -> navigateToSettings(event.indexes)
            is SettingsEvent.NavigateToUsers -> navigateToUsers()
            is SettingsEvent.NavigateToServers -> navigateToServers()
            is SettingsEvent.NavigateToAbout -> navigateToAbout()
            is SettingsEvent.UpdateTheme -> {
                val uiModeManager = context.getSystemService(UiModeManager::class.java)
                val nightMode =
                    when (event.theme) {
                        "system" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_AUTO
                            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        "light" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_NO
                            else AppCompatDelegate.MODE_NIGHT_NO
                        "dark" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_YES
                            else AppCompatDelegate.MODE_NIGHT_YES
                        else ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_AUTO
                            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    uiModeManager.setApplicationNightMode(nightMode)
                } else {
                    AppCompatDelegate.setDefaultNightMode(nightMode)
                }
            }
            is SettingsEvent.LaunchIntent -> {
                try {
                    context.startActivity(event.intent)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            is SettingsEvent.ShowCloudApiKeyDialog -> {
                cloudApiKeyDraft = event.currentValue.orEmpty()
            }
            is SettingsEvent.ShowSeerrUrlDialog -> {
                seerrUrlDraft = event.currentValue.orEmpty()
            }
            is SettingsEvent.ShowSeerrApiKeyDialog -> {
                seerrApiKeyDraft = event.currentValue.orEmpty()
            }
            is SettingsEvent.ShowTmdbApiKeyDialog -> {
                tmdbApiKeyDraft = event.currentValue.orEmpty()
            }
            is SettingsEvent.ShowOmdbApiKeyDialog -> {
                omdbApiKeyDraft = event.currentValue.orEmpty()
            }
            is SettingsEvent.ShowSmartLanguageDialog -> {
                smartLanguageDraft = event.settings
            }
            is SettingsEvent.ShowVoicePickerDialog -> {
                voicePickerInitial = event.currentVoiceName
                voicePickerVisible = true
            }
            is SettingsEvent.ShowCompanionDiscoveryDialog -> {
                showCompanionDiscovery = true
                companionViewModel.startScanning()
            }
            is SettingsEvent.RestartActivity -> {
                try {
                    (context as Activity).restart()
                } catch (_: Exception) {}
            }
            is SettingsEvent.ShowAppLockPinSetup -> {
                pinSetupVisible = true
            }
            is SettingsEvent.ShowDeleteUnencryptedDialog -> {
                appLockScope.launch {
                    val count = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { getServerDatabaseDao(context).countUnencryptedDownloads() }
                            .getOrDefault(0)
                    }
                    if (count == 0) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(SettingsR.string.settings_delete_unencrypted_none),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        deleteUnencryptedCount = count
                    }
                }
            }
            is SettingsEvent.ChangeAppLockMode -> {
                val activity = context as? androidx.fragment.app.FragmentActivity
                when (AppLockMode.fromKey(event.mode)) {
                    AppLockMode.Off -> {
                        appLockManager.disable()
                        viewModel.loadPreferences(indexes, DeviceType.XR)
                    }
                    AppLockMode.Biometric -> {
                        if (activity != null) {
                            appLockScope.launch {
                                when (val r = appLockManager.enroll(activity)) {
                                    is AppLockManager.EnrollResult.Success -> Unit
                                    is AppLockManager.EnrollResult.DeviceNotSecure -> {
                                        appLockManager.disable()
                                        viewModel.loadPreferences(indexes, DeviceType.XR)
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(SettingsR.string.settings_app_lock_device_not_secure),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    is AppLockManager.EnrollResult.Cancelled -> {
                                        appLockManager.disable()
                                        viewModel.loadPreferences(indexes, DeviceType.XR)
                                    }
                                    is AppLockManager.EnrollResult.Failed -> {
                                        appLockManager.disable()
                                        viewModel.loadPreferences(indexes, DeviceType.XR)
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(
                                                SettingsR.string.settings_app_lock_enroll_failed,
                                                r.message,
                                            ),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                    AppLockMode.Pin -> {
                        // Mode is already persisted by the PreferenceSelect handler;
                        // only require a PIN setup if one isn't stored yet.
                        if (!appLockManager.isConfigured()) {
                            pinSetupVisible = true
                        } else {
                            viewModel.loadPreferences(indexes, DeviceType.XR)
                        }
                    }
                }
            }
            is SettingsEvent.ConfigureAppLock -> {
                val activity = context as? androidx.fragment.app.FragmentActivity
                if (activity != null) {
                    appLockScope.launch {
                        if (event.enable) {
                            when (val result = appLockManager.enroll(activity)) {
                                is AppLockManager.EnrollResult.Success -> Unit
                                is AppLockManager.EnrollResult.DeviceNotSecure -> {
                                    appLockManager.rollbackEnable()
                                    viewModel.loadPreferences(indexes, DeviceType.XR)
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(SettingsR.string.settings_app_lock_device_not_secure),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                                is AppLockManager.EnrollResult.Cancelled -> {
                                    appLockManager.rollbackEnable()
                                    viewModel.loadPreferences(indexes, DeviceType.XR)
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(SettingsR.string.settings_app_lock_enroll_cancelled),
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                is AppLockManager.EnrollResult.Failed -> {
                                    appLockManager.rollbackEnable()
                                    viewModel.loadPreferences(indexes, DeviceType.XR)
                                    android.widget.Toast.makeText(
                                        context,
                                        context.getString(
                                            SettingsR.string.settings_app_lock_enroll_failed,
                                            result.message,
                                        ),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        } else {
                            appLockManager.disable()
                            viewModel.loadPreferences(indexes, DeviceType.XR)
                        }
                    }
                }
            }
        }
    }

    deleteUnencryptedCount?.let { count ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteUnencryptedCount = null },
            title = {
                androidx.compose.material3.Text(
                    stringResource(SettingsR.string.settings_delete_unencrypted_dialog_title, count)
                )
            },
            text = {
                androidx.compose.material3.Text(
                    stringResource(SettingsR.string.settings_delete_unencrypted_dialog_body)
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleteUnencryptedCount = null
                    appLockScope.launch {
                        val deleted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { deleteUnencryptedDownloads(context) }
                                .getOrDefault(0)
                        }
                        android.widget.Toast.makeText(
                            context,
                            context.getString(SettingsR.string.settings_delete_unencrypted_done, deleted),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    androidx.compose.material3.Text(
                        stringResource(SettingsR.string.settings_delete_unencrypted_dialog_confirm)
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteUnencryptedCount = null }) {
                    androidx.compose.material3.Text(
                        stringResource(SettingsR.string.settings_delete_unencrypted_dialog_cancel)
                    )
                }
            },
        )
    }

    if (pinSetupVisible) {
        SpatialDialog(onDismissRequest = { pinSetupVisible = false }) {
            PinSetupScreen(
                onConfirmed = { newPin ->
                    appLockScope.launch {
                        val ok = appLockManager.setPin(newPin)
                        pinSetupVisible = false
                        val msg =
                            if (ok) SettingsR.string.settings_app_lock_pin_set_ok
                            else SettingsR.string.settings_app_lock_pin_set_failed
                        android.widget.Toast.makeText(
                            context,
                            context.getString(msg),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        viewModel.loadPreferences(indexes, DeviceType.XR)
                    }
                },
                onCancel = {
                    pinSetupVisible = false
                    // If PIN mode was selected but user bailed without a PIN, revert to Off.
                    if (appLockManager.mode() == AppLockMode.Pin &&
                        !appLockManager.isConfigured()
                    ) {
                        appLockManager.disable()
                        viewModel.loadPreferences(indexes, DeviceType.XR)
                    }
                },
            )
        }
    }

    seerrUrlDraft?.let { currentValue ->
        SpatialDialog(onDismissRequest = { seerrUrlDraft = null }) {
            SettingsTextInputDialog(
                title = stringResource(SettingsR.string.settings_seerr_url),
                description = stringResource(SettingsR.string.settings_seerr_url_summary),
                initialValue = currentValue,
                onUpdate = { value ->
                    viewModel.saveSeerrUrl(value)
                    seerrUrlDraft = null
                },
                onDismissRequest = { seerrUrlDraft = null },
            )
        }
    }

    seerrApiKeyDraft?.let { currentValue ->
        SpatialDialog(onDismissRequest = { seerrApiKeyDraft = null }) {
            SettingsTextInputDialog(
                title = stringResource(SettingsR.string.settings_seerr_api_key),
                description = stringResource(SettingsR.string.settings_seerr_api_key_summary),
                initialValue = currentValue,
                onUpdate = { value ->
                    viewModel.saveSeerrApiKey(value)
                    seerrApiKeyDraft = null
                },
                onDismissRequest = { seerrApiKeyDraft = null },
            )
        }
    }

    smartLanguageDraft?.let { currentSettings ->
        SpatialDialog(onDismissRequest = { smartLanguageDraft = null }) {
            SmartLanguageSettingsDialog(
                initialSettings = currentSettings,
                onUpdate = { settings ->
                    viewModel.saveSmartLanguageSettings(settings)
                    smartLanguageDraft = null
                },
                onDismissRequest = { smartLanguageDraft = null },
            )
        }
    }

    if (voicePickerVisible) {
        SpatialDialog(onDismissRequest = { voicePickerVisible = false }) {
            VoicePickerDialog(
                initialVoiceName = voicePickerInitial,
                onSave = { selected ->
                    viewModel.saveVoiceAssistantVoice(selected)
                    voicePickerVisible = false
                },
                onDismissRequest = { voicePickerVisible = false },
            )
        }
    }

    tmdbApiKeyDraft?.let { currentValue ->
        SpatialDialog(onDismissRequest = { tmdbApiKeyDraft = null }) {
            SettingsTextInputDialog(
                title = stringResource(SettingsR.string.settings_tmdb_api_key),
                description = stringResource(SettingsR.string.settings_tmdb_api_key_summary),
                initialValue = currentValue,
                onUpdate = { value ->
                    viewModel.saveTmdbApiKey(value)
                    tmdbApiKeyDraft = null
                },
                onDismissRequest = { tmdbApiKeyDraft = null },
            )
        }
    }

    omdbApiKeyDraft?.let { currentValue ->
        SpatialDialog(onDismissRequest = { omdbApiKeyDraft = null }) {
            SettingsTextInputDialog(
                title = stringResource(SettingsR.string.settings_omdb_api_key),
                description = stringResource(SettingsR.string.settings_omdb_api_key_summary),
                initialValue = currentValue,
                onUpdate = { value ->
                    viewModel.saveOmdbApiKey(value)
                    omdbApiKeyDraft = null
                },
                onDismissRequest = { omdbApiKeyDraft = null },
            )
        }
    }

    cloudApiKeyDraft?.let { currentValue ->
        SpatialDialog(onDismissRequest = { cloudApiKeyDraft = null }) {
            SettingsTextInputDialog(
                title = stringResource(SettingsR.string.voice_cloud_api_key),
                description = stringResource(SettingsR.string.voice_cloud_api_key_summary),
                initialValue = currentValue,
                actionLabel = stringResource(SettingsR.string.voice_cloud_api_key_get_one),
                onActionClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://aistudio.google.com/app/apikey"),
                            ),
                        )
                    } catch (e: Exception) {
                        Timber.e(e)
                    }
                },
                onUpdate = { value ->
                    viewModel.saveCloudApiKey(value)
                    cloudApiKeyDraft = null
                },
                onDismissRequest = { cloudApiKeyDraft = null },
            )
        }
    }

    if (showCompanionDiscovery) {
        SpatialDialog(onDismissRequest = { showCompanionDiscovery = false }) {
            Surface(
                modifier = Modifier.widthIn(max = 600.dp).shadow(12.dp, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(SettingsR.string.welcome_companion_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    when (val s = companionState) {
                        CompanionState.Idle,
                        CompanionState.Scanning -> {
                            if (!state.companionSyncStatus.isNullOrEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Connected to: ${state.companionSyncStatus}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (state.lastSyncTime > 0) {
                                        val date = Date(state.lastSyncTime)
                                        val formatted = DateFormat.getMediumDateFormat(context).format(date) + " " + 
                                                        DateFormat.getTimeFormat(context).format(date)
                                        Text(
                                            text = "Last sync: $formatted",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { companionViewModel.syncNow() }) {
                                            Text("Sync Now")
                                        }
                                        TextButton(onClick = { companionViewModel.startScanning() }) {
                                            Text("Re-scan QR")
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = stringResource(SettingsR.string.welcome_companion_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(300.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    CompanionScanner(
                                        onPayloadFound = { companionViewModel.fetchAndApplyConfig(it) }
                                    )
                                }
                            }
                        }
                        CompanionState.Fetching -> {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalArrangement =
                                    Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(SettingsR.string.welcome_companion_fetching),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        CompanionState.Success -> {
                            Text(
                                text = stringResource(SettingsR.string.welcome_companion_success),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF4CAF50),
                            )
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(1500)
                                showCompanionDiscovery = false
                            }
                        }
                        is CompanionState.Error -> {
                            Text(
                                text = stringResource(SettingsR.string.welcome_companion_error, s.message),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = { companionViewModel.startScanning() },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(CoreR.string.retry))
                            }
                        }
                    }

                    TextButton(
                        onClick = { showCompanionDiscovery = false },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(CoreR.string.close))
                    }
                }
            }
        }
    }

    SettingsScreenLayout(
        title = indexes.last(),
        state = state,
        appPreferences = appPreferences,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnBackClick -> navigateBack()
                is SettingsAction.OnPreferenceClick -> viewModel.onAction(action)
                is SettingsAction.OnUpdate -> viewModel.onAction(action)
            }
        },
    )
}

@Composable
private fun SettingsScreenLayout(
    @StringRes title: Int,
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
    appPreferences: AppPreferences? = null,
) {
    val hasSubtitlePreferences = remember(state.preferenceGroups) {
        state.preferenceGroups.any { group ->
            group.preferences.any { pref ->
                (pref is PreferenceIntInput && pref.nameStringResource == SettingsR.string.xr_subtitle_size) ||
                    (pref is PreferenceSelect && pref.nameStringResource == SettingsR.string.libass_subtitle_usage)
            }
        }
    }
    val safePadding = rememberSafePadding()
    val contentPadding =
        PaddingValues(
            start = safePadding.start + MaterialTheme.spacings.default,
            top = MaterialTheme.spacings.large,
            end = safePadding.end + MaterialTheme.spacings.default,
            bottom = safePadding.bottom + MaterialTheme.spacings.large,
        )

    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    // Filter groups by resolved name/description across every preference row.
    // Cheap enough to recompute on each keystroke; the tree only has a few
    // hundred resolved strings and the search box is unlikely to be held down
    // while the groups animate.
    val filteredGroups = remember(state.preferenceGroups, searchQuery) {
        if (searchQuery.isBlank()) state.preferenceGroups
        else {
            val q = searchQuery.trim().lowercase()
            fun matches(@StringRes nameRes: Int?, @StringRes descRes: Int?): Boolean {
                if (nameRes != null && context.getString(nameRes).lowercase().contains(q)) return true
                if (descRes != null && context.getString(descRes).lowercase().contains(q)) return true
                return false
            }
            state.preferenceGroups.filter { group ->
                if (matches(group.nameStringResource, null)) return@filter true
                group.preferences.any { pref ->
                    matches(pref.nameStringResource, pref.descriptionStringRes)
                }
            }
        }
    }

    Column(
        modifier =
            Modifier.fillMaxSize().padding(top = safePadding.top + MaterialTheme.spacings.default),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.default),
    ) {
        XrBrowseHeader(
            title = stringResource(title),
            onBackClick = { onAction(SettingsAction.OnBackClick) },
            modifier =
                Modifier.padding(
                    start = safePadding.start + MaterialTheme.spacings.default,
                    end = safePadding.end + MaterialTheme.spacings.default,
                ),
        )
        Text(
            text = "Large controls and grouped settings tuned for hand-first XR interaction.",
            modifier =
                Modifier.padding(
                    start = safePadding.start + MaterialTheme.spacings.default,
                    end = safePadding.end + MaterialTheme.spacings.default,
                ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth()
                .padding(
                    start = safePadding.start + MaterialTheme.spacings.default,
                    end = safePadding.end + MaterialTheme.spacings.default,
                ),
            label = { Text("Search settings") },
            placeholder = { Text("Try \"bitrate\" or \"subtitle\"") },
            singleLine = true,
        )
        if (state.preferenceGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (filteredGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No settings match \"$searchQuery\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasSubtitlePreferences && appPreferences != null && searchQuery.isBlank()) {
                    item {
                        SubtitlePreviewCard(
                            appPreferences = appPreferences,
                            modifier = Modifier.widthIn(max = 860.dp),
                        )
                    }
                }
                items(filteredGroups) { group ->
                    SettingsGroupCard(
                        group = group,
                        onAction = onAction,
                        modifier = Modifier.widthIn(max = 860.dp),
                    )
                }
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsScreenLayoutPreview() {
    SpatialFinTheme {
        SettingsScreenLayout(
            title = CoreR.string.title_settings,
            state =
                SettingsState(
                    preferenceGroups =
                        listOf(
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_language,
                                            iconDrawableId = SettingsR.drawable.ic_languages,
                                        )
                                    ),
                            ),
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_interface,
                                            iconDrawableId = SettingsR.drawable.ic_palette,
                                        )
                                    ),
                            ),
                        )
                ),
            onAction = {},
        )
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
private interface SettingsDbEntryPoint {
    fun serverDatabaseDao(): dev.jdtech.jellyfin.database.ServerDatabaseDao
}

private fun getServerDatabaseDao(context: android.content.Context): dev.jdtech.jellyfin.database.ServerDatabaseDao =
    dagger.hilt.android.EntryPointAccessors
        .fromApplication(context.applicationContext, SettingsDbEntryPoint::class.java)
        .serverDatabaseDao()

/**
 * Deletes all rows in `downloadtasks` where `isEncrypted == 0` along with the
 * underlying file on disk. Returns the number of files actually removed.
 * Safe to run even after a partial failure — rows for files that could not
 * be deleted (e.g. because they don't exist) are still removed from the DB
 * so the "unencrypted count" stays honest.
 */
private fun deleteUnencryptedDownloads(context: android.content.Context): Int {
    val dao = getServerDatabaseDao(context)
    val rows = dao.getUnencryptedDownloads()
    var count = 0
    rows.forEach { task ->
        runCatching {
            val f = java.io.File(task.finalPath)
            if (f.exists() && f.delete()) count++
            // Clear the row so repeated taps on the Settings card reflect the new state.
            if (task.kind == dev.jdtech.jellyfin.models.DownloadTaskKind.PRIMARY) {
                dao.deleteDownloadTask(task.itemId, task.sourceId)
            } else {
                task.mediaStreamId?.let { dao.deleteDownloadTaskByMediaStreamId(it) }
            }
        }
    }
    return count
}
