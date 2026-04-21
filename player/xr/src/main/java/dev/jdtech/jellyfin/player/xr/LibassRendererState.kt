package dev.jdtech.jellyfin.player.xr

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import dev.jdtech.jellyfin.player.xr.mcp.LibassState
import dev.jdtech.jellyfin.player.xr.mcp.McpBridge
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Subtitle + libass render state for the XR player. Owns:
 *   - libass render loop and its output (`bitmap`, `hasContent`, `frameVersion`)
 *   - the `useLibass` decision (stereo mode / preference / track-version gated)
 *   - Media3 fallback cues (`currentCues`, `subtitleTrackSelected`)
 *   - the entity-overlay attachment counter (`overlayAttachmentVersion`) + post-move debug log
 *
 * `rememberLibassRenderer` wires up the four `LaunchedEffect`s and the `DisposableEffect`
 * that hosts the Player.Listener — the screen consumes a single `@Stable` state bag and
 * calls `bumpOverlayAttachment()` / `reset()` instead of juggling nine remembered vars.
 */
@Stable
internal class LibassRendererState {
    var useLibass by mutableStateOf(false)
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var hasContent by mutableStateOf(false)
        private set
    var frameVersion by mutableIntStateOf(0)
        private set
    var overlayAttachmentVersion by mutableIntStateOf(0)
        private set
    var currentCues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var subtitleTrackSelected by mutableStateOf(false)
        private set

    /** Bumped by `updateSubtitleTrackSelection`; keys the `useLibass` decision effect. */
    var subtitleTrackVersion by mutableIntStateOf(0)
        private set

    private var lastLoggedMoveFrameVersion by mutableIntStateOf(0)

    /** True when there is content to show, either from libass or Media3 cues. */
    val hasSubtitleContent: Boolean
        get() = (useLibass && hasContent && bitmap != null) ||
            (!useLibass && subtitleTrackSelected && currentCues.isNotEmpty())

    /** Screen calls this when the video overlay entity is (re)attached. */
    fun bumpOverlayAttachment() {
        overlayAttachmentVersion++
    }

    /** Clear all state on screen exit. */
    fun reset() {
        useLibass = false
        subtitleTrackSelected = false
        hasContent = false
        bitmap = null
        currentCues = emptyList()
    }

    // --- package-internal writers invoked by rememberLibassRenderer effects ---

    internal fun setUseLibass(value: Boolean) {
        useLibass = value
        if (!value) {
            hasContent = false
            bitmap = null
        }
    }

    /** Returns true if the bitmap changed (caller may want to log). */
    internal fun applyRenderResult(newBitmap: Bitmap?, newHasContent: Boolean): Boolean {
        hasContent = newHasContent
        return if (newBitmap != null) {
            bitmap = newBitmap
            frameVersion++
            true
        } else {
            false
        }
    }

    /** Returns true the first time we see each new attachment version while rendering. */
    internal fun consumeFirstFrameAfterMove(): Boolean {
        if (overlayAttachmentVersion > 0 && overlayAttachmentVersion > lastLoggedMoveFrameVersion) {
            lastLoggedMoveFrameVersion = overlayAttachmentVersion
            return true
        }
        return false
    }

    internal fun handleCues(cues: List<Cue>) {
        currentCues = if (subtitleTrackSelected) cues else emptyList()
    }

    internal fun updateSubtitleTrackSelection(selected: Boolean) {
        subtitleTrackSelected = selected
        if (!selected) currentCues = emptyList()
        subtitleTrackVersion++
    }
}

@Composable
internal fun rememberLibassRenderer(
    player: Player,
    currentStereoMode: String,
    libassUsagePref: String,
    libassRenderer: LibassRenderer?,
    onCueRecorded: (positionMs: Long, text: String) -> Unit,
): LibassRendererState {
    val state = remember { LibassRendererState() }

    // Player.Listener: updates cues + subtitle-track-selected, records to AI context.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                state.handleCues(cueGroup.cues)
                if (cueGroup.cues.isNotEmpty()) {
                    val first = cueGroup.cues[0].text?.toString()
                    if (first != null) {
                        val pos = player.currentPosition
                        Timber.d("AI Subtitle Buffer: adding cue at %d: %s", pos, first)
                        onCueRecorded(pos, first)
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val selected =
                    tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_TEXT &&
                            group.isSupported &&
                            groupIsSelected(group)
                    }

                // AI context optimization: find and record the first available SDH track for
                // assistant context, even if not visually selected. Gives the AI non-verbal
                // cues (e.g. [Door Slams]).
                val aiTrack =
                    tracks.groups
                        .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                        .sortedByDescending { group ->
                            val label = group.mediaTrackGroup.getFormat(0).label?.lowercase() ?: ""
                            val roleFlags = group.mediaTrackGroup.getFormat(0).roleFlags
                            var score = 0
                            if (label.contains("sdh") || label.contains("cc")) score += 100
                            if (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) score += 50
                            if (roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0) score += 50
                            score
                        }
                        .firstOrNull()

                if (aiTrack != null) {
                    val format = aiTrack.mediaTrackGroup.getFormat(0)
                    Timber.i(
                        "subtitle: XR assistant track candidate label=%s lang=%s roleFlags=%d",
                        format.label,
                        format.language,
                        format.roleFlags,
                    )
                }

                state.updateSubtitleTrackSelection(selected)
                Timber.i(
                    "subtitle: XR track snapshot updated version=%d textGroups=%d selected=%b",
                    state.subtitleTrackVersion,
                    tracks.groups.count { it.type == C.TRACK_TYPE_TEXT },
                    selected,
                )
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Decide whether libass is active (stereo / renderer / pref / track gate).
    LaunchedEffect(player, state.subtitleTrackVersion, currentStereoMode, libassUsagePref) {
        val stereoPlayback =
            currentStereoMode == "sbs" ||
                currentStereoMode == "top_bottom" ||
                currentStereoMode == "multiview"
        val enable =
            !stereoPlayback &&
                libassRenderer != null &&
                LibassSubtitleHelper.shouldUseLibass(player, emptyList(), libassUsagePref)
        state.setUseLibass(enable)
        Timber.i(
            "subtitle: useLibass=%b (renderer=%b pref=%s stereoMode=%s trackVersion=%d)",
            enable,
            libassRenderer != null,
            libassUsagePref,
            currentStereoMode,
            state.subtitleTrackVersion,
        )
    }

    // ~60fps render loop while libass is active.
    LaunchedEffect(state.useLibass) {
        while (state.useLibass) {
            val pos = player.currentPosition
            val result = libassRenderer?.renderFrame(pos)
            if (result != null) {
                val bitmapChanged = state.applyRenderResult(result.bitmap, result.hasContent)
                if ((bitmapChanged || result.hasContent) && state.consumeFirstFrameAfterMove()) {
                    Timber.i(
                        "subtitle: first frame after move overlayVersion=%d hasContent=%b bitmap=%b frameVersion=%d posMs=%d",
                        state.overlayAttachmentVersion,
                        result.hasContent,
                        result.bitmap != null,
                        state.frameVersion,
                        pos,
                    )
                }
            }
            delay(16) // ~60fps for \move, \fad, \k karaoke smoothness
        }
    }

    // Post-move diagnostic snapshot.
    LaunchedEffect(state.overlayAttachmentVersion) {
        if (state.overlayAttachmentVersion <= 0) return@LaunchedEffect
        delay(750L)
        Timber.i(
            "subtitle: post-move state overlayVersion=%d useLibass=%b hasContent=%b frameVersion=%d bitmap=%b",
            state.overlayAttachmentVersion,
            state.useLibass,
            state.hasContent,
            state.frameVersion,
            state.bitmap != null,
        )
    }

    // MCP bridge mirror.
    LaunchedEffect(state.useLibass, state.hasContent, state.frameVersion, state.bitmap) {
        McpBridge.updateLibass(
            LibassState(
                renderWidth = state.bitmap?.width ?: 0,
                renderHeight = state.bitmap?.height ?: 0,
                hasContent = state.hasContent,
                frameVersion = state.frameVersion,
            ),
        )
    }

    return state
}
