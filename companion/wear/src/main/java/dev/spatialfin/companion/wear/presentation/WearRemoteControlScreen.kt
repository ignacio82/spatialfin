package dev.spatialfin.companion.wear.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.spatialfin.companion.protocol.WearPlayerAction
import dev.spatialfin.companion.protocol.WearVitalsState
import dev.spatialfin.companion.wear.ambient.LocalAmbientMode
import dev.spatialfin.companion.wear.pairing.WearPairingManager
import dev.spatialfin.companion.wear.presentation.components.WearAudioTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearChaptersSheet
import dev.spatialfin.companion.wear.presentation.components.WearDevicePickerSheet
import dev.spatialfin.companion.wear.presentation.components.WearSpatialControlsSheet
import dev.spatialfin.companion.wear.presentation.components.WearSubtitleTracksSheet
import dev.spatialfin.companion.wear.presentation.components.WearTvPairingDialog
import dev.spatialfin.companion.wear.presentation.components.WearVoiceDialog
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimary
import dev.spatialfin.companion.wear.presentation.theme.WearDarkPrimaryContainer
import dev.spatialfin.companion.wear.presentation.theme.WearDarkSurfaceVariant
import dev.spatialfin.companion.wear.rotary.CrownMode
import dev.spatialfin.companion.wear.rotary.rememberRotaryScrubState
import dev.spatialfin.companion.wear.rotary.rememberRotaryVolumeState
import dev.spatialfin.companion.wear.rotary.rotaryControl
import dev.spatialfin.companion.wear.transport.TransportState
import dev.spatialfin.companion.wear.transport.WearTransportManager
import dev.spatialfin.companion.wear.voice.VoiceRecordingState
import dev.spatialfin.companion.wear.voice.WearVoiceCapture
import kotlinx.coroutines.launch

private sealed interface ActiveWearSheet {
    data object AudioTracks : ActiveWearSheet
    data object SubtitleTracks : ActiveWearSheet
    data object Chapters : ActiveWearSheet
    data object SpatialControls : ActiveWearSheet
    data object DevicePicker : ActiveWearSheet
    data object Voice : ActiveWearSheet
}

@Composable
fun WearRemoteControlScreen(
    transportManager: WearTransportManager,
    voiceCapture: WearVoiceCapture,
    pairingManager: WearPairingManager,
    onNavigateToNextUp: () -> Unit,
    onNavigateToReceiverSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
) {
    val isAmbient = LocalAmbientMode.current
    val coroutineScope = rememberCoroutineScope()

    val transportState by transportManager.transportState.collectAsState()
    val nowPlaying by transportManager.nowPlaying.collectAsState()
    val vitals by transportManager.vitals.collectAsState()
    val coverArt by transportManager.coverArt.collectAsState()
    val pendingPairing by pairingManager.pendingPairingRequest.collectAsState()
    val voiceState by voiceCapture.recordingState.collectAsState()

    var activeSheet by remember { mutableStateOf<ActiveWearSheet?>(null) }
    val focusRequester = remember { FocusRequester() }

    val currentPos = nowPlaying?.positionSeconds ?: 0L
    val duration = nowPlaying?.durationSeconds ?: 0L
    val isPlaying = nowPlaying?.isPlaying ?: false

    val scrubState = rememberRotaryScrubState(
        positionSeconds = currentPos,
        durationSeconds = duration,
        onSeek = { targetSec ->
            coroutineScope.launch {
                transportManager.dispatchAction(WearPlayerAction.SeekTo(targetSec))
            }
        },
    )

    val volumeState = rememberRotaryVolumeState(
        volume = nowPlaying?.volume ?: 1f,
        onVolumeChange = { level ->
            coroutineScope.launch {
                transportManager.dispatchAction(WearPlayerAction.AdjustVolume(percentage = level))
            }
        },
    )

    // The crown drives one thing at a time. Scrub is the default (it is the reason
    // this app exists); tapping the volume chip hands the crown to volume until it
    // is tapped again. Both fall through to list scrolling when nothing is playing.
    var crownMode by remember { mutableStateOf(CrownMode.Scrub) }
    val scrollState = rememberScrollState()

    // Modal Sheet Overlays
    if (pendingPairing != null) {
        WearTvPairingDialog(
            request = pendingPairing!!,
            onApprove = { pairingManager.approvePairing(pendingPairing!!) },
            onReject = { pairingManager.rejectPairing(pendingPairing!!) },
        )
        return
    }

    when (activeSheet) {
        is ActiveWearSheet.AudioTracks -> {
            WearAudioTracksSheet(
                tracks = nowPlaying?.audioTracks.orEmpty(),
                currentTrack = nowPlaying?.currentAudioTrack,
                onSelectTrack = { track ->
                    coroutineScope.launch {
                        transportManager.dispatchAction(
                            WearPlayerAction.SelectAudioTrack(language = track.language, index = track.index),
                        )
                    }
                },
                onDismiss = { activeSheet = null },
            )
            return
        }

        is ActiveWearSheet.SubtitleTracks -> {
            WearSubtitleTracksSheet(
                tracks = nowPlaying?.subtitleTracks.orEmpty(),
                currentTrack = nowPlaying?.currentSubtitleTrack,
                onSelectTrack = { track ->
                    coroutineScope.launch {
                        if (track == null) {
                            transportManager.dispatchAction(WearPlayerAction.DisableSubtitles)
                        } else {
                            transportManager.dispatchAction(
                                WearPlayerAction.SelectSubtitleTrack(language = track.language, index = track.index),
                            )
                        }
                    }
                },
                onDismiss = { activeSheet = null },
            )
            return
        }

        is ActiveWearSheet.Chapters -> {
            WearChaptersSheet(
                chapters = nowPlaying?.chapters.orEmpty(),
                onSelectChapter = { ch ->
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.SeekTo(ch.startPositionSeconds))
                    }
                },
                onDismiss = { activeSheet = null },
            )
            return
        }

        is ActiveWearSheet.SpatialControls -> {
            WearSpatialControlsSheet(
                onDispatchAction = { action ->
                    coroutineScope.launch { transportManager.dispatchAction(action) }
                },
                onDismiss = { activeSheet = null },
            )
            return
        }

        is ActiveWearSheet.DevicePicker -> {
            val lanReceivers by transportManager.directLanClient.discoveredReceivers.collectAsState()
            LaunchedEffect(Unit) {
                transportManager.directLanClient.startDiscovery()
            }
            WearDevicePickerSheet(
                currentDeviceName = nowPlaying?.targetDeviceName ?: "SpatialFin",
                lanReceivers = lanReceivers,
                canFling = !nowPlaying?.streamUrl.isNullOrBlank(),
                onSelectLanReceiver = { recv ->
                    coroutineScope.launch {
                        transportManager.directLanClient.connectToReceiver(recv)
                        transportManager.checkConnectivity()
                    }
                },
                onFlingToReceiver = { recv ->
                    val stream = nowPlaying
                    coroutineScope.launch {
                        val url = stream?.streamUrl ?: return@launch
                        transportManager.directLanClient.castStream(
                            receiver = recv,
                            streamUrl = url,
                            container = stream.mediaContainer,
                            title = stream.title.ifBlank { "SpatialFin" },
                            positionSeconds = stream.positionSeconds.toDouble(),
                        )
                        transportManager.checkConnectivity()
                    }
                },
                onDismiss = {
                    transportManager.directLanClient.stopDiscovery()
                    activeSheet = null
                },
            )
            return
        }

        is ActiveWearSheet.Voice -> {
            WearVoiceDialog(
                state = voiceState,
                onStopCapture = { voiceCapture.stopCapture() },
                onRequestPermission = onRequestMicPermission,
                onDismiss = {
                    voiceCapture.stopCapture()
                    activeSheet = null
                },
            )
            return
        }

        null -> Unit
    }

    // Main Rotary-Scrubbable Remote Surface
    // Re-requested every time the main surface comes back, because each sheet
    // replaces this Column outright and takes the focus target with it. Keyed on
    // activeSheet so returning from a sheet restores crown control.
    LaunchedEffect(activeSheet, pendingPairing) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .rotaryControl(
                scrubState = scrubState,
                volumeState = volumeState,
                mode = crownMode,
                scrollState = scrollState,
                enabled = duration > 0,
            )
            .padding(horizontal = 10.dp, vertical = 14.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top HUD Header
        RemoteTopHeader(
            targetName = nowPlaying?.targetDeviceName ?: "SpatialFin",
            transportState = transportState,
            vitals = vitals,
            onDeviceClick = { activeSheet = ActiveWearSheet.DevicePicker },
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Poster & Metadata
        NowPlayingHero(
            title = nowPlaying?.title?.ifBlank { "SpatialFin" } ?: "SpatialFin",
            seriesName = nowPlaying?.seriesName,
            seasonNumber = nowPlaying?.seasonNumber,
            episodeNumber = nowPlaying?.episodeNumber,
            coverArt = if (!isAmbient) coverArt else null,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Scrubber Timeline
        ScrubberTimeline(
            positionSeconds = scrubState.currentScrubPositionSeconds,
            durationSeconds = duration,
            isScrubbing = scrubState.isScrubbing,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Transport Controls (-10s, Play/Pause, +10s)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.SeekBackward(10))
                    }
                },
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
            ) {
                Text("-10", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.TogglePlayPause)
                    }
                },
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
            ) {
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    fontSize = 20.sp,
                )
            }

            FilledTonalButton(
                onClick = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.SeekForward(10))
                    }
                },
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
            ) {
                Text("+10", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Optional Skip Intro button
        if (nowPlaying?.segmentType?.contains("intro", ignoreCase = true) == true) {
            Spacer(modifier = Modifier.height(4.dp))
            FilledTonalButton(
                onClick = {
                    coroutineScope.launch {
                        transportManager.dispatchAction(WearPlayerAction.SkipIntro)
                    }
                },
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Text("⏭ Skip Intro", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Crown target selector + live volume readout
        FilledTonalButton(
            onClick = {
                crownMode = if (crownMode == CrownMode.Volume) CrownMode.Scrub else CrownMode.Volume
            },
            colors = if (crownMode == CrownMode.Volume) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = WearDarkPrimaryContainer,
                    contentColor = Color.White,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = WearDarkSurfaceVariant,
                    contentColor = Color.LightGray,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (crownMode == CrownMode.Volume) {
                    "🔊 Crown: Vol ${(volumeState.currentVolume * 100).toInt()}%"
                } else {
                    "🔊 Vol ${(volumeState.currentVolume * 100).toInt()} · crown scrubs"
                },
                fontSize = 11.sp,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Fast-Switcher Grid Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                onClick = { activeSheet = ActiveWearSheet.AudioTracks },
                modifier = Modifier.weight(1f),
            ) {
                Text("🔊 Aud", fontSize = 11.sp)
            }
            FilledTonalButton(
                onClick = { activeSheet = ActiveWearSheet.SubtitleTracks },
                modifier = Modifier.weight(1f),
            ) {
                Text("💬 Sub", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                onClick = { activeSheet = ActiveWearSheet.Chapters },
                modifier = Modifier.weight(1f),
            ) {
                Text("📑 Chap", fontSize = 11.sp)
            }
            FilledTonalButton(
                onClick = { activeSheet = ActiveWearSheet.SpatialControls },
                modifier = Modifier.weight(1f),
            ) {
                Text("🎯 XR", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    activeSheet = ActiveWearSheet.Voice
                    voiceCapture.startCapture()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("🎤 Voice", fontSize = 11.sp)
            }
            FilledTonalButton(
                onClick = onNavigateToNextUp,
                modifier = Modifier.weight(1f),
            ) {
                Text("📋 Next Up", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        FilledTonalButton(
            onClick = onNavigateToReceiverSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("🎧 Private Audio Sink", fontSize = 11.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}

@Composable
private fun RemoteTopHeader(
    targetName: String,
    transportState: TransportState,
    vitals: WearVitalsState?,
    onDeviceClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(WearDarkSurfaceVariant)
                .clickable { onDeviceClick() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when (transportState) {
                is TransportState.ConnectedViaDataLayer -> "📱"
                is TransportState.ConnectedViaFCastLan -> "📺"
                is TransportState.ConnectedViaJellyfinRelay -> "🌐"
                is TransportState.Disconnected -> "⚠️"
            }
            Text(text = icon, fontSize = 10.sp)
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = targetName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Battery / Vitals Indicator
        if (vitals != null && vitals.batteryPercent >= 0) {
            val batteryColor = when {
                vitals.batteryPercent <= 20 -> Color(0xFFFFB4AB)
                else -> WearDarkPrimary
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(WearDarkSurfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${if (vitals.isHeadset) "🥽 " else "🔋 "}${vitals.batteryPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = batteryColor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingHero(
    title: String,
    seriesName: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    coverArt: Bitmap?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (coverArt != null) {
            Image(
                bitmap = coverArt.asImageBitmap(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        if (!seriesName.isNullOrBlank()) {
            val epText = if (seasonNumber != null && episodeNumber != null) {
                "S$seasonNumber:E$episodeNumber • $seriesName"
            } else {
                seriesName
            }
            Text(
                text = epText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ScrubberTimeline(
    positionSeconds: Long,
    durationSeconds: Long,
    isScrubbing: Boolean,
) {
    val progress = if (durationSeconds > 0) {
        (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isScrubbing) Color(0xFF556070) else Color(0xFF2C323D)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isScrubbing) Color(0xFFFFD56B) else WearDarkPrimary),
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Time format: 01:24:18 / 02:46:00
        val posStr = formatSeconds(positionSeconds)
        val durStr = formatSeconds(durationSeconds)
        Text(
            text = "$posStr / $durStr",
            style = MaterialTheme.typography.labelSmall,
            color = if (isScrubbing) Color(0xFFFFD56B) else Color.LightGray,
            fontWeight = if (isScrubbing) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun formatSeconds(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
