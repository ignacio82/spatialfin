package dev.jdtech.jellyfin.cast.subtitle

import dev.jdtech.jellyfin.cast.CastCapability
import dev.jdtech.jellyfin.cast.SubtitleFidelity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePolicyTest {

    private val nativeAssReceiver: Set<CastCapability> = setOf(
        CastCapability.NativeAss, CastCapability.EmbeddedFonts, CastCapability.Subtitles,
    )
    private val plainReceiver: Set<CastCapability> = setOf(CastCapability.Subtitles)

    private fun ass(streamIndex: Int = 2, styled: Boolean? = null, external: Boolean = true) =
        SubtitlePolicy.SubtitleTrack(
            streamIndex = streamIndex,
            codec = "ass",
            language = "en",
            isExternal = external,
            isStyled = styled,
        )

    private fun srt(streamIndex: Int = 3) = SubtitlePolicy.SubtitleTrack(
        streamIndex = streamIndex,
        codec = "subrip",
        language = "en",
        isExternal = false,
        isStyled = null,
    )

    @Test
    fun `Off preference yields NoSubtitles regardless of track or receiver`() {
        val decision = SubtitlePolicy.decide(
            tracks = listOf(ass(styled = true)),
            receiverCapabilities = nativeAssReceiver,
            userPreference = SubtitlePolicy.UserPreference.Off,
        )
        assertEquals(SubtitlePolicy.Decision.NoSubtitles, decision)
    }

    @Test
    fun `empty track list yields NoSubtitles`() {
        val decision = SubtitlePolicy.decide(
            tracks = emptyList(),
            receiverCapabilities = nativeAssReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertEquals(SubtitlePolicy.Decision.NoSubtitles, decision)
    }

    @Test
    fun `SRT track always renders natively`() {
        val track = srt()
        val decision = SubtitlePolicy.decide(
            tracks = listOf(track),
            receiverCapabilities = plainReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertTrue(decision is SubtitlePolicy.Decision.Native)
        assertEquals(track, (decision as SubtitlePolicy.Decision.Native).track)
    }

    @Test
    fun `ASS on NativeAss receiver renders natively even when styled`() {
        val track = ass(styled = true)
        val decision = SubtitlePolicy.decide(
            tracks = listOf(track),
            receiverCapabilities = nativeAssReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertTrue(decision is SubtitlePolicy.Decision.Native)
    }

    @Test
    fun `plain-dialogue ASS on plain receiver renders natively`() {
        // No override tags → default Media3 TextRenderer handles it fine; burning in would just
        // waste server CPU.
        val track = ass(styled = false)
        val decision = SubtitlePolicy.decide(
            tracks = listOf(track),
            receiverCapabilities = plainReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertTrue(decision is SubtitlePolicy.Decision.Native)
    }

    @Test
    fun `styled ASS on plain receiver with BestFidelity preference requests burn-in`() {
        val track = ass(styled = true)
        val decision = SubtitlePolicy.decide(
            tracks = listOf(track),
            receiverCapabilities = plainReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertTrue(decision is SubtitlePolicy.Decision.BurnIn)
        assertEquals(track, (decision as SubtitlePolicy.Decision.BurnIn).track)
    }

    @Test
    fun `styled ASS on plain receiver with FasterStart preference falls to Degraded`() {
        val track = ass(styled = true)
        val decision = SubtitlePolicy.decide(
            tracks = listOf(track),
            receiverCapabilities = plainReceiver,
            userPreference = SubtitlePolicy.UserPreference.FasterStart,
        )
        assertTrue(decision is SubtitlePolicy.Decision.Degraded)
    }

    @Test
    fun `unknown styling on plain receiver is treated conservatively as styled`() {
        // null = caller didn't probe. Policy must err on the safe side or anime users get
        // wrong-looking subs for the unfortunate fraction of tracks we couldn't classify.
        val track = ass(styled = null)
        val decision = SubtitlePolicy.decide(
            tracks = listOf(track),
            receiverCapabilities = plainReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertTrue(decision is SubtitlePolicy.Decision.BurnIn)
    }

    @Test
    fun `fidelityFor maps decisions to the user-facing enum`() {
        assertEquals(
            SubtitleFidelity.Native,
            SubtitlePolicy.fidelityFor(SubtitlePolicy.Decision.Native(ass())),
        )
        assertEquals(
            SubtitleFidelity.Transcoding,
            SubtitlePolicy.fidelityFor(SubtitlePolicy.Decision.BurnIn(ass())),
        )
        assertEquals(
            SubtitleFidelity.Degraded,
            SubtitlePolicy.fidelityFor(SubtitlePolicy.Decision.Degraded(ass())),
        )
        assertEquals(
            SubtitleFidelity.None,
            SubtitlePolicy.fidelityFor(SubtitlePolicy.Decision.NoSubtitles),
        )
    }

    @Test
    fun `decideForTrack accepts a caller-selected track`() {
        // Variant entry point used by the in-player cast button (which already knows which
        // track the user has selected). Same decision logic, no source-order rule.
        val track = ass(streamIndex = 5, styled = true)
        val decision = SubtitlePolicy.decideForTrack(
            track = track,
            receiverCapabilities = plainReceiver,
            userPreference = SubtitlePolicy.UserPreference.BestFidelity,
        )
        assertTrue(decision is SubtitlePolicy.Decision.BurnIn)
        assertEquals(5, (decision as SubtitlePolicy.Decision.BurnIn).track.streamIndex)
    }
}
