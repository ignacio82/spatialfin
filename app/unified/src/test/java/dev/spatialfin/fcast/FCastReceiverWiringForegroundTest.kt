package dev.spatialfin.fcast

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import dev.jdtech.jellyfin.fcast.receiver.FCastReceiverService
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.spatialfin.unified.DeviceClass
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FCastReceiverWiringForegroundTest {

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
        sharedPrefs = app.getSharedPreferences("fcast_wiring_fg_test_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()
        appPreferences = AppPreferences(sharedPrefs)
        FCastReceiverWiring.resetAuthRevokedForTests()
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        FCastReceiverWiring.resetAuthRevokedForTests()
        sharedPrefs.edit().clear().commit()
    }

    @Test
    fun installOnAppStartDefersForegroundServiceUntilActivityResumes() {
        appPreferences.setValue(appPreferences.fcastReceiverEnabled, true)
        val lockObserver = FakeLockObserver(AppLockManager.LockState.UNLOCKED)
        var inForeground = false

        FCastReceiverWiring.installOnAppStart(
            context = app,
            prefs = appPreferences,
            deviceClass = DeviceClass.PHONE,
            baseHttpClient = OkHttpClient(),
            appLockObserver = lockObserver,
            isForegroundProvider = { inForeground },
        )
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(FCastReceiverWiring.tryStartReceiver(app, appPreferences))
        assertNull(shadowOf(app).nextStartedService)

        inForeground = true
        Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val started = shadowOf(app).nextStartedService
        assertEquals(FCastReceiverService::class.java.name, started?.component?.className)
    }

    @Test
    fun mergedManifestRegistersNoSpatialFinBootCompletedReceiver() {
        val receivers =
            app.packageManager.queryBroadcastReceivers(
                Intent(Intent.ACTION_BOOT_COMPLETED),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            )
        val spatialFinBootReceivers =
            receivers.mapNotNull { it.activityInfo?.name }.filter { name ->
                name.startsWith("dev.spatialfin.") || name.startsWith("dev.jdtech.")
            }
        assertEquals(emptyList<String>(), spatialFinBootReceivers)
    }
}
