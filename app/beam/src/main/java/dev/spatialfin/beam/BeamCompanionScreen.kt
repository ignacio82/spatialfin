package dev.spatialfin.beam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.NetworkShareDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionDiscoveryPayload
import dev.jdtech.jellyfin.models.companion.CompanionNetworkShare
import dev.jdtech.jellyfin.models.companion.CompanionUser
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.CompanionSyncWorker
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import timber.log.Timber

sealed class BeamCompanionState {
    data object Idle : BeamCompanionState()
    data object Scanning : BeamCompanionState()
    data object Fetching : BeamCompanionState()
    data object Success : BeamCompanionState()
    data class Error(val message: String) : BeamCompanionState()
}

private data class ImportedSession(
    val serverId: String,
    val baseUrl: String,
    val userId: UUID,
    val accessToken: String?,
)

@HiltViewModel
class BeamCompanionViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val serverDatabase: ServerDatabaseDao,
    private val jellyfinApi: JellyfinApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow<BeamCompanionState>(BeamCompanionState.Idle)
    val state: StateFlow<BeamCompanionState> = _state

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()

    fun startScanning() {
        _state.value = BeamCompanionState.Scanning
    }

    fun reset() {
        _state.value = BeamCompanionState.Idle
    }

    fun syncNow() {
        val url = appPreferences.getValue(appPreferences.companionUrl)
        val token = appPreferences.getValue(appPreferences.companionToken)
        if (url.isNotEmpty() && token.isNotEmpty()) {
            fetchAndApplyConfig(
                CompanionDiscoveryPayload(version = 1, companion_url = url, setup_token = token),
            )
        } else {
            _state.value = BeamCompanionState.Error("No saved companion connection found yet.")
        }
    }

    fun fetchAndApplyConfig(payload: CompanionDiscoveryPayload) {
        if (_state.value is BeamCompanionState.Fetching) return

        appPreferences.setValue(appPreferences.companionUrl, payload.companion_url)
        appPreferences.setValue(appPreferences.companionToken, payload.setup_token)

        viewModelScope.launch {
            _state.value = BeamCompanionState.Fetching
            try {
                val config = fetchConfig(payload)
                applyConfig(config)
                appPreferences.setValue(appPreferences.lastCompanionSyncTime, System.currentTimeMillis())
                _state.value = BeamCompanionState.Success
            } catch (e: Exception) {
                Timber.e(e, "COMPANION: Failed to fetch or apply config")
                val message =
                    when (e) {
                        is SocketTimeoutException -> "Connection timed out. Make sure Beam Pro and the companion app are on the same Wi-Fi."
                        is ConnectException -> "Connection refused. Check that the companion app is running and reachable."
                        else -> e.message ?: "Unknown error"
                    }
                _state.value = BeamCompanionState.Error(message)
            }
        }
    }

    private suspend fun fetchConfig(payload: CompanionDiscoveryPayload): CompanionConfig =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("${payload.companion_url}/api/v1/config")
                    .addHeader("X-Setup-Token", payload.setup_token)
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body?.string() ?: throw Exception("Empty response")
                json.decodeFromString(body)
            }
        }

    private suspend fun applyConfig(config: CompanionConfig) =
        withContext(Dispatchers.IO) {
            applyPreferences(config.preferences)

            val importedSessions = mutableListOf<ImportedSession>()
            val previousCurrentServer = appPreferences.getValue(appPreferences.currentServer)

            config.servers.forEach { serverConfig ->
                val serverId = serverConfig.id
                val rawAddress = serverConfig.addresses.firstOrNull().orEmpty()
                val baseUrl =
                    if (rawAddress.isNotEmpty() && !rawAddress.endsWith("/")) "$rawAddress/" else rawAddress
                var serverSession: ImportedSession? = null
                val validUsers = mutableListOf<User>()

                serverConfig.users.forEach { companionUser ->
                    runCatching {
                        authenticateOrUseToken(companionUser, baseUrl, serverId)
                    }.getOrNull()?.let { user ->
                        validUsers.add(user)
                        if (serverSession == null) {
                            serverSession =
                                ImportedSession(
                                    serverId = serverId,
                                    baseUrl = baseUrl,
                                    userId = user.id,
                                    accessToken = user.accessToken,
                                )
                        }
                    }
                }

                if (validUsers.isNotEmpty()) {
                    val server =
                        Server(
                            id = serverId,
                            name = serverConfig.name,
                            currentServerAddressId = null,
                            currentUserId = serverSession?.userId,
                        )
                    serverDatabase.insertServer(server)
                    val addressId = UUID.randomUUID()
                    serverDatabase.insertServerAddress(
                        ServerAddress(
                            id = addressId,
                            serverId = serverId,
                            address = baseUrl,
                        ),
                    )
                    server.currentServerAddressId = addressId
                    serverDatabase.update(server)
                    validUsers.forEach(serverDatabase::insertUser)
                    serverSession?.let(importedSessions::add)
                }
            }

            applyNetworkShares(config.networkShares)

            val activeSession =
                importedSessions.firstOrNull { it.serverId == previousCurrentServer }
                    ?: importedSessions.firstOrNull()

            activeSession?.let { session ->
                appPreferences.setValue(appPreferences.currentServer, session.serverId)
                appPreferences.setValue(appPreferences.onboardingCompleted, true)
                jellyfinApi.api.update(baseUrl = session.baseUrl, accessToken = session.accessToken)
                jellyfinApi.userId = session.userId
                scheduleCompanionSync()
            }

            config.servers
                .flatMap { it.users }
                .firstOrNull()
                ?.preferences
                ?.filterValues { it != null }
                ?.mapValues { it.value!! }
                ?.takeIf { it.isNotEmpty() }
                ?.let(::applyPreferences)
        }

    private suspend fun authenticateOrUseToken(
        user: CompanionUser,
        baseUrl: String,
        serverId: String,
    ): User? {
        if (user.accessToken != null) {
            runCatching {
                jellyfinApi.api.update(baseUrl = baseUrl, accessToken = user.accessToken)
                val userInfo = jellyfinApi.userApi.getCurrentUser().content
                return User(
                    id = userInfo.id,
                    name = user.username,
                    serverId = serverId,
                    accessToken = user.accessToken,
                    preferences = serializeUserPrefs(user.preferences),
                )
            }
        }

        if (user.password != null) {
            jellyfinApi.api.update(baseUrl = baseUrl, accessToken = null)
            val authResult =
                jellyfinApi.userApi.authenticateUserByName(
                    data = AuthenticateUserByName(username = user.username, pw = user.password),
                ).content

            return User(
                id = authResult.user?.id ?: UUID.randomUUID(),
                name = user.username,
                serverId = serverId,
                accessToken = authResult.accessToken,
                preferences = serializeUserPrefs(user.preferences),
            )
        }

        return null
    }

    private fun serializeUserPrefs(prefs: Map<String, String?>): String? {
        val nonNull = prefs.filterValues { it != null }.mapValues { it.value!! }
        return if (nonNull.isNotEmpty()) json.encodeToString(nonNull) else null
    }

    private fun scheduleCompanionSync() {
        val request =
            PeriodicWorkRequestBuilder<CompanionSyncWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "companion_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun applyPreferences(preferences: Map<String, String?>) {
        preferences.forEach { (key, value) ->
            try {
                if (value == null) return@forEach
                when (key) {
                    appPreferences.preferredAudioLanguage.backendName -> appPreferences.setValue(appPreferences.preferredAudioLanguage, value)
                    appPreferences.preferredSubtitleLanguage.backendName -> appPreferences.setValue(appPreferences.preferredSubtitleLanguage, value)
                    appPreferences.animeAudioLanguage.backendName -> appPreferences.setValue(appPreferences.animeAudioLanguage, value)
                    appPreferences.animeSubtitleLanguage.backendName -> appPreferences.setValue(appPreferences.animeSubtitleLanguage, value)
                    appPreferences.nonAnimeAudioLanguage.backendName -> appPreferences.setValue(appPreferences.nonAnimeAudioLanguage, value)
                    appPreferences.nonAnimeSubtitleLanguage.backendName -> appPreferences.setValue(appPreferences.nonAnimeSubtitleLanguage, value)
                    appPreferences.nonAnimeSubtitleDisabled.backendName -> appPreferences.setValue(appPreferences.nonAnimeSubtitleDisabled, value.toBoolean())
                    appPreferences.smartPreferOriginalAudio.backendName -> appPreferences.setValue(appPreferences.smartPreferOriginalAudio, value.toBoolean())
                    appPreferences.smartSpokenLanguages.backendName -> appPreferences.setValue(appPreferences.smartSpokenLanguages, value)
                    appPreferences.voiceControlEnabled.backendName -> appPreferences.setValue(appPreferences.voiceControlEnabled, value.toBoolean())
                    appPreferences.voiceGestureHand.backendName -> appPreferences.setValue(appPreferences.voiceGestureHand, value)
                    appPreferences.voiceAssistantVerbosity.backendName -> appPreferences.setValue(appPreferences.voiceAssistantVerbosity, value)
                    appPreferences.voiceAssistantSpoilerPolicy.backendName -> appPreferences.setValue(appPreferences.voiceAssistantSpoilerPolicy, value)
                    appPreferences.voiceAssistantSpokenReplies.backendName -> appPreferences.setValue(appPreferences.voiceAssistantSpokenReplies, value.toBoolean())
                    appPreferences.voiceAssistantVoice.backendName -> appPreferences.setValue(appPreferences.voiceAssistantVoice, value)
                    appPreferences.voiceAssistantCloudApiKey.backendName -> appPreferences.setValue(appPreferences.voiceAssistantCloudApiKey, value)
                    appPreferences.tmdbApiKey.backendName -> appPreferences.setValue(appPreferences.tmdbApiKey, value)
                    appPreferences.tmdbAutoMatch.backendName -> appPreferences.setValue(appPreferences.tmdbAutoMatch, value.toBoolean())
                    appPreferences.seerrEnabled.backendName -> appPreferences.setValue(appPreferences.seerrEnabled, value.toBoolean())
                    appPreferences.seerrUrl.backendName -> appPreferences.setValue(appPreferences.seerrUrl, value)
                    appPreferences.seerrApiKey.backendName -> appPreferences.setValue(appPreferences.seerrApiKey, value)
                    else -> Unit
                }
            } catch (e: Exception) {
                Timber.w(e, "COMPANION: Failed to apply preference $key")
            }
        }
    }

    private fun applyNetworkShares(networkShares: List<CompanionNetworkShare>) {
        networkShares.forEach { share ->
            serverDatabase.insertNetworkShare(
                NetworkShareDto(
                    id = share.id,
                    protocol = share.protocol,
                    host = share.host,
                    shareName = share.shareName,
                    path = share.path ?: "",
                    displayName = share.displayName,
                    username = share.username,
                    password = share.password,
                    domain = share.domain,
                    addedAtEpochMs = share.addedAtEpochMs ?: System.currentTimeMillis(),
                    lastScannedAtEpochMs = null,
                ),
            )
        }
    }
}

@Composable
fun BeamCompanionScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: BeamCompanionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BeamScaffoldBody(contentPadding = contentPadding) {
        item {
            BeamScreenHeader(
                title = "Companion Import",
                body = "Scan the SpatialFin companion QR code on your phone to import your server, user, preferences, and companion sync settings.",
            )
        }

        when (val current = state) {
            BeamCompanionState.Idle -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Use the same companion setup flow as the XR build. Camera access is optional and only needed for QR scanning.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = viewModel::startScanning) {
                                    Text("Scan QR")
                                }
                                OutlinedButton(onClick = viewModel::syncNow) {
                                    Text("Use Saved Companion")
                                }
                                OutlinedButton(onClick = onBack) {
                                    Text("Back")
                                }
                            }
                        }
                    }
                }
            }
            BeamCompanionState.Scanning -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Scan the QR code from the companion app.", style = MaterialTheme.typography.bodyLarge)
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(360.dp)
                                        .padding(top = 8.dp),
                            ) {
                                BeamCompanionScanner(
                                    onPayloadFound = { payload ->
                                        viewModel.fetchAndApplyConfig(payload)
                                    },
                                )
                            }
                            OutlinedButton(onClick = onBack) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
            BeamCompanionState.Fetching -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text("Fetching and applying companion config...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            BeamCompanionState.Success -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Companion import complete.", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Your Beam app now has the imported server, user, and sync configuration.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onSuccess) {
                                    Text("Continue")
                                }
                                OutlinedButton(onClick = viewModel::startScanning) {
                                    Text("Re-scan")
                                }
                            }
                        }
                    }
                }
            }
            is BeamCompanionState.Error -> {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Companion import failed.", style = MaterialTheme.typography.titleLarge)
                            Text(current.message, color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = viewModel::startScanning) {
                                    Text("Try Again")
                                }
                                OutlinedButton(onClick = viewModel::reset) {
                                    Text("Back")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeamCompanionScanner(
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val json = remember { Json { ignoreUnknownKeys = true } }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { localContext ->
                    val previewView = PreviewView(localContext)
                    val executor = ContextCompat.getMainExecutor(localContext)
                    cameraProviderFuture.addListener(
                        {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview =
                                Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                            val resolutionSelector =
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(1920, 1080),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER,
                                        ),
                                    ).build()

                            val imageAnalysis =
                                ImageAnalysis.Builder()
                                    .setResolutionSelector(resolutionSelector)
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                            val options =
                                BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build()
                            val scanner = BarcodeScanning.getClient(options)
                            val analysisExecutor = Executors.newSingleThreadExecutor()
                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                processCompanionImageProxy(scanner, imageProxy, onPayloadFound, json)
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis,
                            )
                        },
                        executor,
                    )
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is required to scan the companion QR code.")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processCompanionImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onPayloadFound: (CompanionDiscoveryPayload) -> Unit,
    json: Json,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.forEach { barcode ->
                val rawValue = barcode.rawValue ?: return@forEach
                runCatching {
                    if (rawValue.startsWith("sfcp:")) {
                        val data = rawValue.substring(5).split("|")
                        if (data.size == 2) {
                            CompanionDiscoveryPayload(
                                version = 1,
                                companion_url = data[0],
                                setup_token = data[1],
                            )
                        } else {
                            null
                        }
                    } else {
                        json.decodeFromString<CompanionDiscoveryPayload>(rawValue)
                    }
                }.getOrNull()?.let(onPayloadFound)
            }
        }.addOnFailureListener {
            Timber.e(it, "COMPANION: Barcode scanning failed")
        }.addOnCompleteListener {
            imageProxy.close()
        }
}
