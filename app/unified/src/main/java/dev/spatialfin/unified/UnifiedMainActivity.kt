package dev.spatialfin.unified

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height as uiHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width as uiWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.OrbiterDefaults
import androidx.xr.compose.spatial.SpatialDialog
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import dev.jdtech.jellyfin.core.util.XrPlayerSessionClient
import dev.jdtech.jellyfin.core.util.localVideoPermissions
import dev.jdtech.jellyfin.deeplink.MaDeepLink
import dev.jdtech.jellyfin.deeplink.PlayDeepLink
import dev.jdtech.jellyfin.models.LocalVideoItem
import dev.jdtech.jellyfin.models.NetworkVideoItem
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinSeason
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.player.beam.BeamPlayerActivity
import dev.jdtech.jellyfin.player.core.VideoUriHelper
import dev.jdtech.jellyfin.player.session.voice.ActivePlayerSessionHolder
import dev.jdtech.jellyfin.player.tv.TvPlayerActivity
import dev.jdtech.jellyfin.player.xr.XrPlayerActivity
import dev.jdtech.jellyfin.player.xr.voice.RecommendationContext
import dev.jdtech.jellyfin.player.xr.voice.SecondaryHandPinchDetector
import dev.jdtech.jellyfin.player.xr.voice.VoiceControlOverlay
import dev.jdtech.jellyfin.player.xr.voice.VoiceState
import dev.jdtech.jellyfin.plugins.model.UniversalSpatialFinItem
import dev.jdtech.jellyfin.presentation.utils.LocalOfflineMode
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionNavigationPolicy
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import dev.jdtech.jellyfin.setup.lan.LanSetupController
import dev.jdtech.jellyfin.viewmodels.MainState
import dev.jdtech.jellyfin.viewmodels.MainViewModel
import dev.jdtech.jellyfin.viewmodels.PendingViewVideo
import dev.jdtech.jellyfin.work.SyncWorker
import dev.spatialfin.EpisodeRoute
import dev.spatialfin.LocalVideoRoute
import dev.spatialfin.MediaRoute
import dev.spatialfin.MovieRoute
import dev.spatialfin.NavigationRoot
import dev.spatialfin.NetworkVideoRoute
import dev.spatialfin.SeasonRoute
import dev.spatialfin.ShowRoute
import dev.spatialfin.XrNavigationObserverState
import dev.spatialfin.beam.BeamNavigationRoot
import dev.spatialfin.beam.BeamTheme
import dev.spatialfin.buildXrNavGraph
import dev.spatialfin.navigateHome
import dev.spatialfin.presentation.theme.SpatialFinTheme
import dev.spatialfin.rememberXrNavigationObserverState
import dev.spatialfin.tv.TvNavigationRoot
import dev.spatialfin.tv.TvTheme
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockScreen
import dev.spatialfin.unified.applock.LocalSystemModalGraceHook
import dev.spatialfin.unified.applock.SystemModalGraceHook
import dev.spatialfin.xrInitialStartRoute
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class UnifiedMainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_INITIAL_SEARCH_QUERY = "initial_search_query"
        private const val HAND_TRACKING_PERMISSION = "android.permission.HAND_TRACKING"
        private const val PERMISSIONS_PREFS = "startup_permissions"
        private const val STARTUP_PERMISSIONS_REQUESTED_KEY = "startup_permissions_requested"
        private const val XR_APP_PANEL_WIDTH_DP = 1400
        private const val XR_APP_PANEL_HEIGHT_DP = 824
        private const val VIEW_VIDEO_INTENT_TTL_MS = 5L * 60L * 1000L
        private const val KEY_VIEW_VIDEO_INTENT_CONSUMED = "view_video_intent_consumed"
        private const val KEY_MA_DEEP_LINK_CONSUMED = "ma_deep_link_consumed"
        private const val KEY_SEARCH_INTENT_CONSUMED = "search_intent_consumed"
    }

    private val deviceClass by lazy { detectDeviceClass() }
    private val capabilities by lazy { DeviceClassCapabilities(deviceClass) }
    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var modelManager: Lazy<LlmModelManager>

    @Inject
    lateinit var repository: JellyfinRepository

    @Inject
    lateinit var voiceTelemetryStore: VoiceTelemetryStore

    @Inject
    lateinit var musicAssistantRepository: dev.jdtech.jellyfin.data.musicassistant.repository.MusicAssistantRepository

    @Inject
    lateinit var maSession: dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository

    @Inject
    lateinit var maServiceClient: dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var systemModalGraceHook: SystemModalGraceHook

    @Inject
    lateinit var sessionActivationCoordinator: SessionActivationCoordinator

    @Inject
    lateinit var lanSetupController: LanSetupController

    @Inject
    lateinit var fcastSession: dev.spatialfin.fcast.session.CastSessionManager

    @Inject
    lateinit var jellyfinAudioDispatcher: dev.spatialfin.unified.audio.JellyfinAudioPlayDispatcher

    @Inject
    lateinit var wearSessionBus: dev.jdtech.jellyfin.session.ActiveSessionBus

    private val llmModelManager: LlmModelManager by lazy(LazyThreadSafetyMode.NONE) {
        modelManager.get()
    }

    private val wearIdleLauncher: suspend (String, String?, Long) -> String = { id, _, position ->
        dev.spatialfin.launchWearLibraryItem(this, repository, deviceClass, id, position) {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                appLockManager.lockState.value != AppLockManager.LockState.LOCKED &&
                sessionActivationCoordinator.sessionState.value.sessionMode == SessionMode.SERVER_AUTHENTICATED
        }
    }

    private val xrSessionState = mutableStateOf<Session?>(null)
    private var startupPermissionModalActive: Boolean = false
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (startupPermissionModalActive) {
                startupPermissionModalActive = false
                systemModalGraceHook.setSystemModalActive(false)
            }
        }

    // Listens for external-display hot-plug (Beam Pro ↔ Xreal glasses).
    private var displayListener: DisplayManager.DisplayListener? = null

    private var viewVideoIntentConsumed: Boolean = false
    private var maDeepLinkConsumed: Boolean = false
    private var searchIntentConsumed: Boolean = false

    private val pendingSearchQuery = MutableStateFlow<String?>(null)
    private val pendingMaActionDeepLink = MutableStateFlow<MaDeepLink.Parsed?>(null)
    private val maUiDeepLink = MutableStateFlow<MaDeepLink.Parsed?>(null)

    override fun startActivity(intent: Intent?, options: Bundle?) {
        XrPlayerSessionClient.notifyPendingLaunch(intent)
        super.startActivity(intent, options)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_VIEW_VIDEO_INTENT_CONSUMED, viewVideoIntentConsumed)
        outState.putBoolean(KEY_MA_DEEP_LINK_CONSUMED, maDeepLinkConsumed)
        outState.putBoolean(KEY_SEARCH_INTENT_CONSUMED, searchIntentConsumed)
    }

    private fun ingestSearchIntent(incoming: Intent?, fromOnCreate: Boolean) {
        if (incoming == null) return
        if (fromOnCreate && searchIntentConsumed) return
        val actionSearchQuery =
            if (Intent.ACTION_SEARCH == incoming.action) {
                incoming.getStringExtra(SearchManager.QUERY)
            } else {
                null
            }
        val query = incoming.getStringExtra(EXTRA_INITIAL_SEARCH_QUERY) ?: actionSearchQuery
        if (!query.isNullOrBlank()) {
            pendingSearchQuery.value = query
        }
    }

    private fun clearPendingSearchQuery() {
        pendingSearchQuery.value = null
        searchIntentConsumed = true
        intent?.let { current ->
            if (Intent.ACTION_SEARCH == current.action) {
                current.action = null
            }
            current.removeExtra(EXTRA_INITIAL_SEARCH_QUERY)
            current.removeExtra(SearchManager.QUERY)
        }
    }

    private fun ingestMaDeepLink(incoming: Intent?, fromOnCreate: Boolean) {
        if (incoming == null) return
        if (fromOnCreate && maDeepLinkConsumed) return
        when (val parsed = MaDeepLink.parse(incoming.data)) {
            is MaDeepLink.Parsed.Play,
            MaDeepLink.Parsed.Resume,
            -> pendingMaActionDeepLink.value = parsed
            MaDeepLink.Parsed.Queue,
            MaDeepLink.Parsed.Party,
            -> maUiDeepLink.value = parsed
            null -> Unit
        }
    }

    private fun clearConsumedMaIntent() {
        maDeepLinkConsumed = true
        intent?.let { current ->
            if (MaDeepLink.parse(current.data) != null) {
                current.action = null
                current.data = null
            }
        }
    }

    private fun ingestIncomingViewOrPlayIntent(
        incoming: Intent?,
        finishAfterLaunch: Boolean,
    ): Boolean {
        if (incoming == null) return false
        val isPlayDeepLink =
            incoming.action == Intent.ACTION_VIEW && PlayDeepLink.parse(incoming.data) != null
        val isVideoIntent = !isPlayDeepLink && ViewVideoPolicy.shouldHandleIntent(this, incoming)
        if (!isPlayDeepLink && !isVideoIntent) return false
        if (finishAfterLaunch && viewVideoIntentConsumed) return false

        val locked =
            appLockManager.isEnabled() &&
                appLockManager.lockState.value == AppLockManager.LockState.LOCKED

        if (locked || isPlayDeepLink) {
            val buffered =
                Intent(incoming).apply {
                    clipData = incoming.clipData
                    if (incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            viewModel.pendingViewVideoIntent.value =
                PendingViewVideo(
                    intent = buffered,
                    finishAfterLaunch = finishAfterLaunch,
                    capturedElapsedMs = SystemClock.elapsedRealtime(),
                )
            return false
        }

        val launched = launchViewVideoIntent(incoming)
        viewVideoIntentConsumed = true
        clearConsumedViewIntent(incoming)
        return launched
    }

    private fun clearConsumedViewIntent(source: Intent?) {
        source?.let {
            it.action = null
            it.data = null
            it.clipData = null
        }
        intent?.let { current ->
            if (
                current.action == Intent.ACTION_VIEW &&
                    (PlayDeepLink.parse(current.data) != null ||
                        ViewVideoPolicy.shouldHandleIntent(this, current))
            ) {
                current.action = null
                current.data = null
                current.clipData = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appLockManager.refreshState()
        val isPlayOrVideo =
            (intent.action == Intent.ACTION_VIEW && PlayDeepLink.parse(intent.data) != null) ||
                ViewVideoPolicy.shouldHandleIntent(this, intent)
        if (!isPlayOrVideo && (intent.action == Intent.ACTION_MAIN || intent.action == Intent.ACTION_SEARCH)) {
            viewModel.pendingViewVideoIntent.value = null
        }
        ingestSearchIntent(intent, fromOnCreate = false)
        ingestMaDeepLink(intent, fromOnCreate = false)
        ingestIncomingViewOrPlayIntent(intent, finishAfterLaunch = false)
    }

    private fun launchViewVideoIntent(incoming: Intent?): Boolean {
        if (!ViewVideoPolicy.shouldHandleIntent(this, incoming)) return false
        val uri = incoming?.data ?: return false

        val displayName = VideoUriHelper.resolveDisplayName(this, uri)
        val stereoMode = ViewVideoPolicy.resolveStereoMode(displayName)
        val projection = ViewVideoPolicy.resolveProjection(displayName)

        Timber.i(
            "launchViewVideoIntent: uri=%s title=%s stereo=%s projection=%s deviceClass=%s",
            uri,
            displayName,
            stereoMode,
            projection,
            deviceClass,
        )

        val playerIntent =
            when (deviceClass) {
                DeviceClass.XR ->
                    XrPlayerActivity.createIntentForUri(
                        context = this,
                        uri = uri,
                        title = displayName,
                        stereoMode = stereoMode,
                        projection = projection,
                    )
                DeviceClass.TV ->
                    TvPlayerActivity.createIntentForUri(
                        context = this,
                        uri = uri,
                        title = displayName,
                    )
                DeviceClass.PHONE ->
                    BeamPlayerActivity.createIntentForUri(
                        context = this,
                        uri = uri,
                        title = displayName,
                    )
            }

        playerIntent.clipData = incoming.clipData ?: ClipData.newRawUri("video", uri)
        playerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (deviceClass == DeviceClass.XR) {
            runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { error ->
                Timber.w(error, "grantUriPermission failed for :xrplayer launch of %s", uri)
            }
        }
        startActivity(playerIntent)
        return true
    }

    private fun launchPlayDeepLinkIntent(incoming: Intent, sessionMode: SessionMode): Boolean {
        val uri = incoming.data ?: return false
        if (PlayDeepLink.parse(uri) == null) return false
        if (deviceClass != DeviceClass.TV || sessionMode != SessionMode.SERVER_AUTHENTICATED) {
            Timber.i(
                "Ignoring spatialfin://play deep link (deviceClass=%s, sessionMode=%s)",
                deviceClass,
                sessionMode,
            )
            return false
        }
        val tvIntent =
            Intent(this, TvPlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, null)
            }
        startActivity(tvIntent)
        return true
    }

    private fun currentMaVoiceState(): MaVoiceState? {
        if (!::maSession.isInitialized) return null
        val s = maSession.session.value
        val nowPlaying = s.nowPlaying ?: return null
        if (s.selectedPlayer == null) return null
        return MaVoiceState(
            active = true,
            isPlaying =
                s.playbackPhase ==
                    dev.jdtech.jellyfin.data.musicassistant.repository.PlaybackPhase.Playing,
            track = nowPlaying.title,
            artist = nowPlaying.artist,
            album = null,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewVideoIntentConsumed =
            savedInstanceState?.getBoolean(KEY_VIEW_VIDEO_INTENT_CONSUMED, false) ?: false
        maDeepLinkConsumed =
            savedInstanceState?.getBoolean(KEY_MA_DEEP_LINK_CONSUMED, false) ?: false
        searchIntentConsumed =
            savedInstanceState?.getBoolean(KEY_SEARCH_INTENT_CONSUMED, false) ?: false

        // Refresh lock state BEFORE evaluating incoming ACTION_VIEW / PlayDeepLink intents.
        appLockManager.refreshState()

        if (ingestIncomingViewOrPlayIntent(intent, finishAfterLaunch = true)) {
            finish()
            return
        }

        enableEdgeToEdge()

        if (capabilities.isPhone) {
            applyImmersiveSystemBars()
        }

        when (deviceClass) {
            DeviceClass.TV ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DeviceClass.PHONE -> {
                applyPhoneOrientation()
                val dm = getSystemService(DisplayManager::class.java)
                if (dm != null) {
                    val listener =
                        object : DisplayManager.DisplayListener {
                            override fun onDisplayAdded(displayId: Int) = applyPhoneOrientation()
                            override fun onDisplayRemoved(displayId: Int) = applyPhoneOrientation()
                            override fun onDisplayChanged(displayId: Int) = Unit
                        }
                    dm.registerDisplayListener(listener, null)
                    displayListener = listener
                }
            }
            DeviceClass.XR -> Unit
        }

        if (capabilities.isXr) {
            window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            try {
                val result = Session.create(this)
                if (result is SessionCreateSuccess) {
                    xrSessionState.value = result.session
                }
            } catch (e: Exception) {
                Timber.w(e, "XR session not available")
            }
        }

        ingestSearchIntent(intent, fromOnCreate = true)
        ingestMaDeepLink(intent, fromOnCreate = true)

        // Scope Wear browsing and idle remote media launcher strictly to RESUMED + unlocked + authenticated.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ActivePlayerSessionHolder.idleMediaLauncher = wearIdleLauncher
                try {
                    val sessionKeyFlow =
                        combine(
                            appLockManager.lockState.map { it == AppLockManager.LockState.LOCKED },
                            sessionActivationCoordinator.sessionState.map {
                                Pair(it.sessionMode, it.connectionEpoch)
                            },
                        ) { locked, modeAndEpoch ->
                            Triple(locked, modeAndEpoch.first, modeAndEpoch.second)
                        }.distinctUntilChanged()

                    combine(
                        sessionKeyFlow,
                        merge(flowOf(Unit), wearSessionBus.events),
                    ) { key, _ -> key }
                        .collectLatest { (locked, sessionMode, _) ->
                            if (locked || sessionMode != SessionMode.SERVER_AUTHENTICATED) {
                                return@collectLatest
                            }
                            runCatching { repository.postCapabilities() }
                            repository.observeGeneralCommandMessages().collect { message ->
                                if (
                                    message.data?.name !=
                                        org.jellyfin.sdk.model.api.GeneralCommandType.PLAY_MEDIA_SOURCE
                                ) {
                                    return@collect
                                }
                                if (ActivePlayerSessionHolder.activeSession.value != null) {
                                    return@collect
                                }
                                val args = message.data?.arguments.orEmpty()
                                val id =
                                    args.entries
                                        .firstOrNull { it.key.equals("ItemId", true) }
                                        ?.value ?: return@collect
                                val position =
                                    args.entries
                                        .firstOrNull { it.key.equals("StartPositionTicks", true) }
                                        ?.value
                                        ?.toLongOrNull()
                                        ?.div(10_000) ?: 0L
                                try {
                                    wearIdleLauncher(id, null, position)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.widget.Toast.makeText(
                                        this@UnifiedMainActivity,
                                        error.message ?: "Remote playback failed",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                } finally {
                    if (ActivePlayerSessionHolder.idleMediaLauncher === wearIdleLauncher) {
                        ActivePlayerSessionHolder.idleMediaLauncher = null
                    }
                }
            }
        }

        setContent {
            CompositionLocalProvider(LocalSystemModalGraceHook provides systemModalGraceHook) {
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    (application as UnifiedApplication).startDeferredInitialization()
                }

                // Hoisted above AppLock so shell state, XR NavGraph, and space state survive LOCKED <-> UNLOCKED.
                val lockState by appLockManager.lockState.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val shellStateHolder = rememberSaveableStateHolder()

                val xrNavController = if (deviceClass == DeviceClass.XR) rememberNavController() else null
                val xrObserverState = if (deviceClass == DeviceClass.XR) rememberXrNavigationObserverState() else null
                var xrNavGraph by remember(xrNavController) { mutableStateOf<NavGraph?>(null) }
                if (xrNavController != null && xrObserverState != null) {
                    LaunchedEffect(state.isLoading, xrNavController) {
                        if (!state.isLoading && xrNavGraph == null) {
                            xrNavGraph =
                                buildXrNavGraph(
                                    navController = xrNavController,
                                    startDestination =
                                        xrInitialStartRoute(
                                            state = state,
                                            offlineBrowseEntered = xrObserverState.offlineBrowseEntered,
                                        ),
                                    appPreferences = appPreferences,
                                )
                        }
                    }
                }

                val xrSession = if (deviceClass == DeviceClass.XR) xrSessionState.value else null
                val spaceController =
                    xrSession?.let { remember(it) { XrSpaceController(it, appPreferences) } }
                val spaceUiState = spaceController?.uiState?.collectAsState()?.value
                var launchPreferenceApplied by remember(spaceController) { mutableStateOf(false) }
                LaunchedEffect(spaceController, lockState) {
                    if (
                        spaceController != null &&
                            !launchPreferenceApplied &&
                            lockState != AppLockManager.LockState.LOCKED
                    ) {
                        launchPreferenceApplied = true
                        spaceController.applyLaunchPreference(isCasting = { fcastSession.hasCastIntent() })
                    }
                }
                var renderedSpaceMode by remember(spaceController) { mutableStateOf<XrSpaceMode?>(null) }

                // Exclusive LOCKED surface: unmount all shells, voice controllers, and media panels.
                if (lockState == AppLockManager.LockState.LOCKED) {
                    if (
                        deviceClass == DeviceClass.XR &&
                            spaceUiState?.mode == XrSpaceMode.FULL &&
                            spaceUiState.spatialUiAvailable
                    ) {
                        val savedAppPanelScale = remember {
                            appPreferences.getValue(appPreferences.xrAppPanelScale).coerceIn(0.5f, 3.0f)
                        }
                        SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                            Subspace {
                                SpatialPanel(
                                    modifier =
                                        SubspaceModifier
                                            .width((XR_APP_PANEL_WIDTH_DP * savedAppPanelScale).dp)
                                            .height((XR_APP_PANEL_HEIGHT_DP * savedAppPanelScale).dp)
                                ) {
                                    AppLockScreen(lockManager = appLockManager)
                                }
                            }
                        }
                    } else {
                        SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                            AppLockScreen(lockManager = appLockManager)
                        }
                    }
                    return@CompositionLocalProvider
                }

                // Strictly below LOCKED gate: startup permissions (XR only, post-onboarding), sync, and intent consumers.
                LaunchedEffect(capabilities.isXr, state.isLoading, state.sessionMode) {
                    if (
                        capabilities.isXr &&
                            !state.isLoading &&
                            state.sessionMode != SessionMode.UNCONFIGURED
                    ) {
                        requestStartupPermissionsIfNeeded()
                    }
                }

                LaunchedEffect(state.isLoading, state.sessionMode) {
                    if (!state.isLoading && state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
                        scheduleUserDataSync()
                    }
                }

                val pendingViewVideo by viewModel.pendingViewVideoIntent.collectAsStateWithLifecycle()
                LaunchedEffect(state.isLoading, state.sessionMode, pendingViewVideo) {
                    val pending = pendingViewVideo ?: return@LaunchedEffect
                    val isPlayDeepLink = PlayDeepLink.parse(pending.intent.data) != null
                    if (isPlayDeepLink && state.isLoading) return@LaunchedEffect

                    viewModel.pendingViewVideoIntent.value = null
                    viewVideoIntentConsumed = true

                    val ageMs = SystemClock.elapsedRealtime() - pending.capturedElapsedMs
                    if (ageMs > VIEW_VIDEO_INTENT_TTL_MS) {
                        clearConsumedViewIntent(pending.intent)
                        return@LaunchedEffect
                    }

                    val launched =
                        if (isPlayDeepLink) {
                            launchPlayDeepLinkIntent(pending.intent, state.sessionMode)
                        } else {
                            launchViewVideoIntent(pending.intent)
                        }
                    clearConsumedViewIntent(pending.intent)
                    if (pending.finishAfterLaunch && launched) {
                        finish()
                    }
                }

                val pendingMaAction by pendingMaActionDeepLink.collectAsStateWithLifecycle()
                LaunchedEffect(state.isLoading, state.sessionMode, pendingMaAction) {
                    if (state.isLoading) return@LaunchedEffect
                    if (!SessionNavigationPolicy.isMaOverlayAllowed(state.sessionMode)) {
                        if (pendingMaAction != null) pendingMaActionDeepLink.value = null
                        if (maUiDeepLink.value != null) maUiDeepLink.value = null
                        clearConsumedMaIntent()
                        return@LaunchedEffect
                    }
                    val action = pendingMaAction ?: return@LaunchedEffect
                    pendingMaActionDeepLink.value = null
                    clearConsumedMaIntent()
                    when (action) {
                        is MaDeepLink.Parsed.Play ->
                            dispatchMusicAssistantPlay(
                                this@UnifiedMainActivity,
                                maSession,
                                maServiceClient,
                                null,
                                action.uri,
                            )
                        MaDeepLink.Parsed.Resume ->
                            MaPlayDispatcher(
                                this@UnifiedMainActivity,
                                maSession,
                                maServiceClient,
                            ).playPause()
                        else -> Unit
                    }
                }

                val activeSearchQuery by pendingSearchQuery.collectAsStateWithLifecycle()

                // Audio & Music Assistant bridges strictly below LOCKED.
                dev.spatialfin.unified.music.MaConnectionBridge(
                    serviceClient = maServiceClient,
                    sessionMode = state.sessionMode,
                    activeSessionUserId = state.currentUser?.id?.toString(),
                )
                dev.spatialfin.unified.music.MaMediaSessionBridge(maSession)
                dev.spatialfin.unified.audio.JellyfinAudioMediaSessionBridge(jellyfinAudioDispatcher)
                dev.spatialfin.unified.audio.AudioPlaybackArbitrationBridge(
                    maSession = maSession,
                    maServiceClient = maServiceClient,
                    jellyfinDispatcher = jellyfinAudioDispatcher,
                )

                when (deviceClass) {
                    DeviceClass.TV -> {
                        CompositionLocalProvider(
                            dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher provides
                                jellyfinAudioDispatcher,
                            dev.jdtech.jellyfin.presentation.shell.LocalRemoteControlMiniPlayerHost provides
                                { modifier -> RemoteControlMiniPlayerHost(modifier) },
                            dev.jdtech.jellyfin.presentation.shell.LocalFCastReceiverController provides
                                AppFCastReceiverController,
                            dev.jdtech.jellyfin.presentation.settings.LocalVoicePickerDialog provides
                                { initialVoiceName, onSave, onDismissRequest ->
                                    dev.jdtech.jellyfin.presentation.settings.components.VoicePickerDialog(
                                        initialVoiceName = initialVoiceName,
                                        onSave = onSave,
                                        onDismissRequest = onDismissRequest,
                                    )
                                },
                        ) {
                            TvTheme {
                                shellStateHolder.SaveableStateProvider(deviceClass) {
                                    TvNavigationRoot(
                                        state = state,
                                        appPreferences = appPreferences,
                                        lanSetupController = lanSetupController,
                                        maSession = maSession,
                                        maServiceClient = maServiceClient,
                                        onLogout = viewModel::logout,
                                        initialSearchQuery = activeSearchQuery,
                                        onInitialSearchConsumed = ::clearPendingSearchQuery,
                                        maDeepLink = maUiDeepLink,
                                        onMaDeepLinkHandled = {
                                            maUiDeepLink.value = null
                                            clearConsumedMaIntent()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    DeviceClass.PHONE -> {
                        CompositionLocalProvider(
                            dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher provides
                                jellyfinAudioDispatcher,
                            dev.jdtech.jellyfin.presentation.shell.LocalRemoteControlMiniPlayerHost provides
                                { modifier -> RemoteControlMiniPlayerHost(modifier) },
                            dev.jdtech.jellyfin.presentation.shell.LocalFCastReceiverController provides
                                AppFCastReceiverController,
                            dev.jdtech.jellyfin.presentation.settings.LocalVoicePickerDialog provides
                                { initialVoiceName, onSave, onDismissRequest ->
                                    dev.jdtech.jellyfin.presentation.settings.components.VoicePickerDialog(
                                        initialVoiceName = initialVoiceName,
                                        onSave = onSave,
                                        onDismissRequest = onDismissRequest,
                                    )
                                },
                            dev.jdtech.jellyfin.presentation.settings.LocalSubtitlePreviewCard provides
                                { modifier ->
                                    dev.spatialfin.presentation.settings.components.SubtitlePreviewCard(
                                        appPreferences = appPreferences,
                                        modifier = modifier,
                                    )
                                },
                        ) {
                            BeamTheme {
                                Surface(modifier = Modifier, color = Color.Transparent) {
                                    shellStateHolder.SaveableStateProvider(deviceClass) {
                                        BeamNavigationRoot(
                                            state = state,
                                            appPreferences = appPreferences,
                                            repository = repository,
                                            llmModelManager = llmModelManager,
                                            voiceTelemetryStore = voiceTelemetryStore,
                                            fcastSession = fcastSession,
                                            musicAssistantRepository = musicAssistantRepository,
                                            maSession = maSession,
                                            maServiceClient = maServiceClient,
                                            onReconnect = viewModel::reconnect,
                                            onActivateStandalone = { viewModel.activateStandalone() },
                                            onLogout = viewModel::logout,
                                            onFinishAffinity = { finishAffinity() },
                                            maDeepLink = maUiDeepLink,
                                            onMaDeepLinkHandled = {
                                                maUiDeepLink.value = null
                                                clearConsumedMaIntent()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    DeviceClass.XR -> {
                        val coroutineScope = rememberCoroutineScope()
                        val context = LocalContext.current

                        dev.spatialfin.unified.music.MaLocalPlaybackBridge(maSession)

                        CompositionLocalProvider(
                            dev.spatialfin.unified.audio.LocalAudioPlaybackDispatcher provides
                                jellyfinAudioDispatcher
                        ) {
                            SpatialFinTheme(dynamicColor = state.isDynamicColors) {
                                val lifecycleOwner = LocalLifecycleOwner.current
                                val graph = xrNavGraph
                                if (
                                    !state.isLoading &&
                                        xrNavController != null &&
                                        xrObserverState != null &&
                                        graph != null
                                ) {
                                    CompositionLocalProvider(
                                        LocalOfflineMode provides state.isOfflineMode
                                    ) {
                                        if (
                                            xrSession != null &&
                                                spaceController != null &&
                                                spaceUiState != null
                                        ) {
                                            XrContent(
                                                session = xrSession,
                                                spaceController = spaceController,
                                                spaceUiState = spaceUiState,
                                                renderedSpaceMode = renderedSpaceMode,
                                                onRenderedSpaceModeChange = { renderedSpaceMode = it },
                                                navController = xrNavController,
                                                navGraph = graph,
                                                shellStateHolder = shellStateHolder,
                                                observerState = xrObserverState,
                                                lifecycleOwner = lifecycleOwner,
                                                coroutineScope = coroutineScope,
                                                context = context,
                                                state = state,
                                                appPreferences = appPreferences,
                                                repository = repository,
                                                initialSearchQueryExtra = activeSearchQuery,
                                                onInitialSearchConsumed = ::clearPendingSearchQuery,
                                                onActivateStandalone = { viewModel.activateStandalone() },
                                                onLogout = viewModel::logout,
                                                onFinishAffinity = { finishAffinity() },
                                                onFinish = { finish() },
                                            )
                                        } else {
                                            NavigationRoot(
                                                navController = xrNavController,
                                                navGraph = graph,
                                                shellStateHolder = shellStateHolder,
                                                observerState = xrObserverState,
                                                state = state,
                                                lanSetupController = lanSetupController,
                                                appPreferences = appPreferences,
                                                onActivateStandalone = { viewModel.activateStandalone() },
                                                onLogout = viewModel::logout,
                                                initialSearchQuery = activeSearchQuery,
                                                onInitialSearchConsumed = ::clearPendingSearchQuery,
                                                onReconnect = viewModel::reconnect,
                                                fcastSession = fcastSession,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appLockManager.refreshState()
    }

    override fun onDestroy() {
        if (startupPermissionModalActive) {
            startupPermissionModalActive = false
            systemModalGraceHook.setSystemModalActive(false)
        }
        super.onDestroy()
        displayListener?.let { listener ->
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
        }
        displayListener = null
        xrSessionState.value = null
    }

    private fun applyPhoneOrientation() {
        if (!capabilities.isPhone) return
        val dm = getSystemService(DisplayManager::class.java)
        val hasExternal = dm?.displays?.any { it.displayId != Display.DEFAULT_DISPLAY } == true
        requestedOrientation =
            if (hasExternal) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER
            }
    }

    private fun applyImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && capabilities.isPhone) {
            applyImmersiveSystemBars()
        }
    }

    // ---------------------------------------------------------------------------
    // XrContent — the full XR branch once a Session is available
    // ---------------------------------------------------------------------------

    @Composable
    private fun XrContent(
        session: Session,
        spaceController: XrSpaceController,
        spaceUiState: XrSpaceUiState,
        renderedSpaceMode: XrSpaceMode?,
        onRenderedSpaceModeChange: (XrSpaceMode?) -> Unit,
        navController: NavHostController,
        navGraph: NavGraph,
        shellStateHolder: SaveableStateHolder,
        observerState: XrNavigationObserverState,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        coroutineScope: kotlinx.coroutines.CoroutineScope,
        context: android.content.Context,
        state: MainState,
        appPreferences: AppPreferences,
        repository: JellyfinRepository,
        initialSearchQueryExtra: String?,
        onInitialSearchConsumed: () -> Unit,
        onActivateStandalone: () -> Unit,
        onLogout: () -> Unit,
        onFinishAffinity: () -> Unit,
        onFinish: () -> Unit,
    ) {
        val graceHook = LocalSystemModalGraceHook.current

        // Two-frame HOME <-> FULL space serialization so SaveableStateProvider("xr_nav_host")
        // flushes savedStates cleanly before the incoming applier mounts.
        val targetSpaceMode =
            if (spaceUiState.mode == XrSpaceMode.FULL && spaceUiState.spatialUiAvailable) {
                XrSpaceMode.FULL
            } else {
                XrSpaceMode.HOME
            }
        LaunchedEffect(targetSpaceMode) {
            if (renderedSpaceMode != null && renderedSpaceMode != targetSpaceMode) {
                onRenderedSpaceModeChange(null)
                withFrameNanos { }
            }
            onRenderedSpaceModeChange(targetSpaceMode)
        }

        val latestSessionMode = rememberUpdatedState(state.sessionMode)
        val latestOfflineBrowseEntered = rememberUpdatedState(observerState.offlineBrowseEntered)

        fun isVoiceAllowed(mode: SessionMode, offlineBrowse: Boolean): Boolean =
            mode == SessionMode.SERVER_AUTHENTICATED ||
                mode == SessionMode.STANDALONE_SOURCES ||
                (mode == SessionMode.SERVER_NEEDS_AUTH && offlineBrowse)

        // ------------------------------------------------------------------
        // Voice setup (shared across both space modes)
        // ------------------------------------------------------------------
        val voiceController = remember(context) {
            HomeVoiceController(
                applicationContext = context.applicationContext,
                appPreferences = appPreferences,
                repository = repository,
                llmModelManagerProvider = modelManager::get,
                voiceTelemetryStore = voiceTelemetryStore,
                maStateProvider = ::currentMaVoiceState,
            )
        }
        LaunchedEffect(initialSearchQueryExtra) {
            voiceController.setInitialSearchQuery(initialSearchQueryExtra)
        }
        val voiceState by voiceController.voiceService.state.collectAsState()
        val partialTranscript by voiceController.voiceService.partialTranscript.collectAsState()
        val voiceMicLevel by voiceController.voiceService.micLevel.collectAsState()
        val isTtsSpeaking by voiceController.tts.isSpeaking.collectAsState()

        val voiceControlEnabled by appPreferences.rememberPrefState(appPreferences.voiceControlEnabled) {
            appPreferences.getValue(appPreferences.voiceControlEnabled)
        }
        val voiceGestureHand by appPreferences.rememberPrefState(appPreferences.voiceGestureHand) {
            appPreferences.getValue(appPreferences.voiceGestureHand) ?: "left"
        }
        val assistantSpokenReplies by appPreferences.rememberPrefState(
            appPreferences.voiceAssistantSpokenReplies
        ) {
            appPreferences.getValue(appPreferences.voiceAssistantSpokenReplies)
        }
        val assistantVoiceName by appPreferences.rememberPrefState(appPreferences.voiceAssistantVoice) {
            appPreferences.getValue(appPreferences.voiceAssistantVoice)
        }

        var hasAudioPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
        var hasHandTrackingPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    HAND_TRACKING_PERMISSION,
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        var audioModalActive by remember { mutableStateOf(false) }
        var handModalActive by remember { mutableStateOf(false) }
        DisposableEffect(graceHook) {
            onDispose {
                if (audioModalActive) {
                    audioModalActive = false
                    graceHook.setSystemModalActive(false)
                }
                if (handModalActive) {
                    handModalActive = false
                    graceHook.setSystemModalActive(false)
                }
            }
        }

        val audioPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (audioModalActive) {
                    audioModalActive = false
                    graceHook.setSystemModalActive(false)
                }
                hasAudioPermission = granted
            }
        val handTrackingPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (handModalActive) {
                    handModalActive = false
                    graceHook.setSystemModalActive(false)
                }
                hasHandTrackingPermission = granted
            }

        val pinchDetector = remember(session, voiceGestureHand) {
            SecondaryHandPinchDetector(
                session = session,
                activity = this@UnifiedMainActivity,
                preferredHand = voiceGestureHand,
                shouldDetectActivation = {
                    isVoiceAllowed(latestSessionMode.value, latestOfflineBrowseEntered.value) &&
                        voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        hasAudioPermission &&
                        voiceController.voiceService.isAvailable() &&
                        !voiceController.isVoiceTurnBusy(voiceState, isTtsSpeaking)
                },
                shouldDetectInterrupt = {
                    isVoiceAllowed(latestSessionMode.value, latestOfflineBrowseEntered.value) &&
                        voiceControlEnabled &&
                        hasHandTrackingPermission &&
                        voiceController.isVoiceTurnBusy(voiceState, isTtsSpeaking)
                },
            )
        }

        val voiceCoroutineScope = rememberCoroutineScope()
        val maVoicePlayDispatcher = remember(context, maSession, maServiceClient) {
            MaPlayDispatcher(context, maSession, maServiceClient)
        }
        val navigation = remember(navController, context, fcastSession, maVoicePlayDispatcher) {
            object : HomeVoiceNavigation {
                private fun navigateToItem(item: SpatialFinItem): Boolean {
                    if (
                        navController.currentBackStackEntry?.lifecycle?.currentState !=
                            Lifecycle.State.RESUMED
                    ) {
                        return false
                    }
                    return when (item) {
                        is UniversalSpatialFinItem -> {
                            context.startActivity(
                                XrPlayerActivity.createIntentForUniversalMedia(
                                    context = context,
                                    pluginId = item.universalMediaItem.pluginId,
                                    itemId = item.universalMediaItem.id,
                                    videoUrl = item.universalMediaItem.videoUrl,
                                    title = item.name,
                                    stereoMode = item.universalMediaItem.stereoMode,
                                    projection = item.universalMediaItem.projection,
                                )
                            )
                            true
                        }
                        is LocalVideoItem -> {
                            navController.navigate(LocalVideoRoute(item.mediaStoreId))
                            true
                        }
                        is NetworkVideoItem -> {
                            navController.navigate(NetworkVideoRoute(videoId = item.networkVideoId))
                            true
                        }
                        is SpatialFinMovie -> {
                            navController.navigate(MovieRoute(movieId = item.id.toString()))
                            true
                        }
                        is SpatialFinShow -> {
                            navController.navigate(ShowRoute(showId = item.id.toString()))
                            true
                        }
                        is SpatialFinSeason -> {
                            navController.navigate(SeasonRoute(seasonId = item.id.toString()))
                            true
                        }
                        is SpatialFinEpisode -> {
                            navController.navigate(EpisodeRoute(episodeId = item.id.toString()))
                            true
                        }
                        else -> {
                            if (!SessionNavigationPolicy.isSearchAllowed(latestSessionMode.value)) {
                                return false
                            }
                            voiceController.voiceSearchQuery = item.name
                            navController.navigate(MediaRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            true
                        }
                    }
                }

                override fun launchItem(item: SpatialFinItem): Boolean {
                    val mode = latestSessionMode.value
                    val offlineBrowse = latestOfflineBrowseEntered.value
                    val allowed =
                        when (mode) {
                            SessionMode.SERVER_AUTHENTICATED -> true
                            SessionMode.STANDALONE_SOURCES ->
                                item is UniversalSpatialFinItem ||
                                    item is LocalVideoItem ||
                                    item is NetworkVideoItem
                            SessionMode.SERVER_NEEDS_AUTH ->
                                offlineBrowse &&
                                    (item is LocalVideoItem ||
                                        item is NetworkVideoItem ||
                                        item.isDownloaded())
                            SessionMode.UNCONFIGURED -> false
                        }
                    if (!allowed) return false

                    val routed =
                        dev.spatialfin.fcast.session.launchPlayback(
                            context = context,
                            sessionManager = fcastSession,
                            scope = voiceCoroutineScope,
                            item = item,
                            splitAvIntentBuilder = {
                                XrPlayerActivity.createIntentForItem(
                                    context,
                                    item,
                                    splitAvVideoRole = true,
                                )
                            },
                        ) { XrPlayerActivity.createIntentForItem(context, item) }
                    if (routed) return true
                    return navigateToItem(item)
                }

                override fun launchMusic(query: String): Boolean {
                    if (!SessionNavigationPolicy.isMaOverlayAllowed(latestSessionMode.value)) {
                        return false
                    }
                    voiceCoroutineScope.launch {
                        launchMusicAssistantQuery(
                            context = context,
                            repository = musicAssistantRepository,
                            session = maSession,
                            serviceClient = maServiceClient,
                            query = query,
                        )
                    }
                    return true
                }

                override fun controlMusic(command: MusicVoiceCommand): Boolean {
                    if (!SessionNavigationPolicy.isMaOverlayAllowed(latestSessionMode.value)) {
                        return false
                    }
                    if (maSession.session.value.selectedPlayer == null) return false
                    when (command) {
                        MusicVoiceCommand.PlayPause -> maVoicePlayDispatcher.playPause()
                        MusicVoiceCommand.Pause -> maVoicePlayDispatcher.pause()
                        MusicVoiceCommand.Resume -> maVoicePlayDispatcher.resume()
                        MusicVoiceCommand.Next -> maVoicePlayDispatcher.next()
                        MusicVoiceCommand.Previous -> maVoicePlayDispatcher.previous()
                        is MusicVoiceCommand.AdjustVolume -> {
                            val pct = command.percentage
                            if (pct != null) maVoicePlayDispatcher.setVolume((pct * 100f).toInt())
                            else maVoicePlayDispatcher.nudgeVolume(command.delta ?: 0.1f)
                        }
                    }
                    return true
                }

                override fun goHome() {
                    navigateHome(
                        navController = navController,
                        sessionMode = latestSessionMode.value,
                        offlineBrowseEntered = latestOfflineBrowseEntered.value,
                    )
                }

                override fun goBack() {
                    if (!navController.popBackStack()) onFinish()
                }

                override fun closeApp() {
                    onFinishAffinity()
                }
            }
        }

        val latestHasAudioPermission = rememberUpdatedState(hasAudioPermission)
        val latestAssistantSpoken = rememberUpdatedState(assistantSpokenReplies)
        val latestAssistantVoice = rememberUpdatedState(assistantVoiceName)
        val onAudioPermissionMissing = rememberUpdatedState({
            if (!audioModalActive) {
                audioModalActive = true
                graceHook.setSystemModalActive(true)
            }
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        })

        fun startVoiceCommand(source: String) {
            if (!isVoiceAllowed(latestSessionMode.value, latestOfflineBrowseEntered.value)) return
            voiceController.requestVoiceCommand(
                scope = coroutineScope,
                source = source,
                currentVoiceState = voiceState,
                currentTtsSpeaking = isTtsSpeaking,
                hasAudioPermission = latestHasAudioPermission.value,
                assistantSpokenReplies = latestAssistantSpoken.value,
                assistantVoiceName = latestAssistantVoice.value,
                navigation = navigation,
                onAudioPermissionMissing = { onAudioPermissionMissing.value() },
            )
        }

        LaunchedEffect(session, hasHandTrackingPermission) {
            if (hasHandTrackingPermission) {
                runCatching {
                    session.configure(
                        androidx.xr.runtime.Config.Builder(session.config)
                            .setHandTracking(HandTrackingMode.BOTH)
                            .build()
                    )
                }.onFailure { Timber.w(it, "VOICE: Hand tracking not available") }
            }
        }

        LaunchedEffect(pinchDetector, hasHandTrackingPermission, lifecycleOwner) {
            if (!hasHandTrackingPermission) return@LaunchedEffect
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pinchDetector.gestureStates.collect { event ->
                    when (event) {
                        is SecondaryHandPinchDetector.GestureState.Arming -> {
                            voiceController.onGestureUpdate(event.progress, event.hint)
                        }
                        is SecondaryHandPinchDetector.GestureState.Started -> {
                            voiceController.onGestureUpdate(1f, null)
                            when (event.gestureType) {
                                SecondaryHandPinchDetector.GestureType.ACTIVATE ->
                                    startVoiceCommand("manual")
                                SecondaryHandPinchDetector.GestureType.INTERRUPT ->
                                    voiceController.interruptVoiceCommand(
                                        scope = coroutineScope,
                                        reason = "fist-gesture",
                                        currentVoiceState = voiceState,
                                        currentTtsSpeaking = isTtsSpeaking,
                                        requestFollowUp = { startVoiceCommand("follow-up-interrupt") },
                                    )
                            }
                        }
                        is SecondaryHandPinchDetector.GestureState.Ended ->
                            voiceController.clearGestureHintIfIdle(voiceState)
                        SecondaryHandPinchDetector.GestureState.Idle ->
                            voiceController.clearGestureHintIfIdle(voiceState)
                    }
                }
            }
        }

        voiceController.RegisterEffects(
            requestFollowUp = { startVoiceCommand("follow-up-auto") },
        )

        DisposableEffect(voiceController) {
            onDispose { voiceController.destroy() }
        }

        val recommendationContext = voiceController.recommendationContext
        val onSelectRecommendation: (Int, SpatialFinItem) -> Unit = { _, item ->
            if (navigation.launchItem(item)) {
                voiceController.clearRecommendationContext()
            }
        }
        val onDismissRecommendation: () -> Unit = { voiceController.clearRecommendationContext() }

        when (renderedSpaceMode) {
            XrSpaceMode.FULL -> {
                FullSpaceContent(
                    navController = navController,
                    navGraph = navGraph,
                    shellStateHolder = shellStateHolder,
                    observerState = observerState,
                    state = state,
                    appPreferences = appPreferences,
                    voiceSearchQuery = voiceController.voiceSearchQuery,
                    onInitialSearchConsumed = {
                        voiceController.voiceSearchQuery = null
                        onInitialSearchConsumed()
                    },
                    voiceState = voiceState,
                    partialTranscript = partialTranscript,
                    voiceFeedback = voiceController.voiceFeedback,
                    voiceGestureArmingProgress = voiceController.voiceGestureArmingProgress,
                    voiceGestureHint = voiceController.voiceGestureHint,
                    voiceMicLevel = voiceMicLevel,
                    transitioning = spaceUiState.transitioning,
                    recommendationContext = recommendationContext,
                    onSelectRecommendation = onSelectRecommendation,
                    onDismissRecommendation = onDismissRecommendation,
                    onReconnect = viewModel::reconnect,
                    onActivateStandalone = onActivateStandalone,
                    onLogout = onLogout,
                    onEnterHomeSpace = { spaceController.enterHomeSpace() },
                    onVoiceClick = { startVoiceCommand("orbiter") },
                )
            }
            XrSpaceMode.HOME -> {
                HomeSpaceContent(
                    navController = navController,
                    navGraph = navGraph,
                    shellStateHolder = shellStateHolder,
                    observerState = observerState,
                    state = state,
                    appPreferences = appPreferences,
                    voiceSearchQuery = voiceController.voiceSearchQuery,
                    onInitialSearchConsumed = {
                        voiceController.voiceSearchQuery = null
                        onInitialSearchConsumed()
                    },
                    voiceState = voiceState,
                    partialTranscript = partialTranscript,
                    voiceFeedback = voiceController.voiceFeedback,
                    voiceGestureArmingProgress = voiceController.voiceGestureArmingProgress,
                    voiceGestureHint = voiceController.voiceGestureHint,
                    voiceMicLevel = voiceMicLevel,
                    transitioning = spaceUiState.transitioning,
                    recommendationContext = recommendationContext,
                    onSelectRecommendation = onSelectRecommendation,
                    onDismissRecommendation = onDismissRecommendation,
                    onReconnect = viewModel::reconnect,
                    onActivateStandalone = onActivateStandalone,
                    onLogout = onLogout,
                    onEnterFullSpace = { spaceController.enterFullSpace() },
                    onVoiceClick = { startVoiceCommand("orbiter") },
                )
            }
            null -> {
                // One-frame transition gap while outgoing SaveableStateProvider unmounts cleanly.
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Home Space path — standard adaptive Compose, no Subspace/SpatialPanel
    // ---------------------------------------------------------------------------

    @Composable
    private fun HomeSpaceContent(
        navController: NavHostController,
        navGraph: NavGraph,
        shellStateHolder: SaveableStateHolder,
        observerState: XrNavigationObserverState,
        state: MainState,
        appPreferences: AppPreferences,
        voiceSearchQuery: String?,
        onInitialSearchConsumed: () -> Unit,
        voiceState: VoiceState,
        partialTranscript: String,
        voiceFeedback: String?,
        voiceGestureArmingProgress: Float,
        voiceGestureHint: String?,
        voiceMicLevel: Float,
        transitioning: Boolean,
        recommendationContext: RecommendationContext?,
        onSelectRecommendation: (Int, SpatialFinItem) -> Unit,
        onDismissRecommendation: () -> Unit,
        onReconnect: () -> Unit,
        onActivateStandalone: () -> Unit,
        onLogout: () -> Unit,
        onEnterFullSpace: () -> Unit,
        onVoiceClick: (() -> Unit)? = null,
    ) {
        val surfaceAlpha by animateFloatAsState(
            targetValue = if (transitioning) 0f else 1f,
            animationSpec = tween(durationMillis = 260),
            label = "home-space-fade",
        )
        val maOverlayAllowed = SessionNavigationPolicy.isMaOverlayAllowed(state.sessionMode)

        Box(modifier = Modifier.fillMaxSize().alpha(surfaceAlpha)) {
            var maDetailSeed by remember {
                mutableStateOf<dev.jdtech.jellyfin.data.musicassistant.data.model.server.ServerMediaItem?>(null)
            }
            var maDetailKind by remember {
                mutableStateOf<dev.spatialfin.unified.music.MaDetailViewModel.DetailKind?>(null)
            }
            var showNowPlaying by remember { mutableStateOf(false) }
            var showPickerSheet by remember { mutableStateOf(false) }
            var showParty by remember { mutableStateOf(false) }
            var showMaSearch by remember { mutableStateOf(false) }

            LaunchedEffect(maOverlayAllowed) {
                if (!maOverlayAllowed) {
                    showNowPlaying = false
                    showPickerSheet = false
                    showParty = false
                    showMaSearch = false
                    maDetailSeed = null
                    maDetailKind = null
                }
            }

            val xrContext = LocalContext.current
            val dispatcher = remember(xrContext) {
                MaPlayDispatcher(xrContext, maSession, maServiceClient)
            }

            NavigationRoot(
                navController = navController,
                navGraph = navGraph,
                shellStateHolder = shellStateHolder,
                observerState = observerState,
                state = state,
                lanSetupController = lanSetupController,
                appPreferences = appPreferences,
                onActivateStandalone = onActivateStandalone,
                onLogout = onLogout,
                initialSearchQuery = voiceSearchQuery,
                onInitialSearchConsumed = onInitialSearchConsumed,
                onReconnect = onReconnect,
                xrSpaceMode = XrSpaceMode.HOME,
                onEnterFullSpace = onEnterFullSpace,
                fcastSession = fcastSession,
                onMaItemTap =
                    if (maOverlayAllowed) {
                        { item ->
                            dev.spatialfin.unified.music.handleMaHomeItemTap(
                                item = item,
                                dispatcher = dispatcher,
                                onOpenDetail = { target ->
                                    maDetailSeed = target.item
                                    maDetailKind = target.detailKind
                                },
                            )
                        }
                    } else {
                        null
                    },
                onVoiceClick = onVoiceClick,
            )

            VoiceControlOverlay(
                state = voiceState,
                partialTranscript = partialTranscript,
                feedbackText = voiceFeedback,
                gestureArmingProgress = voiceGestureArmingProgress,
                gestureHint = voiceGestureHint,
                micLevel = voiceMicLevel,
            )

            recommendationContext?.let { ctx ->
                if (ctx.items.size > 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 96.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        DisambiguationCarousel(
                            query = ctx.query,
                            items = ctx.items,
                            onSelect = onSelectRecommendation,
                            onDismiss = onDismissRecommendation,
                        )
                    }
                }
            }

            val pendingDeepLink by maUiDeepLink.collectAsStateWithLifecycle()
            LaunchedEffect(pendingDeepLink, state.isLoading, maOverlayAllowed) {
                val dl = pendingDeepLink ?: return@LaunchedEffect
                if (state.isLoading) return@LaunchedEffect
                maUiDeepLink.value = null
                clearConsumedMaIntent()
                if (!maOverlayAllowed) return@LaunchedEffect
                when (dl) {
                    MaDeepLink.Parsed.Queue -> showNowPlaying = true
                    MaDeepLink.Parsed.Party -> showParty = true
                    else -> Unit
                }
            }

            CompositionLocalProvider(LocalMaPlayDispatcher provides dispatcher) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Column {
                        if (state.sessionMode == SessionMode.SERVER_AUTHENTICATED) {
                            RemoteControlMiniPlayerHost()
                        }
                        if (maOverlayAllowed) {
                            dev.spatialfin.unified.music.MaMiniPlayer(
                                session = maSession,
                                onExpand = { showNowPlaying = true },
                            )
                        }
                    }
                }
                if (maOverlayAllowed && showNowPlaying) {
                    BackHandler(onBack = { showNowPlaying = false })
                    dev.spatialfin.unified.music.MaNowPlayingScreen(
                        session = maSession,
                        onBack = { showNowPlaying = false },
                        onPickPlayer = { showPickerSheet = true },
                        onOpenSearch = {
                            showNowPlaying = false
                            showMaSearch = true
                        },
                        onOpenParty = { showParty = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (maOverlayAllowed && showParty) {
                    BackHandler(onBack = { showParty = false })
                    dev.spatialfin.unified.music.MaPartyScreen(
                        session = maSession,
                        onBack = { showParty = false },
                        onPickPlayer = { showPickerSheet = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (maOverlayAllowed && showPickerSheet) {
                    dev.spatialfin.unified.music.MaPlayerPickerSheet(
                        session = maSession,
                        dispatcher = dispatcher,
                        onDismiss = { showPickerSheet = false },
                    )
                }
                if (maOverlayAllowed && showMaSearch) {
                    BackHandler(onBack = { showMaSearch = false })
                    dev.spatialfin.unified.music.MaSearchScreen(
                        onBack = { showMaSearch = false },
                        onOpenItem = { target ->
                            maDetailSeed = target.item
                            maDetailKind = target.detailKind
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            val seed = maDetailSeed
            val kind = maDetailKind
            if (maOverlayAllowed && seed != null && kind != null) {
                BackHandler(onBack = { maDetailSeed = null })
                dev.spatialfin.unified.music.MaDetailScreen(
                    seed = seed,
                    kind = kind,
                    onBack = { maDetailSeed = null },
                    onOpenItem = { nested ->
                        maDetailSeed = nested.item
                        maDetailKind = nested.detailKind
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Full Space path — spatialized XR UI with movable SpatialPanel
    // ---------------------------------------------------------------------------

    @Composable
    private fun FullSpaceContent(
        navController: NavHostController,
        navGraph: NavGraph,
        shellStateHolder: SaveableStateHolder,
        observerState: XrNavigationObserverState,
        state: MainState,
        appPreferences: AppPreferences,
        voiceSearchQuery: String?,
        onInitialSearchConsumed: () -> Unit,
        voiceState: VoiceState,
        partialTranscript: String,
        voiceFeedback: String?,
        voiceGestureArmingProgress: Float,
        voiceGestureHint: String?,
        voiceMicLevel: Float,
        @Suppress("UNUSED_PARAMETER") transitioning: Boolean,
        recommendationContext: RecommendationContext?,
        onSelectRecommendation: (Int, SpatialFinItem) -> Unit,
        onDismissRecommendation: () -> Unit,
        onReconnect: () -> Unit,
        onActivateStandalone: () -> Unit,
        onLogout: () -> Unit,
        onEnterHomeSpace: () -> Unit,
        onVoiceClick: (() -> Unit)? = null,
    ) {
        var controlsVisible by remember { mutableStateOf(true) }
        var hideTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val maOverlayAllowed = SessionNavigationPolicy.isMaOverlayAllowed(state.sessionMode)

        val savedAppPanelScale = remember {
            appPreferences.getValue(appPreferences.xrAppPanelScale).coerceIn(0.5f, 3.0f)
        }

        LaunchedEffect(savedAppPanelScale) {
            Timber.i(
                "FullSpaceContent: rendering SpatialPanel widthDp=%d heightDp=%d scale=%.2f",
                (XR_APP_PANEL_WIDTH_DP * savedAppPanelScale).toInt(),
                (XR_APP_PANEL_HEIGHT_DP * savedAppPanelScale).toInt(),
                savedAppPanelScale,
            )
        }

        LaunchedEffect(controlsVisible, hideTimestamp) {
            if (controlsVisible) {
                delay(5000L)
                controlsVisible = false
            }
        }

        Subspace {
            SpatialPanel(
                modifier =
                    SubspaceModifier
                        .width((XR_APP_PANEL_WIDTH_DP * savedAppPanelScale).dp)
                        .height((XR_APP_PANEL_HEIGHT_DP * savedAppPanelScale).dp)
                        .movable()
            ) {
                val spatialCtx = LocalContext.current
                val spatialDispatcher = remember(spatialCtx) {
                    MaPlayDispatcher(spatialCtx, maSession, maServiceClient)
                }

                var showSpatialNowPlaying by remember { mutableStateOf(false) }
                var showSpatialPicker by remember { mutableStateOf(false) }
                var showSpatialParty by remember { mutableStateOf(false) }

                LaunchedEffect(maOverlayAllowed) {
                    if (!maOverlayAllowed) {
                        showSpatialNowPlaying = false
                        showSpatialPicker = false
                        showSpatialParty = false
                    }
                }

                val pendingDeepLink by maUiDeepLink.collectAsStateWithLifecycle()
                LaunchedEffect(pendingDeepLink, state.isLoading, maOverlayAllowed) {
                    val dl = pendingDeepLink ?: return@LaunchedEffect
                    if (state.isLoading) return@LaunchedEffect
                    maUiDeepLink.value = null
                    clearConsumedMaIntent()
                    if (!maOverlayAllowed) return@LaunchedEffect
                    when (dl) {
                        MaDeepLink.Parsed.Queue -> showSpatialNowPlaying = true
                        MaDeepLink.Parsed.Party -> showSpatialParty = true
                        else -> Unit
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (
                                        event.type == PointerEventType.Enter ||
                                            event.type == PointerEventType.Move
                                    ) {
                                        controlsVisible = true
                                        hideTimestamp = System.currentTimeMillis()
                                    }
                                }
                            }
                        }
                ) {
                    NavigationRoot(
                        navController = navController,
                        navGraph = navGraph,
                        shellStateHolder = shellStateHolder,
                        observerState = observerState,
                        state = state,
                        lanSetupController = lanSetupController,
                        appPreferences = appPreferences,
                        onActivateStandalone = onActivateStandalone,
                        onLogout = onLogout,
                        initialSearchQuery = voiceSearchQuery,
                        onInitialSearchConsumed = onInitialSearchConsumed,
                        onReconnect = onReconnect,
                        xrSpaceMode = XrSpaceMode.FULL,
                        onEnterHomeSpace = onEnterHomeSpace,
                        fcastSession = fcastSession,
                        onMaItemTap =
                            if (maOverlayAllowed) {
                                { item ->
                                    dev.spatialfin.unified.music.handleMaHomeItemTap(
                                        item = item,
                                        dispatcher = spatialDispatcher,
                                        onOpenDetail = null,
                                    )
                                }
                            } else {
                                null
                            },
                        onVoiceClick = onVoiceClick,
                    )
                    VoiceControlOverlay(
                        state = voiceState,
                        partialTranscript = partialTranscript,
                        feedbackText = voiceFeedback,
                        gestureArmingProgress = voiceGestureArmingProgress,
                        gestureHint = voiceGestureHint,
                        micLevel = voiceMicLevel,
                    )
                    recommendationContext?.let { ctx ->
                        if (ctx.items.size > 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 80.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                DisambiguationCarousel(
                                    query = ctx.query,
                                    items = ctx.items,
                                    onSelect = onSelectRecommendation,
                                    onDismiss = onDismissRecommendation,
                                )
                            }
                        }
                    }
                }

                if (maOverlayAllowed) {
                    CompositionLocalProvider(LocalMaPlayDispatcher provides spatialDispatcher) {
                        Orbiter(
                            position = OrbiterPosition.BottomCenter(
                                offset = DpVolumeOffset(
                                    x = 0.dp,
                                    y = 32.dp,
                                    z = OrbiterDefaults.Elevation,
                                ),
                            ),
                        ) {
                            Column {
                                dev.spatialfin.unified.music.MaMiniPlayer(
                                    session = maSession,
                                    onExpand = { showSpatialNowPlaying = true },
                                )
                            }
                        }
                    }
                }

                val sendspinState by SendspinReceiverSession.state.collectAsStateWithLifecycle()
                if (maOverlayAllowed && showSpatialNowPlaying) {
                    val dialogModifier = Modifier.uiWidth(800.dp).uiHeight(824.dp)
                    SpatialDialog(onDismissRequest = { showSpatialNowPlaying = false }) {
                        CompositionLocalProvider(LocalMaPlayDispatcher provides spatialDispatcher) {
                            dev.spatialfin.unified.music.MaNowPlayingScreen(
                                session = maSession,
                                onBack = { showSpatialNowPlaying = false },
                                onPickPlayer = {
                                    showSpatialNowPlaying = false
                                    showSpatialPicker = true
                                },
                                onOpenParty = {
                                    showSpatialNowPlaying = false
                                    showSpatialParty = true
                                },
                                modifier = dialogModifier,
                            )
                        }
                    }
                }
                if (maOverlayAllowed && showSpatialParty) {
                    val dialogModifier = Modifier.uiWidth(800.dp).uiHeight(824.dp)
                    SpatialDialog(
                        onDismissRequest = {
                            showSpatialParty = false
                            showSpatialNowPlaying = true
                        },
                    ) {
                        CompositionLocalProvider(LocalMaPlayDispatcher provides spatialDispatcher) {
                            dev.spatialfin.unified.music.MaPartyScreen(
                                session = maSession,
                                onBack = {
                                    showSpatialParty = false
                                    showSpatialNowPlaying = true
                                },
                                onPickPlayer = { showSpatialPicker = true },
                                modifier = dialogModifier,
                            )
                        }
                    }
                }
                if (maOverlayAllowed && showSpatialPicker) {
                    val dialogModifier = Modifier.uiWidth(800.dp).uiHeight(824.dp)
                    SpatialDialog(
                        onDismissRequest = {
                            showSpatialPicker = false
                            showSpatialNowPlaying = true
                        },
                    ) {
                        Surface(
                            modifier = dialogModifier,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            val maState by maSession.session.collectAsStateWithLifecycle()
                            dev.spatialfin.unified.music.MaPlayerPickerContent(
                                players = maState.visiblePlayers,
                                selectedPlayer = maState.selectedPlayer,
                                localPlayerId = sendspinState.musicAssistantQueuePlayerId,
                                playbackActive = maState.nowPlaying != null,
                                onToggleGroupMember = { leaderId, memberId, grouped ->
                                    if (grouped) {
                                        spatialDispatcher.removeFromSyncGroup(leaderId, memberId)
                                    } else {
                                        spatialDispatcher.addToSyncGroup(leaderId, memberId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun requestStartupPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PERMISSIONS_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(STARTUP_PERMISSIONS_REQUESTED_KEY, false)) return

        // Drop CAMERA from startup permissions; request XR audio/hand-tracking/local-video only.
        val missingPermissions =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                add(HAND_TRACKING_PERMISSION)
                addAll(localVideoPermissions())
            }
                .distinct()
                .filter { permission ->
                    ContextCompat.checkSelfPermission(this@UnifiedMainActivity, permission) !=
                        PackageManager.PERMISSION_GRANTED
                }

        prefs.edit().putBoolean(STARTUP_PERMISSIONS_REQUESTED_KEY, true).apply()
        if (missingPermissions.isNotEmpty()) {
            if (!startupPermissionModalActive) {
                startupPermissionModalActive = true
                systemModalGraceHook.setSystemModalActive(true)
            }
            startupPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun scheduleUserDataSync() {
        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

        WorkManager.getInstance(applicationContext)
            .beginUniqueWork("syncUserData", ExistingWorkPolicy.REPLACE, syncWorkRequest)
            .enqueue()
    }
}

/**
 * Re-reads any preference whenever its backing SharedPreferences key changes.
 */
@Composable
private fun <T> AppPreferences.rememberPrefState(
    preference: dev.jdtech.jellyfin.settings.domain.models.Preference<*>,
    read: () -> T,
): androidx.compose.runtime.State<T> {
    val state = remember(this, preference) { mutableStateOf(read()) }
    DisposableEffect(this, preference) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == preference.backendName) {
                    state.value = read()
                }
            }
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return state
}
