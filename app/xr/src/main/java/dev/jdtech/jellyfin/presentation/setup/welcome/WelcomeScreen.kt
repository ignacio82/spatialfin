package dev.jdtech.jellyfin.presentation.setup.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.SpatialDialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoService
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.player.xr.voice.GeminiNanoStatus
import dev.jdtech.jellyfin.presentation.settings.components.SmartLanguageSettingsDialog
import dev.jdtech.jellyfin.presentation.setup.components.RootLayout
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.language.LanguageCatalog
import dev.jdtech.jellyfin.settings.language.SmartLanguageSettings
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.spatialfin.presentation.theme.SpatialFinTheme

private enum class WelcomeStep {
    Connection,
    CompanionDiscovery,
    Languages,
    Ai,
}

@Composable
fun WelcomeScreen(
    appPreferences: AppPreferences,
    onContinueToServerSetup: () -> Unit,
    onContinueToLocalLibrary: () -> Unit,
    companionViewModel: CompanionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val geminiNanoService = remember(context) { GeminiNanoService(context.applicationContext) }

    val companionState by companionViewModel.state.collectAsStateWithLifecycle()

    var currentStepIndex by rememberSaveable { mutableIntStateOf(0) }
    var connectToServer by rememberSaveable { mutableStateOf(true) }
    var smartLanguageSettings by remember {
        mutableStateOf(appPreferences.getSmartLanguageSettings(context))
    }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var wantsApiKey by rememberSaveable {
        mutableStateOf(!appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).isNullOrBlank())
    }
    var wantsGemma by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantGemmaEnabled))
    }
    var cloudApiKey by rememberSaveable {
        mutableStateOf(appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty())
    }
    var aiStatus by remember { mutableStateOf<GeminiNanoStatus?>(null) }
    var aiStatusLoading by remember { mutableStateOf(true) }

    val steps = remember { WelcomeStep.entries }
    val currentStep = steps[currentStepIndex]

    LaunchedEffect(Unit) {
        aiStatusLoading = true
        aiStatus = runCatching { geminiNanoService.status() }.getOrNull()
        aiStatusLoading = false
    }

    LaunchedEffect(companionState) {
        if (companionState is CompanionState.Success) {
            val importedApiKey =
                appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty()
            if (importedApiKey.isNotBlank()) {
                wantsApiKey = true
                cloudApiKey = importedApiKey
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { geminiNanoService.destroy() }
    }

    fun completeOnboarding(saveChoices: Boolean) {
        val normalizedLanguages =
            smartLanguageSettings.spokenLanguageCodes
                .mapNotNull { LanguageCatalog.normalize(context, it) }
                .distinct()
                .ifEmpty { listOf(LanguageCatalog.defaultDeviceLanguageCode(context)) }

        if (saveChoices) {
            appPreferences.setSmartLanguageSettings(
                smartLanguageSettings.copy(spokenLanguageCodes = normalizedLanguages)
            )
            appPreferences.setValue(
                appPreferences.voiceAssistantCloudApiKey,
                if (wantsApiKey) cloudApiKey.trim().takeIf { it.isNotEmpty() } else null,
            )
            appPreferences.setValue(appPreferences.voiceAssistantGemmaEnabled, wantsGemma)
        }

        appPreferences.setValue(appPreferences.onboardingCompleted, true)

        if (connectToServer) onContinueToServerSetup() else onContinueToLocalLibrary()
    }

    fun goNext() {
        if (currentStepIndex == steps.lastIndex || (currentStep == WelcomeStep.CompanionDiscovery && companionState is CompanionState.Success)) {
            completeOnboarding(saveChoices = true)
        } else {
            currentStepIndex += 1
        }
    }

    fun skipCurrentStep() {
        if (currentStepIndex == steps.lastIndex) {
            completeOnboarding(saveChoices = true)
        } else {
            currentStepIndex += 1
        }
    }

    if (showLanguageDialog) {
        SpatialDialog(onDismissRequest = { showLanguageDialog = false }) {
            SmartLanguageSettingsDialog(
                initialSettings = smartLanguageSettings,
                onUpdate = {
                    smartLanguageSettings = it
                    showLanguageDialog = false
                },
                onDismissRequest = { showLanguageDialog = false },
            )
        }
    }

    WelcomeScreenLayout(
        currentStep = currentStep,
        currentStepIndex = currentStepIndex,
        totalSteps = steps.size,
        connectToServer = connectToServer,
        onConnectToServerChange = { connectToServer = it },
        companionState = companionState,
        onStartScanning = { companionViewModel.startScanning() },
        onPayloadFound = { companionViewModel.fetchAndApplyConfig(it) },
        smartLanguageSettings = smartLanguageSettings,
        onPreferOriginalAudioChange = {
            smartLanguageSettings = smartLanguageSettings.copy(preferOriginalAudio = it)
        },
        onEditLanguages = { showLanguageDialog = true },
        aiStatus = aiStatus,
        aiStatusLoading = aiStatusLoading,
        wantsGemma = wantsGemma,
        onWantsGemmaChange = { wantsGemma = it },
        wantsApiKey = wantsApiKey,
        onWantsApiKeyChange = { wantsApiKey = it },
        cloudApiKey = cloudApiKey,
        onCloudApiKeyChange = { cloudApiKey = it },
        onLearnMoreClick = { uriHandler.openUri("https://jellyfin.org/") },
        onOpenAiStudioClick = { uriHandler.openUri("https://aistudio.google.com/app/apikey") },
        onBackClick = { currentStepIndex = (currentStepIndex - 1).coerceAtLeast(0) },
        onSkipStepClick = ::skipCurrentStep,
        onSkipSetupClick = { completeOnboarding(saveChoices = false) },
        onNextClick = ::goNext,
    )
}

@Composable
private fun WelcomeScreenLayout(
    currentStep: WelcomeStep,
    currentStepIndex: Int,
    totalSteps: Int,
    connectToServer: Boolean,
    onConnectToServerChange: (Boolean) -> Unit,
    smartLanguageSettings: SmartLanguageSettings,
    onPreferOriginalAudioChange: (Boolean) -> Unit,
    onEditLanguages: () -> Unit,
    aiStatus: GeminiNanoStatus?,
    aiStatusLoading: Boolean,
    wantsGemma: Boolean,
    onWantsGemmaChange: (Boolean) -> Unit,
    wantsApiKey: Boolean,
    onWantsApiKeyChange: (Boolean) -> Unit,
    cloudApiKey: String,
    onCloudApiKeyChange: (String) -> Unit,
    companionState: CompanionState,
    onStartScanning: () -> Unit,
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
    onLearnMoreClick: () -> Unit,
    onOpenAiStudioClick: () -> Unit,
    onBackClick: () -> Unit,
    onSkipStepClick: () -> Unit,
    onSkipSetupClick: () -> Unit,
    onNextClick: () -> Unit,
) {
    val context = LocalContext.current
    val spokenLanguagesSummary =
        LanguageCatalog.summarize(context, smartLanguageSettings.spokenLanguageCodes, maxItems = 4)
    val stepTitle =
        when (currentStep) {
            WelcomeStep.Connection -> stringResource(SetupR.string.welcome_connect_title)
            WelcomeStep.CompanionDiscovery -> stringResource(SettingsR.string.welcome_companion_title)
            WelcomeStep.Languages -> stringResource(SetupR.string.welcome_languages_title)
            WelcomeStep.Ai -> stringResource(SetupR.string.welcome_ai_title)
        }
    val stepBody =
        when (currentStep) {
            WelcomeStep.Connection -> stringResource(SetupR.string.welcome_connect_body)
            WelcomeStep.CompanionDiscovery -> stringResource(SettingsR.string.welcome_companion_body)
            WelcomeStep.Languages -> stringResource(SetupR.string.welcome_languages_body)
            WelcomeStep.Ai -> stringResource(SetupR.string.welcome_ai_body)
        }
    val primaryActionText =
        when {
            currentStep != WelcomeStep.Ai ->
                stringResource(SetupR.string.welcome_btn_next)
            connectToServer ->
                stringResource(SetupR.string.welcome_btn_continue)
            else ->
                stringResource(SetupR.string.welcome_btn_continue_local)
        }

    RootLayout(padding = PaddingValues(horizontal = 24.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.align(Alignment.Center)
                    .widthIn(max = 920.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Image(
                                painter = painterResource(id = CoreR.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(SetupR.string.welcome),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            SetupR.string.welcome_step_counter,
                                            currentStepIndex + 1,
                                            totalSteps,
                                        ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                        TextButton(onClick = onSkipSetupClick) {
                            Text(stringResource(SetupR.string.welcome_btn_skip_all))
                        }
                    }

                    StepProgress(
                        currentStepIndex = currentStepIndex,
                        totalSteps = totalSteps,
                    )

                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stepTitle,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = stepBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    when (currentStep) {
                        WelcomeStep.Connection -> {
                            ConnectionStep(
                                connectToServer = connectToServer,
                                onConnectToServerChange = onConnectToServerChange,
                                onLearnMoreClick = onLearnMoreClick,
                            )
                        }
                        WelcomeStep.CompanionDiscovery -> {
                            CompanionDiscoveryStep(
                                state = companionState,
                                onStartScanning = onStartScanning,
                                onPayloadFound = onPayloadFound,
                            )
                        }
                        WelcomeStep.Languages -> {
                            LanguagesStep(
                                smartLanguageSettings = smartLanguageSettings,
                                spokenLanguagesSummary = spokenLanguagesSummary,
                                onPreferOriginalAudioChange = onPreferOriginalAudioChange,
                                onEditLanguages = onEditLanguages,
                            )
                        }
                        WelcomeStep.Ai -> {
                            AiStep(
                                aiStatus = aiStatus,
                                aiStatusLoading = aiStatusLoading,
                                wantsGemma = wantsGemma,
                                onWantsGemmaChange = onWantsGemmaChange,
                                wantsApiKey = wantsApiKey,
                                onWantsApiKeyChange = onWantsApiKeyChange,
                                cloudApiKey = cloudApiKey,
                                onCloudApiKeyChange = onCloudApiKeyChange,
                                onOpenAiStudioClick = onOpenAiStudioClick,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (currentStepIndex > 0) {
                                TextButton(onClick = onBackClick) {
                                    Text(stringResource(SetupR.string.welcome_btn_back))
                                }
                            }
                            TextButton(onClick = onSkipStepClick) {
                                Text(stringResource(SetupR.string.welcome_btn_skip_step))
                            }
                        }

                        Button(
                            onClick = onNextClick,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(
                                text = primaryActionText,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionDiscoveryStep(
    state: CompanionState,
    onStartScanning: () -> Unit,
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            CompanionState.Idle -> {
                StepChoiceCard(
                    title = stringResource(SettingsR.string.welcome_companion_scan),
                    body = stringResource(SettingsR.string.welcome_companion_body),
                    selected = false,
                    onClick = onStartScanning,
                )
            }
            CompanionState.Scanning -> {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    CompanionScanner(onPayloadFound = onPayloadFound)
                }
                Text(
                    text = stringResource(SettingsR.string.welcome_companion_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            CompanionState.Fetching -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(SettingsR.string.welcome_companion_fetching),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            CompanionState.Success -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(SettingsR.string.welcome_companion_success),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            is CompanionState.Error -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(SettingsR.string.welcome_companion_error, state.message),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(onClick = onStartScanning) {
                            Text(stringResource(CoreR.string.retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStep(
    connectToServer: Boolean,
    onConnectToServerChange: (Boolean) -> Unit,
    onLearnMoreClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepChoiceCard(
            title = stringResource(SetupR.string.welcome_connect_yes),
            body = stringResource(SetupR.string.welcome_connect_selected_server),
            selected = connectToServer,
            onClick = { onConnectToServerChange(true) },
        )
        StepChoiceCard(
            title = stringResource(SetupR.string.welcome_connect_local_only),
            body = stringResource(SetupR.string.welcome_connect_selected_local),
            selected = !connectToServer,
            onClick = { onConnectToServerChange(false) },
        )
        TextButton(onClick = onLearnMoreClick, modifier = Modifier.align(Alignment.Start)) {
            Text(stringResource(SetupR.string.welcome_btn_learn_more))
        }
    }
}

@Composable
private fun LanguagesStep(
    smartLanguageSettings: SmartLanguageSettings,
    spokenLanguagesSummary: String,
    onPreferOriginalAudioChange: (Boolean) -> Unit,
    onEditLanguages: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(SettingsR.string.settings_smart_language_prefer_original),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text =
                                if (smartLanguageSettings.preferOriginalAudio) {
                                    stringResource(SettingsR.string.settings_original_audio_enabled)
                                } else {
                                    stringResource(SettingsR.string.settings_original_audio_disabled)
                                },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = smartLanguageSettings.preferOriginalAudio,
                        onCheckedChange = onPreferOriginalAudioChange,
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.welcome_languages_summary_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = spokenLanguagesSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onEditLanguages,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(stringResource(SetupR.string.welcome_languages_edit))
                }

                Text(
                    text = stringResource(SetupR.string.welcome_languages_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AiStep(
    aiStatus: GeminiNanoStatus?,
    aiStatusLoading: Boolean,
    wantsGemma: Boolean,
    onWantsGemmaChange: (Boolean) -> Unit,
    wantsApiKey: Boolean,
    onWantsApiKeyChange: (Boolean) -> Unit,
    cloudApiKey: String,
    onCloudApiKeyChange: (String) -> Unit,
    onOpenAiStudioClick: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var apiKeyFieldValue by remember(cloudApiKey) {
        mutableStateOf(TextFieldValue(text = cloudApiKey, selection = TextRange(cloudApiKey.length)))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            aiStatusLoading -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(SetupR.string.welcome_ai_checking),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            aiStatus?.supported == true -> {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(SetupR.string.welcome_ai_ready_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(SetupR.string.welcome_ai_available),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                StepChoiceCard(
                    title = stringResource(SetupR.string.welcome_gemma_enable),
                    body = stringResource(SetupR.string.welcome_gemma_enable_desc),
                    selected = wantsGemma,
                    onClick = { onWantsGemmaChange(true) },
                )
                StepChoiceCard(
                    title = stringResource(SetupR.string.welcome_gemma_disable),
                    body = stringResource(SetupR.string.welcome_gemma_disable_desc),
                    selected = !wantsGemma,
                    onClick = { onWantsGemmaChange(false) },
                )
            }
            else -> {
                StepChoiceCard(
                    title = stringResource(SetupR.string.welcome_ai_add_key),
                    body = stringResource(SetupR.string.welcome_ai_add_key_body),
                    selected = wantsApiKey,
                    onClick = { onWantsApiKeyChange(true) },
                )
                StepChoiceCard(
                    title = stringResource(SetupR.string.welcome_ai_skip_key),
                    body = stringResource(SetupR.string.welcome_ai_skip_key_body),
                    selected = !wantsApiKey,
                    onClick = { onWantsApiKeyChange(false) },
                )

                if (wantsApiKey) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = apiKeyFieldValue,
                                onValueChange = {
                                    apiKeyFieldValue = it
                                    onCloudApiKeyChange(it.text)
                                },
                                modifier = Modifier.fillMaxWidth().contentType(ContentType.Password),
                                label = { Text(stringResource(SettingsR.string.voice_cloud_api_key)) },
                                keyboardOptions =
                                    androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = KeyboardType.Password,
                                        imeAction = ImeAction.Done,
                                    ),
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = {
                                        val clip = clipboardManager.getText()?.text.orEmpty()
                                        apiKeyFieldValue =
                                            TextFieldValue(
                                                text = clip,
                                                selection = TextRange(clip.length),
                                            )
                                        onCloudApiKeyChange(clip)
                                    },
                                ) {
                                    Text(stringResource(CoreR.string.paste))
                                }
                                TextButton(
                                    onClick = {
                                        apiKeyFieldValue = TextFieldValue("")
                                        onCloudApiKeyChange("")
                                    },
                                ) {
                                    Text(stringResource(CoreR.string.clear))
                                }
                            }
                            OutlinedButton(
                                onClick = onOpenAiStudioClick,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                            ) {
                                Text(stringResource(SettingsR.string.voice_cloud_api_key_get_one))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepChoiceCard(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }

    Surface(
        modifier =
            Modifier.fillMaxWidth().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        border = BorderStroke(2.dp, borderColor),
        tonalElevation = if (selected) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(18.dp),
                shape = CircleShape,
                color =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, borderColor),
            ) {}
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepProgress(
    currentStepIndex: Int,
    totalSteps: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(totalSteps) { index ->
            val active = index <= currentStepIndex
            Surface(
                modifier = Modifier.weight(1f).height(8.dp),
                shape = MaterialTheme.shapes.small,
                color =
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

@PreviewScreenSizes
@Composable
private fun WelcomeScreenLayoutPreview() {
    SpatialFinTheme {
        WelcomeScreenLayout(
            currentStep = WelcomeStep.Connection,
            currentStepIndex = 0,
            totalSteps = 3,
            connectToServer = true,
            onConnectToServerChange = {},
            companionState = CompanionState.Idle,
            onStartScanning = {},
            onPayloadFound = {},
            smartLanguageSettings = SmartLanguageSettings(spokenLanguageCodes = listOf("en", "es")),
            onPreferOriginalAudioChange = {},
            onEditLanguages = {},
            aiStatus = null,
            aiStatusLoading = false,
            wantsGemma = true,
        onWantsGemmaChange = {},
        wantsApiKey = false,
            onWantsApiKeyChange = {},
            cloudApiKey = "",
            onCloudApiKeyChange = {},
            onLearnMoreClick = {},
            onOpenAiStudioClick = {},
            onBackClick = {},
            onSkipStepClick = {},
            onSkipSetupClick = {},
            onNextClick = {},
        )
    }
}
