package dev.jdtech.jellyfin.work

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.work.WorkManager
import dagger.Lazy
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.companion.CompanionConfigImporter
import dev.jdtech.jellyfin.companion.CompanionImportOutcome
import dev.jdtech.jellyfin.companion.CompanionLiveSyncClient
import dev.jdtech.jellyfin.companion.CompanionQuickConnectBroker
import dev.jdtech.jellyfin.companion.ImportMode
import dev.jdtech.jellyfin.companion.SelectedCompanionUser
import dev.jdtech.jellyfin.core.util.CompanionLogUploader
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.ServerWithUsers
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.companion.CompanionConfig
import dev.jdtech.jellyfin.models.companion.CompanionEndpoint
import dev.jdtech.jellyfin.models.companion.CompanionTvPairingEnvelope
import dev.jdtech.jellyfin.models.companion.DeviceIdentity
import dev.jdtech.jellyfin.models.normalizeJellyfinId
import dev.jdtech.jellyfin.models.parseJellyfinUuid
import dev.jdtech.jellyfin.network.PhysicalLanAddressResolver
import dev.jdtech.jellyfin.network.UrlTargetPolicy
import dev.jdtech.jellyfin.network.newBuilderWithSsrfGuard
import dev.jdtech.jellyfin.onboarding.OnboardingFailureCode
import dev.jdtech.jellyfin.quickconnect.QuickConnectClient
import dev.jdtech.jellyfin.repository.NetworkMediaRepository
import dev.jdtech.jellyfin.session.BackgroundServerSyncUpdate
import dev.jdtech.jellyfin.session.BackgroundSyncResult
import dev.jdtech.jellyfin.session.SessionActivationCoordinator
import dev.jdtech.jellyfin.session.SessionMode
import dev.jdtech.jellyfin.session.SessionState
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.applyCompanionPreference
import dev.jdtech.jellyfin.settings.domain.isSyncableCompanionPreferenceKey
import dev.spatialfin.unified.applock.AppLockManager
import dev.spatialfin.unified.applock.AppLockObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompanionTokenPreservationTest {

    private lateinit var app: Application
    private lateinit var prefs: AppPreferences
    private lateinit var testHttpClient: OkHttpClient

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, "test-android-id-1")
        val sp = app.getSharedPreferences("companion_test_prefs", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        prefs = AppPreferences(sp)
        testHttpClient =
            OkHttpClient()
                .newBuilderWithSsrfGuard(
                    policy = UrlTargetPolicy.LAN_ONLY,
                    allowLoopbackForTests = true,
                )
                .build()
        CompanionLogUploader.resetForTests()
    }

    @After
    fun tearDown() {
        CompanionLogUploader.resetForTests()
    }

    private fun buildImporter(
        dao: ServerDatabaseDao = mockk(relaxed = true),
        coordinator: SessionActivationCoordinator = mockk(relaxed = true),
        wm: WorkManager = mockk(relaxed = true),
        appLockObserver: AppLockObserver = AppLockObserver.NoOp,
    ): CompanionConfigImporter {
        val jellyfinApi = JellyfinApi(app, allowLoopbackForTests = true)
        val qcClient = QuickConnectClient(jellyfinApi)
        val broker = CompanionQuickConnectBroker(
            baseHttpClient = testHttpClient,
            quickConnectClient = qcClient,
        ).apply {
            allowLoopbackForTests = true
        }
        return CompanionConfigImporter(
            context = app,
            appPreferences = prefs,
            serverDatabase = dao,
            jellyfinApi = jellyfinApi,
            networkMediaRepository = mockk<NetworkMediaRepository>(relaxed = true),
            extrasApplier = mockk<CompanionUserExtrasApplier>(relaxed = true),
            sessionActivationCoordinator = coordinator,
            quickConnectBroker = broker,
            workManager = Lazy { wm },
            okHttpClient = testHttpClient,
            appLockObserver = appLockObserver,
        ).apply {
            allowLoopbackForTests = true
        }
    }

    @Test
    fun `parseJellyfinUuid and normalizeJellyfinId handle dashed and dashless UUIDs`() {
        val uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        assertEquals(uuid, parseJellyfinUuid("12345678-1234-5678-9abc-def012345678"))
        assertEquals(uuid, parseJellyfinUuid("12345678123456789abcdef012345678"))
        assertEquals(uuid, parseJellyfinUuid("  12345678-1234-5678-9ABC-DEF012345678 "))
        assertNull(parseJellyfinUuid("not-a-uuid"))
        assertNull(parseJellyfinUuid(""))
        assertEquals(
            "12345678123456789abcdef012345678",
            normalizeJellyfinId("12345678-1234-5678-9ABC-DEF012345678"),
        )
    }

    @Test
    fun `PhysicalLanAddressResolver classifies RFC1918, CGNAT, ULA, link-local, loopback, and public IPs`() {
        assertTrue(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("192.168.1.50"), includeVpn = false))
        assertTrue(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("10.0.0.12"), includeVpn = false))
        assertTrue(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("172.20.1.5"), includeVpn = false))
        assertTrue(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("fd12:3456:789a::1"), includeVpn = false))

        assertFalse(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("100.64.1.2"), includeVpn = false))
        assertTrue(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("100.64.1.2"), includeVpn = true))

        assertFalse(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("127.0.0.1"), includeVpn = true))
        assertFalse(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("169.254.1.1"), includeVpn = true))
        assertFalse(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("8.8.8.8"), includeVpn = true))
        assertFalse(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("2606:4700:4700::1111"), includeVpn = true))
        assertFalse(PhysicalLanAddressResolver.isAcceptedLanAddress(InetAddress.getByName("fe80::1"), includeVpn = true))

        assertTrue(PhysicalLanAddressResolver.resolvePhysicalLanAddresses(null, includeVpn = false).isEmpty())
    }

    @Test
    fun `CompanionPreferenceSync excludes local-only security keys and enforces local enrollment gate`() {
        assertFalse(isSyncableCompanionPreferenceKey(prefs.appLockMode.backendName))
        assertFalse(isSyncableCompanionPreferenceKey(prefs.appLockPinHash.backendName))
        assertFalse(isSyncableCompanionPreferenceKey(prefs.appLockPinSalt.backendName))
        assertFalse(isSyncableCompanionPreferenceKey(prefs.appLockPinKdfParams.backendName))
        assertFalse(isSyncableCompanionPreferenceKey(prefs.contentEncryptionEnabled.backendName))
        assertFalse(isSyncableCompanionPreferenceKey("home_continue_watching"))
        assertTrue(isSyncableCompanionPreferenceKey(prefs.appLockWipeOnFail.backendName))
        assertTrue(isSyncableCompanionPreferenceKey(prefs.appLockMaxAttempts.backendName))

        assertFalse(prefs.applyCompanionPreference(prefs.appLockWipeOnFail.backendName, "true"))
        assertFalse(prefs.applyCompanionPreference(prefs.appLockMaxAttempts.backendName, "3"))
        assertFalse(prefs.applyCompanionPreference(prefs.appLockMode.backendName, "pin"))
        assertFalse(prefs.applyCompanionPreference(prefs.contentEncryptionEnabled.backendName, "true"))

        prefs.setValue(prefs.appLockMode, "pin")
        prefs.setValue(prefs.appLockPinHash, "deadbeef")
        assertTrue(prefs.applyCompanionPreference(prefs.appLockWipeOnFail.backendName, "true"))
        assertTrue(prefs.applyCompanionPreference(prefs.appLockMaxAttempts.backendName, "3"))
        assertTrue(prefs.getValue(prefs.appLockWipeOnFail))
        assertEquals(3, prefs.getValue(prefs.appLockMaxAttempts))
        assertFalse(prefs.applyCompanionPreference(prefs.appLockMode.backendName, "off"))
        assertEquals("pin", prefs.getValue(prefs.appLockMode))
    }

    @Test
    fun `CompanionConfigImporter attaches X-Companion-Device and Capabilities headers and preserves local tokens during BACKGROUND_SYNC`() =
        runBlocking {
            val companionServer = MockWebServer()
            val jellyfinServer = MockWebServer()
            companionServer.start()
            jellyfinServer.start()
            try {
                val localUserId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                val signedOutUserId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                val unknownHouseholdUserId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
                val jfUrl = jellyfinServer.url("/").toString()

                val configJson =
                    """
                    {
                      "version": 1,
                      "setup_token": "sf-setup-0123456789abcdef0123456789abcdef",
                      "servers": [
                        {
                          "id": "srv-home",
                          "name": "Home Jellyfin",
                          "addresses": ["$jfUrl"],
                          "users": [
                            {"id": "$localUserId", "username": "alice", "access_token": "companion-token-a", "preferences": {}},
                            {"id": "$signedOutUserId", "username": "bob", "access_token": "companion-token-b", "preferences": {}},
                            {"id": "$unknownHouseholdUserId", "username": "carol", "access_token": "companion-token-c", "preferences": {}}
                          ]
                        }
                      ],
                      "globalPreferences": {"theme": "dark"},
                      "networkShares": [
                        {"id": "share-1", "protocol": "smb", "host": "192.168.1.50", "shareName": "Movies", "displayName": "NAS"}
                      ]
                    }
                    """.trimIndent()
                companionServer.enqueue(MockResponse(code = 200, body = configJson))
                jellyfinServer.enqueue(MockResponse(code = 200, body = """{"Id":"$localUserId","Name":"alice"}"""))

                val existingAlice = User(id = localUserId, name = "alice", serverId = "srv-home", accessToken = "local-token-alice")
                val existingBobSignedOut = User(id = signedOutUserId, name = "bob", serverId = "srv-home", accessToken = null)
                val existingServer = Server(id = "srv-home", name = "Home Jellyfin", currentServerAddressId = null, currentUserId = localUserId)

                val dao = mockk<ServerDatabaseDao>(relaxed = true)
                every { dao.getAllServersSync() } returns listOf(existingServer)
                every { dao.get("srv-home") } returns existingServer
                every { dao.getUser(localUserId) } returns existingAlice
                every { dao.getUser(signedOutUserId) } returns existingBobSignedOut
                every { dao.getServerWithUsers("srv-home") } returns ServerWithUsers(existingServer, listOf(existingAlice, existingBobSignedOut))
                every { dao.getServerAddresses("srv-home") } returns listOf(ServerAddress(UUID.randomUUID(), "srv-home", jfUrl))

                val session = SessionState(
                    isLoading = false,
                    sessionMode = SessionMode.SERVER_AUTHENTICATED,
                    sessionEpoch = 4L,
                    activeServerId = "srv-home",
                    activeServerAddress = jfUrl,
                    activeUserId = localUserId,
                )
                val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
                every { coordinator.sessionState } returns MutableStateFlow(session)
                every { coordinator.sessionEpoch } returns java.util.concurrent.atomic.AtomicLong(4L)
                coEvery { coordinator.ensureInitialized() } returns session

                val updatesSlot = slot<List<BackgroundServerSyncUpdate>>()
                coEvery {
                    coordinator.commitLockedBackgroundSync(
                        serverUpdates = capture(updatesSlot),
                        expectedSessionEpoch = any(),
                        companionBaseline = any(),
                        deviceOverrodeSeerrUrl = any(),
                        shares = any(),
                        reason = any(),
                    )
                } returns BackgroundSyncResult.Committed(5L)

                val importer = buildImporter(dao = dao, coordinator = coordinator)
                val endpoint =
                    CompanionEndpoint.parse(
                        rawBaseUrl = companionServer.url("/").toString(),
                        rawSetupToken = "sf-setup-0123456789abcdef0123456789abcdef",
                        allowLoopbackForTests = true,
                    )
                val outcome = importer.importFromEndpoint(endpoint, ImportMode.BACKGROUND_SYNC)
                assertTrue("Expected Activated, got $outcome", outcome is CompanionImportOutcome.Activated)

                val req = companionServer.takeRequest()
                assertEquals(DeviceIdentity.deviceId(app), req.headers["X-Companion-Device"])
                assertEquals(CompanionConfigImporter.CAPABILITY_QC_BROKER_V1, req.headers["X-SpatialFin-Capabilities"])
                assertNotNull(req.headers["X-Companion-Device-Name"])
                assertNotNull(req.headers["X-Companion-App-Version"])

                assertEquals(1, updatesSlot.captured.size)
                val syncedUsers = updatesSlot.captured.single().users
                // Alice preserved local token; Bob stayed signed out (null); Carol was NOT imported
                assertEquals(2, syncedUsers.size)
                assertEquals("local-token-alice", syncedUsers.first { it.user.id == localUserId }.user.accessToken)
                assertNull(syncedUsers.first { it.user.id == signedOutUserId }.user.accessToken)
                assertFalse(syncedUsers.any { it.user.id == unknownHouseholdUserId })
                coVerify(exactly = 1) {
                    coordinator.commitLockedBackgroundSync(
                        serverUpdates = any(),
                        expectedSessionEpoch = 4L,
                        companionBaseline = any(),
                        deviceOverrodeSeerrUrl = false,
                        shares = any(),
                        reason = any(),
                    )
                }
            } finally {
                companionServer.close()
                jellyfinServer.close()
            }
        }

    @Test
    fun `CompanionConfigImporter returns INVALID_CREDENTIALS on 401`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
                val importer = buildImporter()
                val endpoint =
                    CompanionEndpoint.parse(
                        rawBaseUrl = server.url("/").toString(),
                        rawSetupToken = "bad-token",
                        allowLoopbackForTests = true,
                    )
                val outcome = importer.importFromEndpoint(endpoint, ImportMode.BACKGROUND_SYNC)
                assertTrue("Expected Failed, got $outcome", outcome is CompanionImportOutcome.Failed)
                val failed = outcome as CompanionImportOutcome.Failed
                assertEquals(OnboardingFailureCode.AUTH_INVALID_CREDENTIALS, failed.code)
            } finally {
                server.close()
            }
        }

    @Test
    fun `CompanionLiveSyncClient filters targetDeviceId and defers while App Lock is LOCKED`() {
        prefs.setValue(prefs.companionUrl, "http://192.168.1.50:1982")
        prefs.setValue(prefs.companionToken, "sf-setup-0123456789abcdef0123456789abcdef")
        val wm = mockk<WorkManager>(relaxed = true)
        val lockStateFlow = MutableStateFlow(AppLockManager.LockState.UNLOCKED)
        val observer = object : AppLockObserver {
            override val lockState = lockStateFlow
            override val enrollmentVersion = MutableStateFlow(0L)
        }
        val liveSync =
            CompanionLiveSyncClient(
                appContext = app,
                appPreferences = prefs,
                baseHttpClient = testHttpClient,
                workManager = Lazy { wm },
                appLockObserver = observer,
            )
        val myId = DeviceIdentity.deviceId(app)

        val emittedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val collectJob =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
                liveSync.configChangedEvents.collect { emittedCount.incrementAndGet() }
            }
        try {
            liveSync.handleMessageText("""{"type":"config_changed","targetDeviceId":"other-device-xyz"}""")
            liveSync.handleMessageText("""{"type":"config_changed","deviceId":"other-device-xyz"}""")
            verify(exactly = 0) { wm.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) }
            assertEquals(0, emittedCount.get())

            liveSync.handleMessageText("""{"type":"config_changed","targetDeviceId":"$myId"}""")
            verify(exactly = 1) { wm.enqueueUniqueWork("CompanionSyncPush", any(), any<androidx.work.OneTimeWorkRequest>()) }
            assertEquals(1, emittedCount.get())

            liveSync.handleMessageText("""{"type":"config_changed","deviceId":"$myId"}""")
            verify(exactly = 2) { wm.enqueueUniqueWork("CompanionSyncPush", any(), any<androidx.work.OneTimeWorkRequest>()) }
            assertEquals(2, emittedCount.get())

            // While LOCKED, config_changed is deferred until onAppUnlocked()
            lockStateFlow.value = AppLockManager.LockState.LOCKED
            liveSync.handleMessageText("""{"type":"config_changed"}""")
            verify(exactly = 2) { wm.enqueueUniqueWork("CompanionSyncPush", any(), any<androidx.work.OneTimeWorkRequest>()) }
            assertEquals(2, emittedCount.get())

            lockStateFlow.value = AppLockManager.LockState.UNLOCKED
            liveSync.onAppUnlocked()
            verify(exactly = 3) { wm.enqueueUniqueWork("CompanionSyncPush", any(), any<androidx.work.OneTimeWorkRequest>()) }
            assertEquals(3, emittedCount.get())
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `CompanionLogUploader latches authRevoked on 401 and resets on onCompanionConfigChanged`() {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            prefs.setValue(prefs.loggingEnabled, true)
            prefs.setValue(prefs.companionUrl, baseUrl)
            prefs.setValue(prefs.companionToken, "sf-setup-0123456789abcdef0123456789abcdef")

            CompanionLogUploader.initialize(app, prefs, testHttpClient, AppLockObserver.NoOp)
            CompanionLogUploader.authRevoked = true
            CompanionLogUploader.flushNow()
            assertTrue(CompanionLogUploader.authRevoked)
            CompanionLogUploader.onCompanionConfigChanged()
            assertFalse(CompanionLogUploader.authRevoked)
        } finally {
            server.close()
        }
    }

    @Test
    fun `importFromEnvelope rejects non-private-LAN companion_url with SSRF_BLOCKED`() = runBlocking {
        val importer = buildImporter()
        val envelope =
            CompanionTvPairingEnvelope(
                version = 1,
                companion_url = "https://evil.example.com",
                setup_token = "sf-setup-0123456789abcdef0123456789abcdef",
                config = CompanionConfig(setup_token = "sf-setup-0123456789abcdef0123456789abcdef"),
            )
        val outcome = importer.importFromEnvelope(envelope)
        assertTrue("Expected Failed, got $outcome", outcome is CompanionImportOutcome.Failed)
        assertEquals(OnboardingFailureCode.URL_SSRF_BLOCKED, (outcome as CompanionImportOutcome.Failed).code)
    }

    @Test
    fun `PAIRING includes verified, needs_relink, and pending null-UUID users in NeedsUserSelection sorted verified-first`() =
        runBlocking {
            val companionServer = MockWebServer()
            val jellyfinServer = MockWebServer()
            companionServer.start()
            jellyfinServer.start()
            try {
                val ignacioId = UUID.fromString("455a45ec-bd63-43e2-853f-c2080b8ec489")
                val emilieId = UUID.fromString("08ca5a75-f757-417c-9899-608f579bd705")
                val danielleId = UUID.fromString("de9300da-e7d6-4c61-94ba-5261e9efba83")
                val jfUrl = jellyfinServer.url("/").toString()

                val configJson =
                    """
                    {
                      "version": 1,
                      "setup_token": "sf-setup-0123456789abcdef0123456789abcdef",
                      "servers": [
                        {
                          "id": "fd6ec3df44f74b4cb2d7cd721991de1b",
                          "name": "Jellyfin",
                          "addresses": ["$jfUrl"],
                          "users": [
                            {"id": null, "username": "spatialfin", "has_verified_token": false, "needs_relink": false},
                            {"id": "$emilieId", "username": "emilie", "has_verified_token": false, "needs_relink": true},
                            {"id": "$ignacioId", "username": "ignacio", "has_verified_token": true, "needs_relink": false},
                            {"id": "$danielleId", "username": "danielle", "has_verified_token": false, "needs_relink": true}
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                companionServer.enqueue(MockResponse(code = 200, body = configJson))

                val coordinator = mockk<SessionActivationCoordinator>(relaxed = true)
                coEvery {
                    coordinator.commitCompanionFallbackMetadata(
                        companionCredentials = any(),
                        companionBaseline = any(),
                        shares = any(),
                        server = any(),
                        addresses = any(),
                        legacyServerIds = any(),
                        deviceOverrodeSeerrUrl = any(),
                        handoffDeadlineElapsedMs = any(),
                    )
                } returns true
                val importer = buildImporter(coordinator = coordinator)
                val endpoint =
                    CompanionEndpoint.parse(
                        rawBaseUrl = companionServer.url("/").toString(),
                        rawSetupToken = "sf-setup-0123456789abcdef0123456789abcdef",
                        allowLoopbackForTests = true,
                    )

                val outcome = importer.importFromEndpoint(endpoint, ImportMode.PAIRING)
                assertTrue("Expected NeedsUserSelection, got $outcome", outcome is CompanionImportOutcome.NeedsUserSelection)
                val sel = outcome as CompanionImportOutcome.NeedsUserSelection
                assertEquals(listOf("ignacio", "spatialfin", "emilie", "danielle"), sel.candidates.map { it.username })
                assertTrue(sel.candidates[0].isVerified)
                assertFalse(sel.candidates[0].requiresCredentialUpdate)
                assertFalse(sel.candidates[1].isVerified)
                assertNull(sel.candidates[1].userId)
                assertTrue(sel.candidates[1].requiresCredentialUpdate)
                assertTrue(sel.candidates[2].needsRelink)
                assertTrue(sel.candidates[3].needsRelink)

                // Selecting needs_relink user or null-UUID user routes to BrokerFallbackToLogin(PAIRING_NO_VERIFIED_USER)
                jellyfinServer.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"Id":"fd6ec3df44f74b4cb2d7cd721991de1b","ServerName":"Jellyfin","ProductName":"Jellyfin Server"}""",
                    ),
                )
                val emilieFallback =
                    importer.completeUserSelection(
                        config = sel.config,
                        endpoint = sel.endpoint,
                        policy = sel.policy,
                        serverId = "fd6ec3df44f74b4cb2d7cd721991de1b",
                        userId = emilieId,
                        username = "emilie",
                    )
                assertTrue("Expected BrokerFallbackToLogin, got $emilieFallback", emilieFallback is CompanionImportOutcome.BrokerFallbackToLogin)
                val ef = emilieFallback as CompanionImportOutcome.BrokerFallbackToLogin
                assertEquals("emilie", ef.prefilledUsername)
                assertEquals(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, ef.failureCode)

                jellyfinServer.enqueue(
                    MockResponse(
                        code = 200,
                        body = """{"Id":"fd6ec3df44f74b4cb2d7cd721991de1b","ServerName":"Jellyfin","ProductName":"Jellyfin Server"}""",
                    ),
                )
                val spatialfinFallback =
                    importer.completeUserSelection(
                        config = sel.config,
                        endpoint = sel.endpoint,
                        policy = sel.policy,
                        serverId = "fd6ec3df44f74b4cb2d7cd721991de1b",
                        userId = null,
                        username = "spatialfin",
                    )
                assertTrue("Expected BrokerFallbackToLogin, got $spatialfinFallback", spatialfinFallback is CompanionImportOutcome.BrokerFallbackToLogin)
                val sf = spatialfinFallback as CompanionImportOutcome.BrokerFallbackToLogin
                assertEquals("spatialfin", sf.prefilledUsername)
                assertEquals(OnboardingFailureCode.PAIRING_NO_VERIFIED_USER, sf.failureCode)
            } finally {
                companionServer.close()
                jellyfinServer.close()
            }
        }

    @Test
    fun `fetchCompanionUsersForServer matches by canonical ID, legacy ID, or normalized address and returns empty on HTTP error`() =
        runBlocking {
            val companionServer = MockWebServer()
            companionServer.start()
            try {
                val ignacioId = UUID.fromString("455a45ec-bd63-43e2-853f-c2080b8ec489")
                val emilieId = UUID.fromString("08ca5a75-f757-417c-9899-608f579bd705")
                val body =
                    """
                    {
                      "version": 1,
                      "setup_token": "sf-setup-0123456789abcdef0123456789abcdef",
                      "servers": [
                        {
                          "id": "fd6ec3df44f74b4cb2d7cd721991de1b",
                          "legacy_ids": ["srv-legacy-1"],
                          "name": "Jellyfin",
                          "addresses": ["http://192.168.1.90:8096"],
                          "users": [
                            {"id": "$ignacioId", "username": "ignacio", "has_verified_token": true, "needs_relink": false},
                            {"id": "$emilieId", "username": "emilie", "has_verified_token": false, "needs_relink": true},
                            {"id": null, "username": "spatialfin", "has_verified_token": false, "needs_relink": false}
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                val endpoint =
                    CompanionEndpoint.parse(
                        rawBaseUrl = companionServer.url("/").toString(),
                        rawSetupToken = "sf-setup-0123456789abcdef0123456789abcdef",
                        allowLoopbackForTests = true,
                    )
                val importer = buildImporter()

                // 1. Canonical ID match (dashed vs dashless)
                companionServer.enqueue(MockResponse(code = 200, body = body))
                val byId = importer.fetchCompanionUsersForServer(
                    endpoint = endpoint,
                    serverId = "fd6ec3df-44f7-4b4c-b2d7-cd721991de1b",
                    serverAddress = "",
                )
                assertEquals(listOf("ignacio", "emilie", "spatialfin"), byId.map { it.username })

                // 2. Legacy ID match
                companionServer.enqueue(MockResponse(code = 200, body = body))
                val byLegacy = importer.fetchCompanionUsersForServer(
                    endpoint = endpoint,
                    serverId = "srv-legacy-1",
                    serverAddress = "",
                )
                assertEquals(listOf("ignacio", "emilie", "spatialfin"), byLegacy.map { it.username })

                // 3. Normalized address fallback match
                companionServer.enqueue(MockResponse(code = 200, body = body))
                val byAddr = importer.fetchCompanionUsersForServer(
                    endpoint = endpoint,
                    serverId = "other-id",
                    serverAddress = "http://192.168.1.90:8096/",
                )
                assertEquals(listOf("ignacio", "emilie", "spatialfin"), byAddr.map { it.username })

                // 4. Non-2xx returns emptyList cleanly
                companionServer.enqueue(MockResponse(code = 500, body = "err"))
                val onErr = importer.fetchCompanionUsersForServer(
                    endpoint = endpoint,
                    serverId = "fd6ec3df44f74b4cb2d7cd721991de1b",
                    serverAddress = "http://192.168.1.90:8096",
                )
                assertTrue(onErr.isEmpty())
            } finally {
                companionServer.close()
            }
        }
}
