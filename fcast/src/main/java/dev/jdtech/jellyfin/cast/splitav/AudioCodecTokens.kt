package dev.jdtech.jellyfin.cast.splitav

/**
 * Pure-Kotlin canonical audio codec tokens and matching rules shared across `:fcast` wire
 * contracts and `:player:core` split-A/V capability checks without introducing Android framework
 * dependencies into `:fcast`.
 */
object AudioCodecTokens {

    /** Codecs Android decodes in software on every device — safe to direct-stream anywhere. */
    val UNIVERSAL_SOFTWARE: Set<String> = setOf(
        "aac", "mp3", "opus", "vorbis", "flac", "alac",
        "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_mulaw", "pcm_alaw",
    )

    /** Bitstream / compressed theater codecs requiring receiver passthrough or AVR decoding. */
    val ALL_PASSTHROUGH_TOKENS: Set<String> = setOf(
        "ac3", "eac3", "eac3-joc", "truehd", "dts", "dts-hd",
    )

    /** Complete canonical 19-token codec vocabulary advertised by Spec 0 receivers. */
    val ALL_SUPPORTED_TOKENS: Set<String> = ALL_PASSTHROUGH_TOKENS + UNIVERSAL_SOFTWARE

    /** Normalize Jellyfin / Media3 codec spellings to the canonical tokens above. */
    fun normalize(codec: String?): String = when (val c = codec?.lowercase()?.trim().orEmpty()) {
        "ec3", "e-ac-3", "e-ac3" -> "eac3"
        "ac-3" -> "ac3"
        "mlp", "true-hd", "truehd-atmos" -> "truehd"
        "dca" -> "dts"
        "dts-hd-ma", "dts-hd ma", "dtshd", "dts_hd" -> "dts-hd"
        "dts-express", "dtse" -> "dts"
        "dtsx" -> "dts-hd"
        else -> c
    }

    /**
     * Can the receiver render [sourceCodec] directly (no server transcode) given
     * [receiverPassthrough] tokens advertised by the receiver?
     */
    fun canRenderDirect(sourceCodec: String?, receiverPassthrough: List<String>?): Boolean {
        val token = normalize(sourceCodec)
        if (token.isEmpty()) return false
        if (token in UNIVERSAL_SOFTWARE) return true
        val caps = receiverPassthrough?.map { normalize(it) }?.toSet() ?: return false
        if (token == "eac3-joc") return "eac3-joc" in caps || "eac3" in caps
        return token in caps
    }

    /**
     * Ordered transcode fallback preference given the receiver's advertised passthrough tokens.
     */
    fun preferredTranscodeCodecs(receiverPassthrough: List<String>?): List<String> {
        val caps = receiverPassthrough?.map { normalize(it) }?.toSet().orEmpty()
        return buildList {
            if ("eac3" in caps || "eac3-joc" in caps) add("eac3")
            if ("ac3" in caps) add("ac3")
            add("aac")
        }.distinct()
    }
}
