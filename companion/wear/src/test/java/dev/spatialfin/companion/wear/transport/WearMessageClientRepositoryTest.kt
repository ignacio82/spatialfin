package dev.spatialfin.companion.wear.transport

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import dev.spatialfin.companion.protocol.WearCommandRequest
import dev.spatialfin.companion.protocol.WearCommandResponse
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearProtocolCodec
import dev.spatialfin.companion.protocol.WearProtocolPaths
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.async
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WearMessageClientRepositoryTest {
    private val messages = mockk<MessageClient>()
    private val listener = slot<MessageClient.OnMessageReceivedListener>()
    private val payload = slot<ByteArray>()
    private val context = mockk<Context>()
    private val capabilities = mockk<CapabilityClient>()
    private val capability = mockk<CapabilityInfo>()

    @Before
    fun setUp() {
        mockkStatic(Wearable::class)
        every { Wearable.getCapabilityClient(context) } returns capabilities
        every { Wearable.getMessageClient(context) } returns messages
        every { messages.addListener(capture(listener)) } returns Tasks.forResult(null)
        every { messages.removeListener(any()) } returns Tasks.forResult(true)
        every {
            messages.sendMessage(
                "chosen-phone",
                WearProtocolPaths.PATH_COMMAND_REQUEST,
                capture(payload),
            )
        } returns Tasks.forResult(1)
        every {
            capabilities.getCapability(
                WearProtocolPaths.CAPABILITY_HOST,
                CapabilityClient.FILTER_REACHABLE,
            )
        } returns Tasks.forResult(capability)
    }

    @After fun tearDown() = unmockkAll()

    @Test
    fun `a paired phone without the SpatialFin capability is not a host`() = runTest {
        every { capability.nodes } returns emptySet()
        assertNull(WearMessageClientRepository(context).getConnectedHostNode())
        verify(exactly = 0) { Wearable.getNodeClient(any<Context>()) }
    }

    @Test
    fun `a nearby SpatialFin host is preferred among capable nodes`() = runTest {
        val distant = mockk<Node> { every { isNearby } returns false }
        val nearby = mockk<Node> { every { isNearby } returns true }
        every { capability.nodes } returns linkedSetOf(distant, nearby)
        assertEquals(nearby, WearMessageClientRepository(context).getConnectedHostNode())
    }

    @Test
    fun `delivery alone is not success and only matching target and request can acknowledge`() =
        runTest {
            val result = async {
                WearMessageClientRepository(context)
                    .sendAction(WearPlayerAction.Pause, "chosen-phone")
            }
            runCurrent()
            assertFalse(result.isCompleted)
            val request =
                WearProtocolCodec.json.decodeFromString(
                    WearCommandRequest.serializer(),
                    payload.captured.decodeToString(),
                )
            respond("other-device", request.requestId)
            respond("chosen-phone", "unrelated-request")
            runCurrent()
            assertFalse(result.isCompleted)
            respond("chosen-phone", request.requestId)
            assertEquals("Paused on phone", result.await().getOrThrow())
            verify { messages.removeListener(listener.captured) }
        }

    @Test
    fun `host execution error is returned as failure`() = runTest {
        val result = async {
            WearMessageClientRepository(context).sendAction(WearPlayerAction.Pause, "chosen-phone")
        }
        runCurrent()
        val request =
            WearProtocolCodec.json.decodeFromString(
                WearCommandRequest.serializer(),
                payload.captured.decodeToString(),
            )
        respond("chosen-phone", request.requestId, false, "Nothing is playing")
        assertEquals("Nothing is playing", result.await().exceptionOrNull()?.message)
    }

    @Test
    fun `old or disconnected host times out and removes listener`() = runTest {
        val result = async {
            WearMessageClientRepository(context).sendAction(WearPlayerAction.Pause, "chosen-phone")
        }
        runCurrent()
        advanceTimeBy(15_001)
        assertTrue(result.await().isFailure)
        verify { messages.removeListener(listener.captured) }
    }

    @Test
    fun `leaving voice cancels pending acknowledgement and removes listener`() = runTest {
        val result = async {
            WearMessageClientRepository(context).sendVoice("pause", "chosen-phone")
        }
        runCurrent()
        result.cancel()
        runCurrent()
        verify { messages.removeListener(listener.captured) }
    }

    @Test
    fun `unresponsive phone capability lookup cannot block server discovery indefinitely`() =
        runTest {
            every { capabilities.getCapability(any<String>(), any()) } returns
                com.google.android.gms.tasks.TaskCompletionSource<CapabilityInfo>().task
            val node = async { WearMessageClientRepository(context).getConnectedHostNode() }
            runCurrent()
            advanceTimeBy(1_501)
            assertNull(node.await())
        }

    private fun respond(
        node: String,
        id: String,
        success: Boolean = true,
        text: String = "Paused on phone",
    ) {
        val bytes =
            WearProtocolCodec.json
                .encodeToString(
                    WearCommandResponse.serializer(),
                    WearCommandResponse(id, text, success),
                )
                .encodeToByteArray()
        val event =
            mockk<MessageEvent> {
                every { sourceNodeId } returns node
                every { path } returns WearProtocolPaths.PATH_ACTION_RESPONSE
                every { data } returns bytes
            }
        listener.captured.onMessageReceived(event)
    }
}
