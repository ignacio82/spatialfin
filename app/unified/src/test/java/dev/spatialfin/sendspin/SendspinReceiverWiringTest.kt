package dev.spatialfin.sendspin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Robolectric
import dev.jdtech.jellyfin.data.musicassistant.api.ServiceClient
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.plugins.bridge.HttpBridge
import dev.jdtech.jellyfin.plugins.repository.PluginContentRepository
import dev.jdtech.jellyfin.plugins.repository.PluginRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession
import dev.jdtech.jellyfin.sendspin.receiver.SendspinUserScope
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.work.MusicAssistantUserScope
import dev.spatialfin.companion.CompanionUserExtrasApplierImpl
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendspinReceiverWiringTest {

    private lateinit var app: Application
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var appPreferences: AppPreferences

    private class FakeLockObserver(
        initial: AppLockManager.LockState = AppLockManager.LockState.UNLOCKED,
    ) : AppLockObserver {
        val mutable = MutableStateFlow(initial)
        override val lockState: StateFlow<AppLockManager.LockState> = mutable
        override val enrollmentVersion: StateFlow<Long> = MutableStateFlow(0L)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        sharedPrefs = app.getSharedPreferences("sendspin_wiring_test_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()
        appPreferences = AppPreferences(sharedPrefs)
        SendspinReceiverWiring.resetForTests()
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        SendspinReceiverWiring.resetForTests()
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Disabled)
        sharedPrefs.edit().clear().commit()
        app.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun installOnAppStartDoesNotStartServiceWhenLockedOrDisabledScope() {
        appPreferences.setValue(appPreferences.fcastReceiverEnabled, true)
        val lockObserver = FakeLockObserver(AppLockManager.LockState.LOCKED)

        // Locked + Disabled scope -> must not start service
        SendspinReceiverWiring.installOnAppStart(app, appPreferences, OkHttpClient(), lockObserver)
        assertNull(shadowOf(app).nextStartedService)

        // Unlocked + Disabled scope -> still must not start service
        lockObserver.mutable.value = AppLockManager.LockState.UNLOCKED
        assertFalse(SendspinReceiverWiring.tryStartReceiver(app, appPreferences))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun applyReceiverConfigAndOnAppUnlockedRespectLockAndScope() {
        appPreferences.setValue(appPreferences.fcastReceiverEnabled, true)
        val lockObserver = FakeLockObserver(AppLockManager.LockState.LOCKED)
        SendspinReceiverWiring.installOnAppStart(app, appPreferences, OkHttpClient(), lockObserver)
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Standalone)
        shadowOf(app).clearStartedServices()

        // Locked -> applyReceiverConfig must not start
        SendspinReceiverWiring.applyReceiverConfig(app, appPreferences)
        assertNull(shadowOf(app).nextStartedService)

        // Unlock -> onAppUnlocked starts SendspinReceiverService
        lockObserver.mutable.value = AppLockManager.LockState.UNLOCKED
        SendspinReceiverWiring.onAppUnlocked(app, appPreferences)
        val started = shadowOf(app).nextStartedService
        assertEquals(SendspinReceiverService::class.java.name, started?.component?.className)
    }

    @Test
    fun refreshMusicAssistantHydratesHeadlesslyWhenInboundReceiverDisabled() {
        // Inbound receiver is disabled; headless MA hydration must still populate SendspinReceiverSession.
        appPreferences.setValue(appPreferences.fcastReceiverEnabled, false)
        val userId = "user-42"
        val maUrl = "http://192.168.1.50:8095"
        val maPrefs = app.getSharedPreferences("sendspin_music_assistant", Context.MODE_PRIVATE)
        maPrefs
            .edit()
            .putString("u:$userId/last_url", maUrl)
            .putString("u:$userId/token_url:$maUrl", "secret-token")
            .commit()

        val serviceClient = mockk<ServiceClient>(relaxed = true)
        val maSessionRepo = mockk<MaSessionRepository>(relaxed = true)
        val pluginRepo = mockk<PluginRepository>(relaxed = true)
        val httpBridge = mockk<HttpBridge>(relaxed = true)
        val pluginContentRepo = mockk<PluginContentRepository>(relaxed = true)
        val lockObserver = FakeLockObserver(AppLockManager.LockState.UNLOCKED)

        val applier =
            CompanionUserExtrasApplierImpl(
                context = app,
                pluginRepository = pluginRepo,
                okHttpClient = OkHttpClient(),
                appPreferences = appPreferences,
                appLockObserver = lockObserver,
                serviceClient = serviceClient,
                maSessionRepository = maSessionRepo,
                httpBridge = httpBridge,
                pluginContentRepository = pluginContentRepo,
            )

        applier.refreshMusicAssistant(MusicAssistantUserScope.Authenticated(userId))

        verify(atLeast = 1) { serviceClient.disconnectForRebind() }
        verify(atLeast = 1) { maSessionRepo.resetSession() }
        val state = SendspinReceiverSession.state.value
        assertEquals(SendspinUserScope.Authenticated(userId), state.activeUserScope)
        assertEquals("http://192.168.1.50:8095", state.musicAssistantServerUrl)
        assertEquals("secret-token", state.musicAssistantToken)
        assertEquals(SendspinMusicAssistantAuthState.AUTHENTICATED, state.musicAssistantAuthState)
        // Inbound receiver is disabled, so tryStartReceiver did not launch the foreground service.
        assertFalse(SendspinReceiverWiring.tryStartReceiver(app, appPreferences))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun installOnAppStartAndTryStartDeferForegroundServiceUntilActivityResumes() {
        appPreferences.setValue(appPreferences.fcastReceiverEnabled, true)
        SendspinReceiverSession.clearMusicAssistantState(SendspinUserScope.Standalone)
        val lockObserver = FakeLockObserver(AppLockManager.LockState.UNLOCKED)
        var inForeground = false

        SendspinReceiverWiring.installOnAppStart(
            context = app,
            prefs = appPreferences,
            baseHttpClient = OkHttpClient(),
            appLockObserver = lockObserver,
            isForegroundProvider = { inForeground },
        )
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(SendspinReceiverWiring.tryStartReceiver(app, appPreferences))
        assertNull(shadowOf(app).nextStartedService)

        inForeground = true
        Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val started = shadowOf(app).nextStartedService
        assertEquals(SendspinReceiverService::class.java.name, started?.component?.className)
    }
}
