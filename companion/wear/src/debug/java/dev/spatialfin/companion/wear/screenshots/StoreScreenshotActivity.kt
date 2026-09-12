package dev.spatialfin.companion.wear.screenshots

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.spatialfin.companion.protocol.WearChapterInfo
import dev.spatialfin.companion.protocol.WearNextUpItem
import dev.spatialfin.companion.protocol.WearStreamInfo
import dev.spatialfin.companion.protocol.WearTvPairingRequest
import dev.spatialfin.companion.protocol.WearVitalsState
import dev.spatialfin.companion.wear.presentation.AmbientPlayerSurface
import dev.spatialfin.companion.wear.presentation.PlayerBackdrop
import dev.spatialfin.companion.wear.presentation.PlayerFace
import dev.spatialfin.companion.wear.presentation.ScrubbingOverlay
import dev.spatialfin.companion.wear.presentation.VolumeOverlay
import dev.spatialfin.companion.wear.presentation.WearActionRingScreen
import dev.spatialfin.companion.wear.presentation.WearNextUpContent
import dev.spatialfin.companion.wear.presentation.WearReceiverSettingsScreen
import dev.spatialfin.companion.wear.presentation.WearStandaloneSetupScreen
import dev.spatialfin.companion.wear.presentation.components.ArcTimeline
import dev.spatialfin.companion.wear.presentation.components.ArcTimelineState
import dev.spatialfin.companion.wear.presentation.components.ArcVolumeRing
import dev.spatialfin.companion.wear.presentation.components.WearAudioTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearChaptersSheet
import dev.spatialfin.companion.wear.presentation.components.WearSpatialControlsSheet
import dev.spatialfin.companion.wear.presentation.components.WearSubtitleTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearTvPairingDialog
import dev.spatialfin.companion.wear.presentation.components.WearVoiceDialog
import dev.spatialfin.companion.wear.presentation.theme.SpatialFinWearTheme
import dev.spatialfin.companion.wear.transport.TransportState
import dev.spatialfin.companion.wear.voice.VoiceRecordingState

/**
 * Renders one production screen with representative data, for Play Store capture.
 *
 * **Debug source set only** — it is not in the release manifest and cannot ship.
 *
 * Why this exists: the watch app is a remote. With no paired host every screen it
 * can reach on its own is `WearStandaloneSetupScreen`, so a plain emulator capture
 * would produce five identical "Waiting for SpatialFin" shots. Pairing a second
 * emulator running the host would need a matching signing cert, a live Jellyfin
 * server and media actually playing — for a screenshot.
 *
 * These are the real composables at real density, not redrawn lookalikes; only the
 * media metadata is fixed. Titles are Blender open movies (CC-BY), so the store
 * listing does not show artwork or trademarks we have no licence to.
 *
 * Usage:
 *   adb shell am start -n dev.spatialfin.debug/dev.spatialfin.companion.wear.screenshots.StoreScreenshotActivity --es screen player
 */
class StoreScreenshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra(EXTRA_SCREEN) ?: "player"

        setContent {
            SpatialFinWearTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    when (screen) {
                        "player" -> PlayerScene()
                        "scrubbing" -> ScrubbingScene()
                        "volume" -> VolumeScene()
                        "ambient" -> AmbientPlayerSurface(
                            positionSeconds = 372,
                            durationSeconds = 630,
                            title = SPRITE_FRIGHT,
                        )
                        "actions" -> WearActionRingScreen(
                            onOpenAudio = {},
                            onOpenSubtitles = {},
                            onOpenChapters = {},
                            onOpenNextUp = {},
                            onOpenSpatial = {},
                            onOpenVoice = {},
                            onOpenPrivateAudio = {},
                            onDismiss = {},
                        )
                        "audio" -> WearAudioTracksSheet(
                            tracks = AUDIO_TRACKS,
                            currentTrack = AUDIO_TRACKS[1].name,
                            onSelectTrack = {},
                            onDismiss = {},
                        )
                        "subtitles" -> WearSubtitleTracksSheet(
                            tracks = SUBTITLE_TRACKS,
                            currentTrack = SUBTITLE_TRACKS[0].name,
                            onSelectTrack = {},
                            onDismiss = {},
                        )
                        "chapters" -> WearChaptersSheet(
                            chapters = CHAPTERS,
                            currentChapterName = "The Forest",
                            positionSeconds = 372,
                            durationSeconds = 630,
                            onSelectChapter = {},
                            onDismiss = {},
                        )
                        "spatial" -> WearSpatialControlsSheet(
                            onDispatchAction = {},
                            onDismiss = {},
                        )
                        "nextup" -> WearNextUpContent(
                            items = NEXT_UP,
                            onPlay = {},
                            onNavigateBack = {},
                        )
                        "voice" -> WearVoiceDialog(
                            state = VoiceRecordingState.Recording(amplitudeRms = 0.7f),
                            onStopCapture = {},
                            onRequestPermission = {},
                            onDismiss = {},
                        )
                        "pairing" -> WearTvPairingDialog(
                            request = PAIRING_REQUEST,
                            onApprove = {},
                            onReject = {},
                        )
                        "setup" -> WearStandaloneSetupScreen(onRetry = {})
                        "privateaudio", "receiver" -> WearReceiverSettingsScreen(onNavigateBack = {})
                        else -> PlayerScene()
                    }
                }
            }
        }
    }

    @Composable
    private fun PlayerScene() {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerBackdrop(art = null, scrubbing = false)
            ArcTimeline(progress = PROGRESS, state = ArcTimelineState.Idle)
            PlayerFace(
                targetName = "Galaxy XR",
                transportState = TransportState.ConnectedViaDataLayer("node", "Galaxy XR"),
                vitals = WearVitalsState(batteryPercent = 84, deviceName = "Galaxy XR", isHeadset = true),
                title = SPRITE_FRIGHT,
                subtitle = "Blender Studio · 4K HDR",
                positionSeconds = 372,
                durationSeconds = 630,
                isPlaying = true,
                showSkipIntro = false,
                onPlayPause = {},
                onSeekBack = {},
                onSeekForward = {},
                onSkipIntro = {},
                onDeviceClick = {},
                onLongPress = {},
                onActionsClick = {},
            )
        }
    }

    @Composable
    private fun ScrubbingScene() {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerBackdrop(art = null, scrubbing = true)
            ArcTimeline(progress = 0.738f, state = ArcTimelineState.Scrubbing)
            ScrubbingOverlay(
                positionSeconds = 465,
                deltaSeconds = 93,
                title = SPRITE_FRIGHT,
            )
        }
    }

    @Composable
    private fun VolumeScene() {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerBackdrop(art = null, scrubbing = false)
            ArcTimeline(progress = PROGRESS, state = ArcTimelineState.Volume)
            ArcVolumeRing(volume = 0.62f)
            VolumeOverlay(volume = 0.62f, onSwapToScrub = {})
        }
    }

    private companion object {
        const val EXTRA_SCREEN = "screen"

        /** Blender open movies — CC-BY, and what the design mock used. */
        const val SPRITE_FRIGHT = "Sprite Fright"

        /** 06:12 of 10:30, the design's reference frame. */
        const val PROGRESS = 372f / 630f

        val AUDIO_TRACKS = listOf(
            WearStreamInfo(index = 0, name = "Original - TrueHD - 7.1", language = "und"),
            WearStreamInfo(index = 1, name = "English - EAC3 - 5.1", language = "eng", isSelected = true),
            WearStreamInfo(index = 2, name = "Español - AAC - 2.0", language = "spa"),
            WearStreamInfo(index = 3, name = "Commentary - AAC - 2.0", language = "eng"),
        )

        val SUBTITLE_TRACKS = listOf(
            WearStreamInfo(index = 0, name = "English - ASS", language = "eng", isSelected = true),
            WearStreamInfo(index = 1, name = "English SDH - SRT", language = "eng"),
            WearStreamInfo(index = 2, name = "Español - SRT", language = "spa"),
        )

        val CHAPTERS = listOf(
            WearChapterInfo(name = "Opening", startPositionSeconds = 0),
            WearChapterInfo(name = "The Forest", startPositionSeconds = 108),
            WearChapterInfo(name = "Campfire", startPositionSeconds = 276),
            WearChapterInfo(name = "Sprites", startPositionSeconds = 422),
        )

        val NEXT_UP = listOf(
            WearNextUpItem(
                id = "1",
                title = "Spring",
                overview = "",
                mediaType = "Movie",
                durationSeconds = 462,
                playbackPositionSeconds = 0,
            ),
            WearNextUpItem(
                id = "2",
                title = "Sintel",
                overview = "",
                mediaType = "Movie",
                durationSeconds = 888,
                playbackPositionSeconds = 708,
            ),
            WearNextUpItem(
                id = "3",
                title = "Big Buck Bunny",
                overview = "",
                mediaType = "Movie",
                durationSeconds = 596,
                playbackPositionSeconds = 120,
            ),
        )

        val PAIRING_REQUEST = WearTvPairingRequest(
            deviceName = "Living Room TV",
            pairingToken = "token",
            manualCode = "4821",
            receiverUrl = "",
            expiresAtEpochMs = System.currentTimeMillis() + 42_000L,
        )
    }
}
