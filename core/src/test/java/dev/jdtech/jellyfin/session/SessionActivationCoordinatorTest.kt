package dev.jdtech.jellyfin.session

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Lazy
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.core.util.XrPlayerSessionCoordinator
import dev.jdtech.jellyfin.database.ServerDatabase
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.downloads.ActiveDownloadRegistry
import dev.jdtech.jellyfin.downloads.DownloadStorageManager
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import dev.jdtech.jellyfin.work.CompanionUserExtrasApplier
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionActivationCoordinatorTest {

    private lateinit var app: Application
    private lateinit var db: ServerDatabase
    private lateinit var dao: ServerDatabaseDao
    private lateinit var prefs: AppPreferences
    private lateinit var jellyfinApi: JellyfinApi
    private val lockStateFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
    private val enrollmentVersionFlow = MutableStateFlow(0L)
    private val appLockObserver = object : AppLockObserver {
        override val lockState = lockStateFlow
        override val enrollmentVersion = enrollmentVersionFlow
    }

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, "test-android-id-sac")
        db = Room.inMemoryDatabaseBuilder(app, ServerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.getServerDatabaseDao()
        val sp = app.getSharedPreferences("sac_test_main", Context.MODE_PRIVATE)
        val xrSp = app.getSharedPreferences("sac_test_xr", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        xrSp.edit().clear().commit()
        prefs = AppPreferences(sp, xrSp, isMainProcess = true, isDebuggable = true)
        jellyfinApi = JellyfinApi(app, allowLoopbackForTests = true)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun buildCoordinator(): SessionActivationCoordinator {
        val storage = DownloadStorageManager(app, dao, isMainProcess = true)
        val xrCoord = mockk<XrPlayerSessionCoordinator>(relaxed = true)
        return SessionActivationCoordinator(
            context = app,
            database = dao,
            appPreferences = prefs,
            jellyfinApi = jellyfinApi,
            activeSessionBus = ActiveSessionBus(),
            extrasApplier = Lazy { mockk<CompanionUserExtrasApplier>(relaxed = true) },
            serverConnectionMonitor = Lazy { mockk<ServerConnectionMonitor>(relaxed = true) },
            downloader = Lazy { mockk<Downloader>(relaxed = true) },
            downloadStorageManager = Lazy { storage },
            activeDownloadRegistry = ActiveDownloadRegistry(),
            watchNextScheduler = Lazy { mockk<WatchNextScheduler>(relaxed = true) },
            appLockObserver = appLockObserver,
            workManager = Lazy { mockk<WorkManager>(relaxed = true) },
            xrPlayerSessionCoordinator = Lazy { xrCoord },
            companionLiveSyncClient = Lazy { mockk<CompanionLiveSyncClient>(relaxed = true) },
        )
    }

    @Test
    fun `activateServer, logout, standalone, and deleteServer transition SessionState and epochs atomically`() = runBlocking {
        val coordinator = buildCoordinator()
        val initial = coordinator.ensureInitialized()
        assertEquals(SessionMode.UNCONFIGURED, initial.sessionMode)
        assertFalse(initial.isLoading)

        val userId = UUID.randomUUID()
        val activation = coordinator.activateServer(
            serverId = "canonical-srv-1",
            serverName = "Living Room",
            addressUrl = "http://192.168.1.50:8096/",
            user = User(id = userId, name = "Alice", serverId = "canonical-srv-1", accessToken = "tok-123"),
            reason = SessionChangeReason.MANUAL_LOGIN,
        )
        assertTrue("Expected Activated, got $activation", activation is ActivationResult.Activated)
        val authed = coordinator.sessionState.value
        assertEquals(SessionMode.SERVER_AUTHENTICATED, authed.sessionMode)
        assertEquals("canonical-srv-1", authed.activeServerId)
        assertEquals(userId, authed.activeUserId)
        assertNull("SessionState.activeUser must strip accessToken", authed.activeUser?.accessToken)
        assertEquals("tok-123", jellyfinApi.api.accessToken)
        assertEquals(userId, jellyfinApi.userId)
        assertTrue(authed.activationEpoch >= 1L)

        // Commit background sync with wrong epoch -> EpochMismatch
        val mismatch = coordinator.commitLockedBackgroundSync(
            serverUpdates = emptyList(),
            expectedSessionEpoch = authed.sessionEpoch - 1L,
            companionBaseline = emptyMap(),
            deviceOverrodeSeerrUrl = false,
            shares = emptyList(),
            reason = SessionChangeReason.COMPANION_BACKGROUND_SYNC,
        )
        assertEquals(BackgroundSyncResult.EpochMismatch, mismatch)

        // Logout clears token, preserves server, transitions to SERVER_NEEDS_AUTH
        coordinator.logout()
        val loggedOut = coordinator.sessionState.value
        assertEquals(SessionMode.SERVER_NEEDS_AUTH, loggedOut.sessionMode)
        assertNull(jellyfinApi.api.accessToken)
        assertNull(dao.getUser(userId)?.accessToken)

        // Delete server transitions to UNCONFIGURED
        coordinator.deleteServerAndNotify("canonical-srv-1")
        val afterDelete = coordinator.sessionState.value
        assertEquals(SessionMode.UNCONFIGURED, afterDelete.sessionMode)
        assertFalse(afterDelete.hasServers)

        // Activate standalone -> STANDALONE_SOURCES
        coordinator.activateStandalone(SessionChangeReason.STANDALONE_ONBOARDING)
        val standalone = coordinator.sessionState.value
        assertEquals(SessionMode.STANDALONE_SOURCES, standalone.sessionMode)
        assertTrue(standalone.standaloneModeChosen)
    }

    @Test
    fun `activateServer merges legacy srv- shell into canonical serverId and migrates addresses`() = runBlocking {
        val shellId = "srv-legacy-1"
        val legacyAddrId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        dao.insertServerIgnore(Server(shellId, "Legacy", legacyAddrId, userId))
        dao.insertServerAddress(ServerAddress(legacyAddrId, shellId, "http://192.168.1.50:8096"))
        dao.insertUserIgnore(User(userId, "Alice", shellId, "old-tok"))
        prefs.setValue(prefs.currentServer, shellId)

        val coordinator = buildCoordinator()
        coordinator.ensureInitialized()

        val result = coordinator.activateServer(
            serverId = "real-canonical-id",
            serverName = "Home Jellyfin",
            addressUrl = "http://192.168.1.50:8096/",
            legacyServerIds = listOf(shellId),
            user = User(id = userId, name = "Alice", serverId = "real-canonical-id", accessToken = "new-tok"),
            reason = SessionChangeReason.QUICK_CONNECT,
        )
        assertTrue(result is ActivationResult.Activated)
        assertNull("Legacy shell must be deleted after merge", dao.get(shellId))
        assertEquals("real-canonical-id", dao.get("real-canonical-id")?.id)
        assertEquals("new-tok", dao.getUser(userId)?.accessToken)
        assertEquals("real-canonical-id", prefs.getValue(prefs.currentServer))
    }
}
