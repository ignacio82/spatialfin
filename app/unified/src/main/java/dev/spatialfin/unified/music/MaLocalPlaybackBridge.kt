package dev.spatialfin.unified.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.data.musicassistant.repository.MaSessionRepository
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession

/**
 * Make the MA session follow local (SendSpin) playback so there is exactly ONE
 * now-playing surface.
 *
 * When this device is the active SendSpin receiver, MA routes audio through the
 * local queue target (usually a Universal-Player wrapper, with the raw protocol
 * endpoint as a fallback). The receiver service resolves that id into
 * [SendspinReceiverSession.musicAssistantQueuePlayerId]. Selecting it in the MA
 * session makes the rich MA now-playing UI (queue, party, add-to-playlist) the
 * single player for local playback.
 *
 * Gated on [SendspinReceiverSession.playbackStarted] or an active optimistic MA
 * play so it only hijacks selection while we are actually the playback target.
 * If the user explicitly picked a different MA player and isn't playing here,
 * their choice stands.
 *
 * Install once per form-factor root (Beam / TV / XR).
 */
@Composable
fun MaLocalPlaybackBridge(maSession: MaSessionRepository) {
    val state by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    val maState by maSession.session.collectAsStateWithLifecycle()
    val localPlayerId = state.musicAssistantQueuePlayerId
        ?.takeIf {
            it.isNotBlank() &&
                (state.playbackStarted || maState.pendingPlayUri != null)
        }
    LaunchedEffect(localPlayerId) {
        if (localPlayerId != null) maSession.setSelectedPlayer(localPlayerId)
    }
}
