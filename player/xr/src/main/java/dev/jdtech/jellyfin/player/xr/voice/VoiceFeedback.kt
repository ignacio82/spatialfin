package dev.jdtech.jellyfin.player.xr.voice

/**
 * Structured voice feedback states for the XR overlay.
 * Replaces the previous string-based "Thinking..." sentinels.
 */
sealed class VoiceFeedback {
    object Idle : VoiceFeedback()
    object Listening : VoiceFeedback()
    object Thinking : VoiceFeedback()
    data class Message(val text: String) : VoiceFeedback()
}
