package dev.jdtech.jellyfin.presentation.settings

import android.app.Activity
import android.content.Intent
import android.app.UiModeManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.SpatialDialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.film.components.XrBrowseHeader
import dev.jdtech.jellyfin.presentation.settings.components.SettingsGroupCard
import dev.jdtech.jellyfin.presentation.settings.components.SmartLanguageSettingsDialog
import dev.jdtech.jellyfin.presentation.settings.components.SettingsTextInputDialog
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
import timber.log.Timber

@Composable
fun SettingsScreen(
    indexes: IntArray = intArrayOf(),
    navigateToSettings: (indexes: IntArray) -> Unit,
    navigateToServers: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToAbout: () -> Unit,
    navigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()
    var cloudApiKeyDraft by remember { mutableStateOf<String?>(null) }
    var smartLanguageDraft by remember { mutableStateOf<SmartLanguageSettings?>(null) }

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
            is SettingsEvent.ShowSmartLanguageDialog -> {
                smartLanguageDraft = event.settings
            }
            is SettingsEvent.RestartActivity -> {
                try {
                    (context as Activity).restart()
                } catch (_: Exception) {}
            }
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

    SettingsScreenLayout(
        title = indexes.last(),
        state = state,
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
) {
    val safePadding = rememberSafePadding()
    val contentPadding =
        PaddingValues(
            start = safePadding.start + MaterialTheme.spacings.default,
            top = MaterialTheme.spacings.large,
            end = safePadding.end + MaterialTheme.spacings.default,
            bottom = safePadding.bottom + MaterialTheme.spacings.large,
        )

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
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(state.preferenceGroups) { group ->
                SettingsGroupCard(
                    group = group,
                    onAction = onAction,
                    modifier = Modifier.widthIn(max = 860.dp),
                )
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
