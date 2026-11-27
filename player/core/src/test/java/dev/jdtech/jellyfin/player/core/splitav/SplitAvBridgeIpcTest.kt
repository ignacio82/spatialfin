package dev.jdtech.jellyfin.player.core.splitav

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SplitAvBridgeIpcTest {

    @After
    fun tearDown() {
        SplitAvTheaterBridge.setOutboundEventForwarder(null)
        SplitAvTheaterBridge.updateSnapshot(null)
    }

    @Test
    fun `RoomGeometryContract and TheaterAudioTelemetry JSON round-trip preserves nullables`() {
        val geometry = RoomGeometryContract(
            roomProfileVersion = 4,
            roomName = "Living Room Theater",
            layout = "7.1.4",
            lcrScreenPlane = TheaterScreenPlaneContract(
                centerM = listOf(0.0, 0.05, -2.85),
                widthM = 2.4,
                heightM = 1.35,
                normalVec = listOf(0.0, 0.0, 1.0),
            ),
            speakers = listOf(
                TheaterSpeakerContract(
                    role = "FL",
                    clientId = "pi-front-dac",
                    channelIndex = 0,
                    transportTier = "wired_front_multich",
                    coordM = listOf(-1.25, 0.0, -2.80),
                ),
            ),
        )
        val decodedGeometry = RoomGeometryContract.fromJson(JSONObject(geometry.toJson().toString()))
        assertEquals(geometry, decodedGeometry)

        val telemetry = TheaterAudioTelemetry(
            sessionId = "sess-1",
            state = "playing",
            driftUs = -1250L,
            resamplerPpm = -18.4,
            clockUncertaintyUs = 450L,
            tPlayLaunchErrorUs = 320L,
            dspGroupDelayUs = 18500L,
            codecActive = "truehd",
            rendererMode = "truehd_vbap",
            activeDecodeChain = "truehd+vbap",
            eqBypass = true,
            degradedSpeaker = null,
            fallbackActive = false,
            fallbackReason = null,
        )
        val decodedTelemetry = TheaterAudioTelemetry.fromJson(JSONObject(telemetry.toJson().toString()))
        assertEquals(telemetry, decodedTelemetry)
        assertNull(decodedTelemetry.degradedSpeaker)
    }

    @Test
    fun `TheaterSessionSnapshot and TheaterClientEvent JSON round-trips match across types`() {
        val snapshot = TheaterSessionSnapshot(
            active = true,
            serverId = "avr-550e8400",
            serverName = "Living Room",
            sessionId = "sess-42",
            masterVolume = 0.72,
            muted = false,
            eqBypass = true,
            upmixMode = "dts_height_upmix",
            maZoneState = "paused_for_cinema",
            lcrScreenLockEnabled = false,
            anchorUuid = "uuid-wall-1",
            anchorToMlpTransform = listOf(1f, 0f, -2.5f, 0.1f, 0f, 0f, 1f),
            roomGeometry = RoomGeometryContract(
                roomProfileVersion = 2,
                roomName = "Den",
                layout = "5.1",
            ),
            telemetry = TheaterAudioTelemetry(sessionId = "sess-42", state = "playing", driftUs = 800L),
            banner = TheaterHudBanner(
                code = "session_busy",
                message = "Take over?",
                retryable = true,
                requiresTakeoverConfirm = true,
            ),
            lastToast = "Speaker SR degraded",
        )
        val decodedSnapshot = TheaterSessionSnapshot.fromJsonString(snapshot.toJsonString())
        assertEquals(snapshot, decodedSnapshot)

        val events = listOf(
            TheaterClientEvent.BufferingStall(barrierSeq = 3L, stalled = true),
            TheaterClientEvent.AudioTrackChanged(mediaStreamIndex = 2, codec = "truehd"),
            TheaterClientEvent.MediaItemChanged(itemId = "ep-9", mediaSourceId = "src-9", audioStreamIndex = 1),
            TheaterClientEvent.SetVolume(volume = 0.5, muted = false),
            TheaterClientEvent.SetMuted(muted = true),
            TheaterClientEvent.SetEqBypass(bypass = true),
            TheaterClientEvent.SetUpmixMode(mode = "native"),
            TheaterClientEvent.SetLcrScreenLockPref(enabled = false),
            TheaterClientEvent.CenterSnap(durationMs = 200, gainDbfs = -18.0),
            TheaterClientEvent.ConfirmTakeover(confirm = true),
            TheaterClientEvent.ReconnectRequested,
        )
        events.forEach { event ->
            assertEquals(event, TheaterClientEvent.fromJsonString(event.toJsonString()))
        }
    }

    @Test
    fun `SplitAvVideoTiming preserves ms and us fidelity`() {
        val timing = SplitAvVideoTiming(
            mediaPositionMs = 1234L,
            releaseMonotonicMs = 5678L,
            sampledMonotonicMs = 5670L,
            mediaPositionUs = 1_234_567L,
            releaseBoottimeUs = 5_678_901L,
        )
        assertEquals(1234L, timing.mediaPositionMs)
        assertEquals(1_234_567L, timing.mediaPositionUs)
        assertEquals(5_678_901L, timing.releaseBoottimeUs)
    }

    @Test
    fun `ProxyVideoMaster dispatches T_play messages and routes inbound XR events`() = runBlocking {
        val handledMessages = mutableListOf<Message>()
        val clientMessenger = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                handledMessages += Message.obtain(msg)
                true
            },
        )
        val proxy = ProxyVideoMaster()
        proxy.attachClient(clientMessenger)

        proxy.prepareAndHoldAt(barrierSeq = 7L, targetPositionMs = 45_000L)
        proxy.startAtBoottimeUs(barrierSeq = 7L, tPlayClientUs = 9_123_456L)
        val sentSnapshot = TheaterSessionSnapshot(active = true, sessionId = "s-7", masterVolume = 0.6)
        proxy.sendTheaterSnapshot(sentSnapshot)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(3, handledMessages.size)
        assertEquals(SplitAvBridgeIpcMessage.MSG_PREPARE_HOLD, handledMessages[0].what)
        assertEquals(7L, handledMessages[0].data.getLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ))
        assertEquals(45_000L, handledMessages[0].data.getLong(SplitAvBridgeIpcMessage.KEY_POSITION_MS))

        assertEquals(SplitAvBridgeIpcMessage.MSG_START_AT_BOOTTIME_US, handledMessages[1].what)
        assertEquals(7L, handledMessages[1].data.getLong(SplitAvBridgeIpcMessage.KEY_BARRIER_SEQ))
        assertEquals(9_123_456L, handledMessages[1].data.getLong(SplitAvBridgeIpcMessage.KEY_T_PLAY_CLIENT_US))

        assertEquals(SplitAvBridgeIpcMessage.MSG_THEATER_SNAPSHOT, handledMessages[2].what)
        val payloadStr = handledMessages[2].data.getString(SplitAvBridgeIpcMessage.KEY_JSON_PAYLOAD)
        assertNotNull(payloadStr)
        assertEquals(sentSnapshot, TheaterSessionSnapshot.fromJsonString(payloadStr!!))

        val readyFuture = async(start = CoroutineStart.UNDISPATCHED) { proxy.firstFrameReady.take(1).toList() }
        proxy.applyFirstFrameReady(barrierSeq = 7L, actualPtsUs = 45_001_200L)
        val readyList = withTimeout(1_000L) { readyFuture.await() }
        assertEquals(SplitAvFirstFrameReady(7L, 45_001_200L), readyList.single())

        val trackFuture = async(start = CoroutineStart.UNDISPATCHED) { proxy.trackSwitches.take(1).toList() }
        val bridgeEventsFuture = async(start = CoroutineStart.UNDISPATCHED) {
            SplitAvTheaterBridge.clientEvents.take(2).toList()
        }
        proxy.applyTheaterEvent(TheaterClientEvent.AudioTrackChanged(mediaStreamIndex = 3, codec = "dts-hd"))
        proxy.applyTheaterEvent(TheaterClientEvent.BufferingStall(barrierSeq = 7L, stalled = true))

        assertTrue(proxy.isBufferingStalled.value)
        assertEquals(SplitAvTrackSwitchRequest(3, "dts-hd"), withTimeout(1_000L) { trackFuture.await() }.single())
        assertEquals(2, withTimeout(1_000L) { bridgeEventsFuture.await() }.size)
    }
}
