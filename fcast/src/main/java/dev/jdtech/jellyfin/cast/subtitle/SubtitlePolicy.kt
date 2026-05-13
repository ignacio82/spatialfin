package dev.jdtech.jellyfin.cast.subtitle

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.SubtitleFidelity

/**
 * Pure decision tree for sender-side subtitle handling on a cast session. Translates "what does
 * the receiver support" + "what does this title contain" + "what did the user choose in
 * settings" into a single [Decision] that the session manager applies when building the Jellyfin
 * stream URL.
 *
 * Lives in `:fcast` (not `:app`) so it stays JVM-testable — no Android imports, no Jellyfin SDK.
 * Call sites construct a [SubtitleTrack] from `SpatialFinMediaStream` and let the policy decide.
 */
object SubtitlePolicy {

    /**
     * What the caller knows about a single subtitle stream on the title being cast. The
     * detector consumes [codec] and [isStyled] only; everything else is forwarded into
     * [Decision] so the session manager can build the burn-in URL parameters.
     *
     * @property streamIndex Jellyfin `MediaStream.index`, used by the burn-in URL as
     *   `SubtitleStreamIndex=`. Required even for non-burn paths so the receiver knows which
     *   track was selected.
     * @property codec lowercase Jellyfin codec name (`ass`, `ssa`, `srt`, `subrip`, `webvtt`, ...).
     * @property isStyled set when the caller has already run [AssStyleDetector.isStyled] on the
     *   sidecar `.ass` content. Null = caller hasn't inspected the content yet (treat as
     *   "potentially styled" — see [decide]).
     */
    data class SubtitleTrack(
        val streamIndex: Int,
        val codec: String,
        val language: String?,
        val isExternal: Boolean,
        val isStyled: Boolean? = null,
    ) {
        val isAss: Boolean get() = codec.equals("ass", ignoreCase = true) ||
            codec.equals("ssa", ignoreCase = true)
    }

    /** User preference matching `AppPreferences.castSubtitleHandling`. */
    enum class UserPreference {
        /** Best fidelity: burn in for non-SpatialFin receivers. Default. */
        BestFidelity,

        /** Faster start: never burn in. ASS subs degraded on non-SpatialFin receivers. */
        FasterStart,

        /** Off: don't send subtitles when casting. */
        Off,
    }

    sealed interface Decision {

        /** Stream the title as-is; the receiver renders subtitles natively. */
        data class Native(val track: SubtitleTrack) : Decision

        /**
         * Request a server-side burn-in transcode of [track]. The session manager rewrites
         * the URL to include `SubtitleStreamIndex=<streamIndex>&SubtitleMethod=Encode` plus
         * `static=false` (forcing a transcode segment producer). Receiver renders the
         * resulting video with subs baked into pixels.
         */
        data class BurnIn(val track: SubtitleTrack) : Decision

        /**
         * Stream the title as-is, but the receiver will render the track without full styling
         * (Cast / AirPlay / non-SpatialFin FCast on a styled track when the user picked
         * "Faster start"). UI surfaces a one-time toast.
         */
        data class Degraded(val track: SubtitleTrack) : Decision

        /** No subtitle track will be sent. */
        data object NoSubtitles : Decision
    }

    /**
     * Run the decision tree.
     *
     * Input axes:
     *  - [tracks] — every subtitle stream on the title, in source order.
     *  - [receiverCapabilities] — post-handshake capability set from
     *    `ProtocolAdapter.currentCapabilities`. Only [CastCapability.NativeAss] influences
     *    the decision; [CastCapability.EmbeddedFonts] is read by the session manager
     *    separately when deciding which warning to surface.
     *  - [userPreference] — from settings.
     *
     * Output: see [Decision].
     *
     * Track selection rule: prefer the first non-external default track when one is marked
     * default in Jellyfin metadata, otherwise the first track in source order. The brief leaves
     * the per-track UI to PR 6; for v1 we pick deterministically so test fixtures are stable.
     */
    fun decide(
        tracks: List<SubtitleTrack>,
        receiverCapabilities: Set<CastCapability>,
        userPreference: UserPreference,
    ): Decision {
        if (userPreference == UserPreference.Off) return Decision.NoSubtitles
        val track = tracks.firstOrNull() ?: return Decision.NoSubtitles
        return decideForTrack(track, receiverCapabilities, userPreference)
    }

    /**
     * Apply the policy to a pre-selected [track] (e.g. the user's active subtitle in the in-player
     * cast button flow). Exposed separately so the session manager can plug in whichever track
     * is appropriate at the call site without re-running the source-order rule above.
     */
    fun decideForTrack(
        track: SubtitleTrack,
        receiverCapabilities: Set<CastCapability>,
        userPreference: UserPreference,
    ): Decision {
        if (userPreference == UserPreference.Off) return Decision.NoSubtitles
        val isAss = track.isAss
        val nativeAss = CastCapability.NativeAss in receiverCapabilities
        // Non-ASS tracks (SRT / VTT / CC) work natively everywhere — no policy needed.
        if (!isAss) return Decision.Native(track)
        // ASS on a libass-capable receiver: stream natively.
        if (nativeAss) return Decision.Native(track)
        // Plain-dialogue ASS on a non-libass receiver: stock TextRenderer renders it fine.
        // `isStyled == null` (caller hasn't probed the content) is treated as "potentially
        // styled" so we err on the safe side and either burn in or warn.
        val styled = track.isStyled ?: true
        if (!styled) return Decision.Native(track)
        // Styled ASS, non-libass receiver, user wants best fidelity → request burn-in.
        // User picked "faster start" → ship as-is and tell them via the warning toast.
        return when (userPreference) {
            UserPreference.BestFidelity -> Decision.BurnIn(track)
            UserPreference.FasterStart -> Decision.Degraded(track)
            UserPreference.Off -> Decision.NoSubtitles
        }
    }

    /** Map a decision back to the user-visible fidelity level the UI displays. */
    fun fidelityFor(decision: Decision): SubtitleFidelity = when (decision) {
        is Decision.Native -> SubtitleFidelity.Native
        is Decision.BurnIn -> SubtitleFidelity.Transcoding
        is Decision.Degraded -> SubtitleFidelity.Degraded
        Decision.NoSubtitles -> SubtitleFidelity.None
    }
}
