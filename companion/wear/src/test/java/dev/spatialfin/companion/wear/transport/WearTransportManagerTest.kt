package dev.spatialfin.companion.wear.transport

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.spatialfin.companion.protocol.WearCredentials
import dev.spatialfin.companion.protocol.WearNowPlayingState
import dev.spatialfin.companion.protocol.WearPlayerAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearTransportManagerTest {
    private val context = mockk<Context>()
    private val capabilityClient = mockk<CapabilityClient>()
    private val capabilityListener = slot<CapabilityClient.OnCapabilityChangedListener>()
    private val data = mockk<WearDataClientRepository>(relaxed = true)
    private val messages = mockk<WearMessageClientRepository>()
    private val lan = mockk<WearDirectLanClient>()
    private val credentials = mockk<WearCredentialsStore>()
    private val relay = mockk<WearJellyfinRelayClient>()
    private val phoneState = MutableStateFlow<WearNowPlayingState?>(null)
    private val lanState = MutableStateFlow<WearNowPlayingState?>(null)
    private val receiver = MutableStateFlow<FCastReceiver?>(null)
    private val credentialState = MutableStateFlow<WearCredentials?>(null)
    private val phone =
        mockk<Node> {
            every { id } returns "phone-node"
            every { displayName } returns "Phone"
        }
    private val xr = RelaySession("xr-session", "xr", "Galaxy XR", "Movie", 30, 600, false)

    @Before
    fun setUp() {
        mockkStatic(Wearable::class)
        every { Wearable.getCapabilityClient(context) } returns capabilityClient
        every { capabilityClient.addListener(capture(capabilityListener), any<String>()) } returns
            mockk()
        every { data.nowPlayingState } returns phoneState
        every { data.coverArtBitmap } returns MutableStateFlow(null)
        every { data.vitalsState } returns MutableStateFlow(null)
        every { data.nextUpState } returns MutableStateFlow(null)
        every { lan.connectedReceiver } returns receiver
        every { lan.lanPlaybackState } returns lanState
        every { lan.disconnect() } answers { receiver.value = null }
        every { credentials.credentials } returns credentialState
        every { credentials.selectedDeviceId } returns null
        every { credentials.selectedDeviceId = any() } returns Unit
        coEvery { messages.getConnectedHostNode() } returns phone
        coEvery { relay.listControllableSessions() } returns listOf(xr)
        every { relay.nowPlayingFrom(any()) } answers
            {
                firstArg<RelaySession>().let {
                    WearNowPlayingState(
                        title = it.nowPlayingTitle.orEmpty(),
                        isPlaying = !it.isPaused,
                        positionSeconds = it.positionSeconds,
                        targetDeviceName = it.deviceName,
                    )
                }
            }
    }

    @After fun tearDown() = unmockkAll()

    private fun manager(scope: CoroutineScope) =
        WearTransportManager(
            context,
            data,
            messages,
            lan,
            credentials,
            relay,
            scope,
        )

    @Test
    fun `idle paired phone does not hide XR playback or receive its controls`() = runTest {
        phoneState.value = WearNowPlayingState()
        coEvery { relay.dispatch(xr.sessionId, WearPlayerAction.Pause) } returns
            Result.success("Paused")
        val manager = manager(backgroundScope)
        runCurrent()

        assertEquals(
            xr.sessionId,
            (manager.transportState.value as TransportState.ConnectedViaJellyfinRelay).sessionId,
        )
        assertEquals("Movie", manager.nowPlaying.value?.title)
        manager.dispatchAction(WearPlayerAction.Pause)
        coVerify(exactly = 1) { relay.dispatch("xr-session", WearPlayerAction.Pause) }
        coVerify(exactly = 0) { messages.sendAction(any(), any()) }

        // Re-announcing the phone capability must not steal the selected XR session.
        capabilityListener.captured.onCapabilityChanged(mockk())
        runCurrent()
        assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
    }

    @Test
    fun `paused playback on the paired host remains controllable through data layer`() = runTest {
        phoneState.value =
            WearNowPlayingState(
                title = "Paused movie",
                isPlaying = false,
                timestampEpochMs = System.currentTimeMillis(),
            )
        coEvery { messages.sendAction(WearPlayerAction.Play, "phone-node") } returns
            Result.success("Playing")
        val manager = manager(backgroundScope)
        runCurrent()

        assertEquals(
            TransportState.ConnectedViaDataLayer("phone-node", "Phone"),
            manager.transportState.value,
        )
        manager.dispatchAction(WearPlayerAction.Play)
        coVerify { messages.sendAction(WearPlayerAction.Play, "phone-node") }
        assertEquals(listOf(xr), manager.remoteSessions.value)
    }

    @Test
    fun `polling discovers a video started after opening the remote and tracks its end`() =
        runTest {
            coEvery { relay.listControllableSessions() } returns emptyList()
            val manager = manager(backgroundScope)
            manager.startPolling()
            runCurrent()
            assertNull(manager.nowPlaying.value)

            coEvery { relay.listControllableSessions() } returns listOf(xr)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("Movie", manager.nowPlaying.value?.title)

            coEvery { relay.listControllableSessions() } returns
                listOf(xr.copy(positionSeconds = 40, isPaused = true))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(40L, manager.nowPlaying.value?.positionSeconds)
            assertEquals(false, manager.nowPlaying.value?.isPlaying)

            coEvery { relay.listControllableSessions() } returns emptyList()
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(manager.isTargetAvailable)
            assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
        }

    @Test
    fun `polling also recovers from disconnected and stops when the remote closes`() = runTest {
        coEvery { messages.getConnectedHostNode() } returns null
        coEvery { relay.listControllableSessions() } returns emptyList()
        val manager = manager(backgroundScope)
        manager.startPolling()
        runCurrent()
        assertEquals(TransportState.Disconnected, manager.transportState.value)

        coEvery { relay.listControllableSessions() } returns listOf(xr)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("Movie", manager.nowPlaying.value?.title)

        manager.stopPolling()
        coEvery { relay.listControllableSessions() } returns emptyList()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Movie", manager.nowPlaying.value?.title)

        manager.startPolling()
        runCurrent()
        assertFalse(manager.isTargetAvailable)
        assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
    }

    @Test
    fun `new credentials trigger discovery without reopening the app`() = runTest {
        coEvery { relay.listControllableSessions() } answers
            {
                if (credentialState.value == null) emptyList() else listOf(xr)
            }
        val manager = manager(backgroundScope)
        runCurrent()
        assertNull(manager.nowPlaying.value)

        credentialState.value = WearCredentials("http://server", "token", "user", "phone", "server")
        runCurrent()
        assertEquals("Movie", manager.nowPlaying.value?.title)
    }

    @Test
    fun `explicit LAN selection takes precedence over a connected phone`() = runTest {
        receiver.value = FCastReceiver(host = "192.0.2.1", port = 46899, name = "Galaxy XR")
        lanState.value = WearNowPlayingState(title = "LAN movie")
        val manager = manager(backgroundScope)
        runCurrent()

        assertEquals(
            TransportState.ConnectedViaFCastLan("192.0.2.1", 46899, "Galaxy XR"),
            manager.transportState.value,
        )
        assertEquals("LAN movie", manager.nowPlaying.value?.title)
        assertEquals(listOf(xr), manager.remoteSessions.value)
    }

    @Test
    fun `polling preserves a selected relay session when server ordering changes`() = runTest {
        val manager = manager(backgroundScope)
        manager.startPolling()
        runCurrent()
        coEvery { relay.listControllableSessions() } returns
            listOf(
                xr.copy(sessionId = "other-session", deviceId = "tv", deviceName = "TV"),
                xr.copy(positionSeconds = 50),
            )
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
        assertEquals(50L, manager.nowPlaying.value?.positionSeconds)
    }

    @Test
    fun `choosing another client switches controls and survives a phone capability update`() =
        runTest {
            phoneState.value =
                WearNowPlayingState(
                    title = "Phone movie",
                    timestampEpochMs = System.currentTimeMillis(),
                )
            val tv =
                xr.copy(
                    sessionId = "tv-session",
                    deviceId = "tv",
                    deviceName = "TV",
                    nowPlayingTitle = "TV movie",
                )
            coEvery { relay.listControllableSessions() } returns listOf(xr, tv)
            coEvery { relay.dispatch("tv-session", WearPlayerAction.Pause) } returns
                Result.success("Paused")
            val manager = manager(backgroundScope)
            manager.startPolling()
            runCurrent()
            assertEquals(
                TransportState.ConnectedViaDataLayer("phone-node", "Phone"),
                manager.transportState.value,
            )
            assertEquals(listOf(xr, tv), manager.remoteSessions.value)

            manager.selectRemoteSession("tv-session")
            runCurrent()
            assertEquals("TV movie", manager.nowPlaying.value?.title)
            capabilityListener.captured.onCapabilityChanged(mockk())
            advanceTimeBy(5_000)
            runCurrent()
            manager.dispatchAction(WearPlayerAction.Pause)
            coVerify(exactly = 1) { relay.dispatch("tv-session", WearPlayerAction.Pause) }
            coVerify(exactly = 0) { messages.sendAction(any(), any()) }
        }

    @Test
    fun `choosing a Jellyfin client releases a previous LAN connection`() = runTest {
        receiver.value = FCastReceiver(host = "192.0.2.1", port = 46899, name = "LAN TV")
        val manager = manager(backgroundScope)
        runCurrent()
        manager.selectRemoteSession("xr-session")
        runCurrent()
        assertNull(receiver.value)
        assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
    }

    @Test
    fun `cached phone playback is not displayed when every transport is disconnected`() = runTest {
        phoneState.value = WearNowPlayingState(title = "Old movie")
        coEvery { messages.getConnectedHostNode() } returns null
        coEvery { relay.listControllableSessions() } returns emptyList()
        val manager = manager(backgroundScope)
        runCurrent()
        assertEquals(TransportState.Disconnected, manager.transportState.value)
        assertNull(manager.nowPlaying.value)
    }

    @Test
    fun `stale phone snapshot cannot hide a playing XR`() = runTest {
        phoneState.value =
            WearNowPlayingState(
                title = "Old phone movie",
                isPlaying = true,
                timestampEpochMs = System.currentTimeMillis() - 60_000,
            )
        val manager = manager(backgroundScope)
        runCurrent()
        assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
    }

    @Test
    fun `server failure preserves target and never redirects Pause to phone`() = runTest {
        val manager = manager(backgroundScope)
        runCurrent()
        coEvery { relay.listControllableSessions() } throws
            RelayDiscoveryException("Jellyfin sign-in expired")
        manager.checkConnectivity()
        runCurrent()
        assertTrue(manager.dispatchAction(WearPlayerAction.Pause).isFailure)
        assertEquals("Galaxy XR", manager.nowPlaying.value?.targetDeviceName)
        assertTrue(manager.connectionStatus.value.contains("expired"))
        coVerify(exactly = 0) { messages.sendAction(any(), any()) }
        coVerify(exactly = 0) { relay.dispatch(any(), any(), any()) }
    }

    @Test
    fun `selected device survives Jellyfin session restart`() = runTest {
        val manager = manager(backgroundScope)
        runCurrent()
        coEvery { relay.listControllableSessions() } returns
            listOf(xr.copy(sessionId = "restarted"))
        manager.checkConnectivity()
        runCurrent()
        assertEquals(
            "restarted",
            (manager.transportState.value as TransportState.ConnectedViaJellyfinRelay).sessionId,
        )
    }

    @Test
    fun `saved target remains selected when another player is first`() = runTest {
        every { credentials.selectedDeviceId } returns "xr"
        coEvery { relay.listControllableSessions() } returns
            listOf(xr.copy(deviceId = "tv", sessionId = "tv"), xr)
        val manager = manager(backgroundScope)
        runCurrent()
        assertEquals(
            "xr-session",
            (manager.transportState.value as TransportState.ConnectedViaJellyfinRelay).sessionId,
        )
    }

    @Test
    fun `account revocation clears relay state and does not retain old session`() = runTest {
        credentialState.value = WearCredentials("http://server", "token", "user", "phone", "server")
        val manager = manager(backgroundScope)
        runCurrent()
        coEvery { relay.listControllableSessions() } returns emptyList()
        credentialState.value = null
        runCurrent()
        assertNull(manager.nowPlaying.value)
        assertEquals(emptyList<RelaySession>(), manager.remoteSessions.value)
    }

    @Test
    fun `voice uses selected XR session instead of paired phone`() = runTest {
        coEvery { relay.listControllableSessions() } returns
            listOf(xr.copy(supportsWearCommands = true))
        coEvery { relay.dispatchVoice("xr-session", "pause") } returns Result.success("Sent")
        val manager = manager(backgroundScope)
        runCurrent()
        assertTrue(manager.dispatchVoice("pause").isSuccess)
        coVerify { relay.dispatchVoice("xr-session", "pause") }
        coVerify(exactly = 0) { messages.sendVoice(any(), any()) }
    }

    @Test
    fun `disconnected cast target cannot redirect controls to XR or phone`() = runTest {
        receiver.value = FCastReceiver(host = "192.0.2.1", port = 46899, name = "Cast TV")
        val manager = manager(backgroundScope)
        runCurrent()
        receiver.value = null
        runCurrent()
        assertFalse(manager.isTargetAvailable)
        assertTrue(manager.dispatchAction(WearPlayerAction.Pause).isFailure)
        coVerify(exactly = 0) { messages.sendAction(any(), any()) }
        coVerify(exactly = 0) { relay.dispatch(any(), any(), any()) }
    }
}
