package dev.jdtech.jellyfin.settings.presentation.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.lifecycle.viewModelScope
import dev.jdtech.jellyfin.settings.R
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.FakeSharedPreferences
import dev.jdtech.jellyfin.settings.domain.llm.DownloadState
import dev.jdtech.jellyfin.settings.domain.llm.LlmDownloadManager
import dev.jdtech.jellyfin.settings.domain.llm.ModelState
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceCategory
import dev.jdtech.jellyfin.settings.presentation.models.PreferenceSelect
import dev.jdtech.jellyfin.settings.voice.VoiceTelemetryStore
import io.mockk.every
import io.mockk.mockk
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val createdViewModels = mutableListOf<SettingsViewModel>()
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var xrPrefs: FakeSharedPreferences
    private lateinit var appPreferences: AppPreferences
    private lateinit var context: Context
    private lateinit var voiceTelemetryStore: VoiceTelemetryStore
    private lateinit var downloadManager: LlmDownloadManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        prefs = FakeSharedPreferences()
        xrPrefs = FakeSharedPreferences()
        appPreferences = AppPreferences(
            sharedPreferences = prefs,
            xrPlayerSharedPreferences = xrPrefs,
            isMainProcess = true,
            isDebuggable = true,
        )

        val resources = mockk<Resources>(relaxed = true)
        val configuration = mockk<Configuration>(relaxed = true)
        val locales = mockk<LocaleList>(relaxed = true)
        every { locales.isEmpty } returns false
        every { locales[0] } returns Locale.US
        every { configuration.locales } returns locales
        every { resources.configuration } returns configuration
        every { resources.getString(any()) } returns "label"
        every { resources.getString(any(), *anyVararg()) } returns "label"

        context = mockk(relaxed = true)
        every { context.resources } returns resources
        every { context.getString(any()) } returns "label"
        every { context.getString(any(), *anyVararg()) } returns "label"

        voiceTelemetryStore = mockk(relaxed = true)
        downloadManager = mockk(relaxed = true)
        every { downloadManager.downloadState } returns MutableStateFlow(DownloadState.Idle)
        every { downloadManager.modelState } returns MutableStateFlow(ModelState.Idle)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { vm ->
            vm.viewModelScope.cancel()
        }
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            appPreferences = appPreferences,
            context = context,
            voiceTelemetryStore = voiceTelemetryStore,
            downloadManager = downloadManager,
        ).also { createdViewModels += it }

    private suspend fun SettingsViewModel.awaitContaining(resId: Int): SettingsState {
        return state.first { s ->
            !s.isLoading &&
                s.preferenceGroups.flatMap { it.preferences }.any { it.nameStringResource == resId }
        }
    }

    @Test
    fun `unauthenticated settings hides Users, disables Approve QC, and shows Connect to server`() = runTest {
        val vm = createViewModel()
        vm.loadPreferences(intArrayOf(R.string.title_settings), DeviceType.XR, isServerAuthenticated = false)

        val topPrefs = vm.awaitContaining(R.string.settings_device_setup_title)
            .preferenceGroups.flatMap { it.preferences }
        assertNull(topPrefs.find { it.nameStringResource == R.string.users })
        assertNotNull(topPrefs.find { it.nameStringResource == R.string.settings_category_servers })
        assertNotNull(topPrefs.find { it.nameStringResource == R.string.settings_device_setup_title })

        vm.loadPreferences(intArrayOf(R.string.settings_device_setup_title), DeviceType.XR)
        val deviceSetupPrefs = vm.awaitContaining(R.string.welcome_companion_title)
            .preferenceGroups.flatMap { it.preferences }

        val setUpFromDevice = deviceSetupPrefs.find { it.nameStringResource == R.string.welcome_companion_title } as? PreferenceCategory
        val connectServer = deviceSetupPrefs.find { it.nameStringResource == R.string.settings_connect_to_jellyfin_server } as? PreferenceCategory
        val approveQc = deviceSetupPrefs.find { it.nameStringResource == R.string.settings_approve_qc_title } as? PreferenceCategory
        val signOut = deviceSetupPrefs.find { it.nameStringResource == R.string.settings_sign_out }

        assertNotNull(setUpFromDevice)
        assertNotNull(connectServer)
        assertNotNull(approveQc)
        assertFalse(approveQc!!.enabled)
        assertNull(signOut)

        vm.onAction(SettingsAction.OnPreferenceClick(setUpFromDevice!!))
        assertEquals(SettingsEvent.NavigateToSetUpFromDevice, vm.events.first())

        vm.onAction(SettingsAction.OnPreferenceClick(connectServer!!))
        assertEquals(SettingsEvent.NavigateToConnectServer, vm.events.first())
    }

    @Test
    fun `authenticated settings shows Users, enables Approve QC, shows Sign out, and preserves auth on null reload`() = runTest {
        val vm = createViewModel()
        vm.loadPreferences(intArrayOf(R.string.title_settings), DeviceType.XR, isServerAuthenticated = true)

        val topPrefs = vm.awaitContaining(R.string.users)
            .preferenceGroups.flatMap { it.preferences }
        assertNotNull(topPrefs.find { it.nameStringResource == R.string.users })

        // Reloading with isServerAuthenticated = null must preserve cached true state
        vm.loadPreferences(intArrayOf(R.string.settings_device_setup_title), DeviceType.XR, isServerAuthenticated = null)

        val deviceSetupPrefs = vm.awaitContaining(R.string.settings_sign_out)
            .preferenceGroups.flatMap { it.preferences }
        assertNull(deviceSetupPrefs.find { it.nameStringResource == R.string.settings_connect_to_jellyfin_server })

        val approveQc = deviceSetupPrefs.find { it.nameStringResource == R.string.settings_approve_qc_title } as? PreferenceCategory
        val signOut = deviceSetupPrefs.find { it.nameStringResource == R.string.settings_sign_out } as? PreferenceCategory
        assertNotNull(approveQc)
        assertTrue(approveQc!!.enabled)
        assertNotNull(signOut)

        vm.onAction(SettingsAction.OnPreferenceClick(approveQc))
        assertEquals(SettingsEvent.ShowQuickConnectApprover, vm.events.first())

        vm.onAction(SettingsAction.OnPreferenceClick(signOut!!))
        assertEquals(SettingsEvent.SignOut, vm.events.first())
    }

    @Test
    fun `changing appLockMode emits ChangeAppLockMode without pre-persisting to AppPreferences`() = runTest {
        val vm = createViewModel()
        vm.loadPreferences(intArrayOf(R.string.settings_category_security), DeviceType.XR, isServerAuthenticated = true)
        vm.awaitContaining(R.string.settings_app_lock_mode_title)

        assertEquals("off", appPreferences.getValue(appPreferences.appLockMode))

        val select = PreferenceSelect(
            nameStringResource = R.string.settings_app_lock_mode_title,
            backendPreference = appPreferences.appLockMode,
            options = R.array.app_lock_mode,
            optionValues = R.array.app_lock_mode_values,
            value = "pin",
        )
        vm.onAction(SettingsAction.OnUpdate(select))

        assertEquals(SettingsEvent.ChangeAppLockMode("pin"), vm.events.first())
        // AppPreferences must still hold "off" so AppLockManager remains the sole atomic writer.
        assertEquals("off", appPreferences.getValue(appPreferences.appLockMode))
    }

    @Test
    fun `preference listener registers on init, refreshes state, and unregisters onCleared`() = runTest {
        val vm = createViewModel()
        assertEquals(1, prefs.listenerCount())

        vm.loadPreferences(intArrayOf(R.string.title_settings), DeviceType.XR, isServerAuthenticated = false)
        assertEquals("", vm.awaitContaining(R.string.settings_category_servers).companionSyncStatus)

        appPreferences.setValue(appPreferences.companionUrl, "http://192.168.1.50:1982")
        val updated = vm.state.first { it.companionSyncStatus == "http://192.168.1.50:1982" }
        assertEquals("http://192.168.1.50:1982", updated.companionSyncStatus)

        val onClearedMethod = vm.javaClass.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)
        assertEquals(0, prefs.listenerCount())
    }

    @Test
    fun `AppPreferences isolates 15 xrPlayer keys and enforces non-main write guard`() {
        val xrProcessPrefs = AppPreferences(
            sharedPreferences = prefs,
            xrPlayerSharedPreferences = xrPrefs,
            isMainProcess = false,
            isDebuggable = true,
        )

        // Writing a routed xrPlayer key succeeds and lands in xrPrefs (after flush), not main prefs
        xrProcessPrefs.setValue(xrProcessPrefs.xrPlayerPassthroughOpacity, 0.75f)
        assertTrue(xrProcessPrefs.flushXrPlayerPrefsSync(1_000L))
        assertEquals(0.75f, xrPrefs.getFloat(xrProcessPrefs.xrPlayerPassthroughOpacity.backendName, 0f), 0.001f)
        assertFalse(prefs.contains(xrProcessPrefs.xrPlayerPassthroughOpacity.backendName))
        assertTrue(xrProcessPrefs.contains(xrProcessPrefs.xrPlayerPassthroughOpacity))

        // Writing a main-process preference in debuggable non-main process throws IllegalStateException
        var threw = false
        try {
            xrProcessPrefs.setValue(xrProcessPrefs.xrAppPanelScale, 1.5f)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected IllegalStateException for main-pref write from :xrplayer", threw)

        // Default App Lock policy values are (false, 3)
        assertFalse(appPreferences.getValue(appPreferences.appLockWipeOnFail))
        assertEquals(3, appPreferences.getValue(appPreferences.appLockMaxAttempts))
    }
}
