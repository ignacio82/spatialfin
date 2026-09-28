package dev.spatialfin.companion.wear.transport

import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.VolumeUpdateMessage
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.FCastSenderClient
import dev.spatialfin.companion.protocol.WearPlayerAction
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WearDirectLanClientTest {
    private val receiver = FCastReceiver("192.0.2.1", 46899, "Cast TV")
    private val client = mockk<FCastSenderClient>(relaxed = true)
    private val state = MutableStateFlow(FCastSenderClient.State.Connected)
    private val playback = MutableSharedFlow<PlaybackUpdateMessage>(replay = 1)
    private val volume = MutableSharedFlow<VolumeUpdateMessage>(replay = 1)
    private val errors = MutableSharedFlow<String>()

    private fun configure() {
        every { client.state } returns state
        every { client.playbackUpdates } returns playback
        every { client.volumeUpdates } returns volume
        every { client.errors } returns errors
    }

    @Test
    fun `finished discovery can run again without manually stopping`() = runTest {
        var scans = 0
        val lan =
            WearDirectLanClient(
                backgroundScope,
                {
                    scans++
                    listOf(receiver)
                },
                { _, _ -> client },
            )
        lan.startDiscovery()
        runCurrent()
        lan.startDiscovery()
        runCurrent()
        assertEquals(2, scans)
        assertEquals(listOf(receiver), lan.discoveredReceivers.value)
    }

    @Test
    fun `socket disconnect clears target and cancels old collectors`() = runTest {
        configure()
        val lan = WearDirectLanClient(backgroundScope, { emptyList() }, { _, _ -> client })
        assertTrue(lan.connectToReceiver(receiver))
        runCurrent()
        playback.emit(PlaybackUpdateMessage(0, 1, time = 5.0))
        runCurrent()
        assertEquals(5L, lan.lanPlaybackState.value?.positionSeconds)
        state.value = FCastSenderClient.State.Disconnected
        runCurrent()
        assertNull(lan.connectedReceiver.value)
        assertNull(lan.lanPlaybackState.value)
        playback.emit(PlaybackUpdateMessage(0, 1, time = 99.0))
        runCurrent()
        assertNull(lan.lanPlaybackState.value)
        assertEquals(0, playback.subscriptionCount.value)
        assertEquals(0, volume.subscriptionCount.value)
        verify { client.close() }
    }

    @Test
    fun `cast playback controls use reported volume and reject unsupported actions`() = runTest {
        configure()
        val lan = WearDirectLanClient(backgroundScope, { emptyList() }, { _, _ -> client })
        lan.connectToReceiver(receiver)
        runCurrent()
        assertTrue(lan.dispatch(WearPlayerAction.Pause).isFailure)
        coVerify(exactly = 0) { client.pause() }
        playback.emit(PlaybackUpdateMessage(0, 1, time = 5.0))
        volume.emit(VolumeUpdateMessage(0, 0.5))
        runCurrent()
        assertEquals(0.5f, lan.lanPlaybackState.value?.volume)
        assertTrue(lan.dispatch(WearPlayerAction.AdjustVolume(delta = 0.25f)).isSuccess)
        coVerify { client.setVolume(0.75) }
        assertTrue(lan.dispatch(WearPlayerAction.ResetScreenPlacement).isFailure)
    }

    @Test
    fun `receiver error invalidates stale playback`() = runTest {
        configure()
        val lan = WearDirectLanClient(backgroundScope, { emptyList() }, { _, _ -> client })
        lan.connectToReceiver(receiver)
        runCurrent()
        errors.emit("Cannot decode stream")
        runCurrent()
        assertNull(lan.connectedReceiver.value)
        assertTrue(lan.dispatch(WearPlayerAction.Play).isFailure)
    }
}
