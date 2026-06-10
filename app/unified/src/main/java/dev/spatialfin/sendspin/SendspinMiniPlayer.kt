package dev.spatialfin.sendspin

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.sendspin.receiver.SendspinControllerCommands
import dev.jdtech.jellyfin.sendspin.receiver.SendspinMusicAssistantAuthState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverPlaybackState
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverService
import dev.jdtech.jellyfin.sendspin.receiver.SendspinReceiverSession

/**
 * Mini-player that takes over when SendSpin is receiving audio but the user
 * has closed the [SendspinFullscreenPlayer]. Without this composable, tapping
 * "X" on the fullscreen left the user with no playback controls at all (the
 * service kept playing audio invisibly).
 *
 * Visibility rule: SendSpin is connected, has media metadata, AND the user
 * dismissed the fullscreen. The fullscreen itself shows on the opposite
 * condition (`showControls` is true → `controlsDismissed` is false), so the
 * two never compete for the same pixels.
 *
 * Tap anywhere on the mini-player restores the fullscreen
 * ([SendspinReceiverSession.restoreControls]). The transport buttons forward
 * to the SendSpin controller commands the receiver service already exposes;
 * the song doesn't need to know which UI it came from.
 */
@Composable
fun SendspinMiniPlayer(
    modifier: Modifier = Modifier,
    suppressed: Boolean = false,
) {
    val state by SendspinReceiverSession.state.collectAsStateWithLifecycle()
    // Show whenever the user dismissed the fullscreen during an active session,
    // so they can always tap to restore and resume. Pausing a SendSpin endpoint
    // reports STOPPED, so gating purely on `playbackState != STOPPED` would hide
    // the mini-player on pause (no way back). `playbackStarted` stays true
    // through a pause but is cleared by a genuine Stop, which keeps Stop a clean
    // exit (mini-player hides).
    // [suppressed] when MA is already presenting this playback through the
    // unified MA player — avoids two mini-players for the same song.
    //
    // Single-player rule: when this device is an MA-driven SendSpin endpoint
    // (authenticated), the rich MA now-playing player owns the UI for the whole
    // playback lifecycle. We hide the SendSpin player entirely rather than gate
    // on the MA session's per-frame now-playing (which briefly clears on pause
    // and made the SendSpin player flicker back in). The SendSpin receiver keeps
    // doing its job headless; only its UI steps aside.
    val maDriven = state.musicAssistantAuthState == SendspinMusicAssistantAuthState.AUTHENTICATED
    val visible = !suppressed &&
        !maDriven &&
        state.connected &&
        state.controlsDismissed &&
        state.hasMedia &&
        (state.playbackState != SendspinReceiverPlaybackState.STOPPED || state.playbackStarted)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val context = LocalContext.current
        val bitmap = remember(state.albumArtwork) {
            state.albumArtwork
                ?.takeIf { it.isNotEmpty() }
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        val playPauseCommand = if (state.isPlaying) {
            SendspinControllerCommands.PAUSE
        } else {
            SendspinControllerCommands.PLAY
        }
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clickable { SendspinReceiverSession.restoreControls() },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(bitmap = bitmap, artworkUrl = state.artworkUrl)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title?.takeIf(String::isNotBlank) ?: "SendSpin audio",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = state.artist?.takeIf(String::isNotBlank)
                        ?: state.album?.takeIf(String::isNotBlank)
                        ?: state.serverName?.takeIf(String::isNotBlank)
                        ?: "SendSpin"
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.supports(playPauseCommand)) {
                    IconButton(
                        onClick = {
                            SendspinReceiverService.sendControllerCommand(
                                context = context.applicationContext,
                                command = playPauseCommand,
                            )
                        },
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                }
                if (state.supports(SendspinControllerCommands.NEXT)) {
                    IconButton(
                        onClick = {
                            SendspinReceiverService.sendControllerCommand(
                                context = context.applicationContext,
                                command = SendspinControllerCommands.NEXT,
                            )
                        },
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun Artwork(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    artworkUrl: String?,
) {
    val shape = RoundedCornerShape(8.dp)
    val artworkModifier = Modifier.size(44.dp).clip(shape)
    when {
        bitmap != null -> {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = artworkModifier,
            )
        }
        !artworkUrl.isNullOrBlank() -> {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                modifier = artworkModifier,
            )
        }
        else -> {
            Box(
                modifier = artworkModifier.background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
