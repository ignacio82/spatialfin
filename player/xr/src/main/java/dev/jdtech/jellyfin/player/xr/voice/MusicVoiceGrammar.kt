package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.spatialfin.unified.HomeVoicePolicy

/**
 * Keyword recognition for Music Assistant transport, kept pure (no LLM, no
 * Android) so it can be unit-tested directly and run *before* the model parser
 * in [SpatialCommandCoordinator].
 *
 * Scope: HOME only. Music Assistant audio is the active surface in Home Space;
 * the PLAYER surface keeps its existing video transport untouched (controlling
 * music while a video plays is a deliberate follow-up — the player action
 * router doesn't handle [XrPlayerAction] music subtypes yet).
 *
 * Two recognition tiers:
 *  1. **Music-qualified** ("next song", "pause the music") — always music when
 *     in HOME, regardless of whether MA currently reports an active session.
 *  2. **Bare verbs** ("pause", "next", "volume up") — music only when
 *     [HomeVoicePolicy.resolveTransportTarget] says so (HOME + an active MA
 *     session), so they don't shadow navigation/search when nothing is playing.
 *
 * Input [text] is the coordinator's normalised transcript: lower-cased, with
 * punctuation collapsed to single spaces (so "don't" → "don t", "what's" →
 * "what s").
 */
internal object MusicVoiceGrammar {

    private val MUSIC_WORDS = listOf("music", "song", "songs", "track", "tracks", "tune", "tunes")

    fun match(text: String, playerState: PlayerStateSnapshot): XrPlayerAction? {
        if (playerState.screenContext != VoiceScreenContext.HOME) return null

        if (isNowPlayingQuery(text, playerState)) return XrPlayerAction.MusicReportNowPlaying

        if (MUSIC_WORDS.any(text::contains)) {
            qualifiedTransport(text)?.let { return it }
        }

        val routeBareToMusic = HomeVoicePolicy.resolveTransportTarget(
            screenContext = playerState.screenContext,
            maActive = playerState.maActive,
        ) == HomeVoicePolicy.TransportTarget.MUSIC
        if (routeBareToMusic) {
            bareTransport(text)?.let { return it }
        }
        return null
    }

    /** "what song is this", "who sings this", or "what's playing" while music is the active surface. */
    private fun isNowPlayingQuery(text: String, playerState: PlayerStateSnapshot): Boolean {
        val explicit = listOf(
            "what song is this",
            "what s this song",
            "what song is playing",
            "name this song",
            "name that song",
            "what music is this",
            "who sings this",
            "who s singing this",
            "who is singing this",
            "what track is this",
            "what s this track",
        ).any(text::contains)
        if (explicit) return true
        val musicSurfaceActive = playerState.maActive
        return musicSurfaceActive &&
            (text == "what s playing" || text == "what is playing" || text == "what s on")
    }

    /** Music-qualified verb (the transcript already mentions a music word). */
    private fun qualifiedTransport(text: String): XrPlayerAction? = when {
        text.contains("pause") || text.contains("hold") ||
            (text.contains("stop") && !text.contains("don t stop")) -> XrPlayerAction.MusicPause
        text.contains("previous") || text.contains("go back") ||
            text.contains("last song") || text.contains("last track") -> XrPlayerAction.MusicPrevious
        text.contains("next") || text.contains("skip") -> XrPlayerAction.MusicNext
        text.contains("resume") || text.contains("unpause") || text.contains("continue") ||
            Regex("^(play|start)( the)?( some)?( music| songs?| tracks?| tunes?)$").matches(text) ->
            XrPlayerAction.MusicResume
        text.contains("volume") || text.contains("louder") || text.contains("quieter") ||
            ((text.contains("turn") || text.contains("crank")) &&
                (text.contains("up") || text.contains("down"))) ->
            musicVolume(text)
        else -> null
    }

    /** Bare transport verb routed to music by surface (HOME + active MA session). */
    private fun bareTransport(text: String): XrPlayerAction? = when {
        text.matches(Regex("^(pause|stop|hold)$")) -> XrPlayerAction.MusicPause
        text.matches(Regex("^(play|resume|start|unpause|continue)$")) -> XrPlayerAction.MusicResume
        text.matches(Regex("^(toggle|play pause)$")) -> XrPlayerAction.MusicPlayPause
        text.matches(Regex("^(next|next one|skip)$")) -> XrPlayerAction.MusicNext
        text.matches(Regex("^(previous|previous one|back one|go back one)$")) -> XrPlayerAction.MusicPrevious
        text.contains("volume") || text.contains("louder") || text.contains("quieter") ||
            text == "turn it up" || text == "turn it down" -> musicVolume(text)
        else -> null
    }

    private fun musicVolume(text: String): XrPlayerAction {
        val percent = Regex("(\\d+)\\s*(?:percent|%)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
        if (percent != null) return XrPlayerAction.MusicAdjustVolume(percentage = percent / 100f)
        val down = text.contains("down") || text.contains("lower") || text.contains("quieter") ||
            text.contains("decrease") || text.contains("softer")
        return XrPlayerAction.MusicAdjustVolume(delta = if (down) -0.1f else 0.1f)
    }
}
