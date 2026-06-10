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
 * When this device is the active SendSpin receiver, MA routes audio through a
 * Universal-Player wrapper that owns the queue / now-playing / party source.
 * The receiver service resolves that wrapper id into
 * [SendspinReceiverSession.musicAssistantQueuePlayerId]. Selecting it in the MA
 * session makes the rich MA now-playing UI (queue, party, add-to-playlist) the
 * single player for local playback — and the SendSpin receiver controls suppress
 * themselves whenever the MA session has a now-playing track.
 *
 * Gated on [SendspinReceiverSession.playbackStarted] so it only hijacks the MA
 * selection while we are actually the playback target. If the user explicitly
 * picked a different MA player and isn't playing here, their choice stands.
 *
 * Install once per form-factor root (Beam / TV / XR).
 */
@Composable
fun MaLocalPlaybackBridge(maSession: MaSessionRepository) {
    val state by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    val wrapperId = state.musicAssistantQueuePlayerId
        ?.takeIf { it.isNotBlank() && state.playbackStarted }
    LaunchedEffect(wrapperId) {
        if (wrapperId != null) maSession.setSelectedPlayer(wrapperId)
    }
}
