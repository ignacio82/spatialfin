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
import dev.jdtech.jellyfin.models.SpatialFinMovieDto
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.offline.ServerConnectionMonitor
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.watchnext.WatchNextScheduler
import dev.jdtech.jellyfin.work.CompanionUserExtrasApplier
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Job
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
        assertEquals("{}", prefs.getValue(prefs.pendingServerIdMigrationsJson))
    }

    @Test
    fun `ensureInitialized recovers interrupted srv- shell migration using pendingServerIdMigrationsJson`() = runBlocking {
        val canonicalId = "canonical-srv-recovered"
        val shellId = "srv-crashed-shell"
        val addrId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        dao.insertServerIgnore(Server(canonicalId, "Recovered JF", addrId, userId))
        dao.insertServerAddress(ServerAddress(addrId, canonicalId, "http://192.168.1.90:8096/"))
        dao.insertUserIgnore(User(userId, "Alice", canonicalId, "tok-recovered"))
        prefs.setValue(prefs.currentServer, shellId)
        prefs.setValue(prefs.pendingServerIdMigrationsJson, """{"$shellId":"$canonicalId"}""")

        val coordinator = buildCoordinator()
        val recovered = coordinator.ensureInitialized()

        assertEquals(SessionMode.SERVER_AUTHENTICATED, recovered.sessionMode)
        assertEquals(canonicalId, recovered.activeServerId)
        assertEquals(userId, recovered.activeUserId)
        assertEquals(canonicalId, prefs.getValue(prefs.currentServer))
        assertEquals("{}", prefs.getValue(prefs.pendingServerIdMigrationsJson))
    }

    @Test
    fun `awaitPendingServerDeleteCleanup returns promptly after bounded timeout when cleanup stalls`() = runBlocking {
        val coordinator = buildCoordinator()
        val stalledJob = Job()
        coordinator.pendingServerDeleteJobs["stalled-server"] = stalledJob
        val elapsedMs = measureTimeMillis {
            coordinator.awaitPendingServerDeleteCleanup("stalled-server", timeoutMs = 150L)
        }
        assertTrue("Expected bounded wait near 150ms, took ${elapsedMs}ms", elapsedMs < 2_000L)
        assertTrue("Stalled job should remain active and uncancelled by caller wait", stalledJob.isActive)
        stalledJob.cancel()
    }

    @Test
    fun `deleteServerAndNotify cleans up deleted movie images and trickplay directories without non-null DAO crash`() =
        runBlocking {
            val serverId = "srv-cleanup-1"
            val userId = UUID.randomUUID()
            val movieId = UUID.randomUUID()
            val coordinator = buildCoordinator()
            coordinator.ensureInitialized()
            val activation = coordinator.activateServer(
                serverId = serverId,
                serverName = "Cleanup Server",
                addressUrl = "http://192.168.1.50:8096/",
                user = User(id = userId, name = "Alice", serverId = serverId, accessToken = "tok-clean"),
                reason = SessionChangeReason.MANUAL_LOGIN,
            )
            assertTrue(activation is ActivationResult.Activated)

            dao.insertMovie(
                SpatialFinMovieDto(
                    id = movieId,
                    serverId = serverId,
                    name = "Test Movie",
                    originalTitle = null,
                    overview = "",
                    runtimeTicks = 0L,
                    premiereDate = null,
                    communityRating = null,
                    officialRating = null,
                    status = "Ended",
                    productionYear = 2025,
                    endDate = null,
                    chapters = null,
                ),
            )
            val imagesDir = File(app.filesDir, "images/$movieId").apply { mkdirs() }
            val imageFile = File(imagesDir, "poster.jpg").apply { writeText("img") }
            val trickplayDir = File(app.filesDir, "trickplay/$movieId").apply { mkdirs() }
            val trickplayFile = File(trickplayDir, "thumb.jpg").apply { writeText("tp") }

            coordinator.deleteServerAndNotify(serverId)
            coordinator.awaitPendingServerDeleteCleanup(serverId, timeoutMs = 5_000L)

            assertNull(dao.getMovieOrNull(movieId))
            assertFalse(imageFile.exists())
            assertFalse(imagesDir.exists())
            assertFalse(trickplayFile.exists())
            assertFalse(trickplayDir.exists())
        }
}
