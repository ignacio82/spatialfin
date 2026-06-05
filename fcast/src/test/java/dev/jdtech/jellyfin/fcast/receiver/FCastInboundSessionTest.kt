package dev.jdtech.jellyfin.fcast.receiver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FCastInboundSessionTest {
    @Before fun clearBefore() = clearSession()
    @After fun clearAfter() = clearSession()

    @Test fun `pending seek and resume replay when replacement player binds`() {
        val old = RecordingPlayer()
        val replacement = RecordingPlayer()
        FCastInboundSession.bindControl(old)
        FCastInboundSession.suspendControlForReplacement()

        FCastInboundSession.seek(42.5)
        FCastInboundSession.resume()
        FCastInboundSession.bindControl(replacement)

        assertEquals(listOf("seek:42.5", "resume"), replacement.commands)
        assertEquals(listOf("stop"), old.commands)
        FCastInboundSession.unbindControl(replacement)
    }

    @Test fun `scheduled resume retains target until activity binds`() {
        val replacement = RecordingPlayer()
        FCastInboundSession.suspendControlForReplacement()

        FCastInboundSession.resumeAt(1234L)
        FCastInboundSession.bindControl(replacement)

        assertEquals(listOf("resumeAt:1234"), replacement.commands)
        FCastInboundSession.unbindControl(replacement)
    }

    @Test fun `pre-bind stop is replayed when activity binds`() {
        val replacement = RecordingPlayer()
        FCastInboundSession.suspendControlForReplacement()

        FCastInboundSession.stop()
        FCastInboundSession.bindControl(replacement)

        assertEquals(listOf("stop"), replacement.commands)
        FCastInboundSession.unbindControl(replacement)
    }

    @Test fun `new playback clears stale pre-bind stop`() {
        val replacement = RecordingPlayer()
        FCastInboundSession.suspendControlForReplacement()

        FCastInboundSession.stop()
        FCastInboundSession.prepareForNewPlayback()
        FCastInboundSession.bindControl(replacement)

        assertEquals(emptyList<String>(), replacement.commands)
        FCastInboundSession.unbindControl(replacement)
    }

    private fun clearSession() {
        FCastInboundSession.suspendControlForReplacement()
        FCastInboundSession.prepareForNewPlayback()
        val cleanup = RecordingPlayer()
        FCastInboundSession.bindControl(cleanup)
        FCastInboundSession.unbindControl(cleanup)
    }

    private class RecordingPlayer : ExternalStreamPlayer {
        val commands = mutableListOf<String>()
        override fun play(request: ExternalStreamRequest) = ExternalStreamPlayer.PlayResult.Accepted
        override fun pause() { commands += "pause" }
        override fun resume() { commands += "resume" }
        override fun resumeAt(atReceiverMonotonicMs: Long) { commands += "resumeAt:$atReceiverMonotonicMs" }
        override fun stop() { commands += "stop" }
        override fun seek(seconds: Double) { commands += "seek:$seconds" }
        override fun setVolume(volume: Double) { commands += "volume:$volume" }
        override fun setSpeed(speed: Double) { commands += "speed:$speed" }
        override fun setTrack(type: Int, trackId: String) { commands += "track:$type:$trackId" }
    }
}
