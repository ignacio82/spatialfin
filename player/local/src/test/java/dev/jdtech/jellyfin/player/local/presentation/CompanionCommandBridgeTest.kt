package dev.jdtech.jellyfin.player.local.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionCommandBridgeTest {
    @Test fun `previous player disposal cannot remove current player command handler`() = runBlocking {
        CompanionCommandBridge.bind("old") { _, _ -> "old" }
        CompanionCommandBridge.bind("new") { action, voice -> action ?: voice.orEmpty() }
        CompanionCommandBridge.unbind("old")
        assertEquals("action", CompanionCommandBridge.dispatch("action", null))
        assertEquals("voice", CompanionCommandBridge.dispatch(null, "voice"))
        CompanionCommandBridge.unbind("new")
        assertEquals("No active player session", CompanionCommandBridge.dispatch("action", null))
    }
}
