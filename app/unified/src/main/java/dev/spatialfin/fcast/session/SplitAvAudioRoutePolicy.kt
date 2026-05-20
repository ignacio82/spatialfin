package dev.spatialfin.fcast.session

import dev.spatialfin.fcast.ReceiverAudioCodecs

/**
 * Small, testable split-A/V audio routing policy.
 *
 * Auto mode optimizes for the best audio the receiver can actually render: direct stream when
 * the source codec is supported, otherwise server-transcode to the best compatible target.
 */
internal object SplitAvAudioRoutePolicy {
    enum class FallbackMode { Auto, Passthrough, TranscodeAac }

    enum class RecastAction { None, UpgradeToDirect, DowngradeToTranscode }

    fun canDirect(
        sourceAudioCodec: String?,
        receiverAudioCodecs: List<String>?,
        fallbackMode: FallbackMode,
    ): Boolean = when (fallbackMode) {
        FallbackMode.TranscodeAac -> false
        FallbackMode.Passthrough -> true
        FallbackMode.Auto -> ReceiverAudioCodecs.canRenderDirect(
            sourceAudioCodec,
            receiverAudioCodecs,
        )
    }

    fun preferredTranscodeCodecs(receiverAudioCodecs: List<String>?): List<String> =
        ReceiverAudioCodecs.preferredTranscodeCodecs(receiverAudioCodecs)

    fun recastForResolvedCapabilities(
        wasDirectStream: Boolean,
        sourceAudioCodec: String?,
        receiverAudioCodecs: List<String>,
        fallbackMode: FallbackMode,
    ): RecastAction {
        if (fallbackMode != FallbackMode.Auto) return RecastAction.None
        val canDirect = ReceiverAudioCodecs.canRenderDirect(sourceAudioCodec, receiverAudioCodecs)
        return when {
            wasDirectStream && !canDirect -> RecastAction.DowngradeToTranscode
            !wasDirectStream && canDirect -> RecastAction.UpgradeToDirect
            else -> RecastAction.None
        }
    }
}
