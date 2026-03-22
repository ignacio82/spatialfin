package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.decoder.DecoderInputBuffer
import java.nio.ByteBuffer
import androidx.media3.exoplayer.source.MediaSource
import timber.log.Timber

/**
 * Custom Media3 renderer that intercepts raw ASS/SSA subtitle data
 * and forwards it to LibassRenderer instead of using the built-in parser.
 *
 * For non-ASS subtitle formats, this renderer should not be used —
 * the default TextRenderer handles SRT, VTT, PGS, etc.
 */
@UnstableApi
class LibassTextRenderer(
    private val libassRenderer: LibassRenderer,
    private val onTrackInitialized: () -> Unit,
    private val fontLoader: (() -> List<Pair<String, ByteArray>>)? = null,
    /** Preference value at the time ExoPlayer was built ("auto", "always", "never"). */
    private val usagePref: String = "auto",
    /** Font size (pt) to use in the synthetic ASS header injected for SRT/VTT tracks. */
    private val srtFontSize: Int = 52,
) : BaseRenderer(C.TRACK_TYPE_TEXT) {

    private var inputFormatReceived = false
    private var dialogueFormatLine: String? = null
    private val formatHolder = FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    // Monotonically increasing ReadOrder for SRT events. libass deduplicates events that share
    // the same ReadOrder value, so every SRT chunk must get a unique counter. Reset on seek.
    private var srtReadOrder = 0
    private var fontsLoaded = false

    override fun getName(): String = "LibassTextRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (usagePref == "never") {
            Timber.i("subtitle: LibassTextRenderer skip mime=%s pref=never", mimeType)
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        return if (mimeType == MimeTypes.TEXT_SSA || mimeType == "text/x-ssa" ||
            mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT) {
            Timber.i("subtitle: LibassTextRenderer CLAIMED mime=%s lang=%s", mimeType, format.language)
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            if (mimeType == "application/x-media3-cues") {
                // This means Media3's in-pipeline subtitle transcoding is still active.
                // Raw ASS bytes were decoded before reaching us — libass cannot process them.
                // Fix: ensure experimentalParseSubtitlesDuringExtraction(false) is set on MediaSourceFactory.
                Timber.e("subtitle: LibassTextRenderer got application/x-media3-cues — subtitle transcoding is active! libass will NOT work. Check MediaSourceFactory setup.")
            } else {
                Timber.d("subtitle: LibassTextRenderer skip mime=%s lang=%s (not ASS/SSA)", mimeType, format.language)
            }
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        val format = formats[0]
        val initData = format.initializationData
        dialogueFormatLine = initData.firstOrNull()?.toString(Charsets.UTF_8)?.takeIf { it.startsWith("Format:") }
        Timber.i(
            "subtitle: LibassTextRenderer stream started mime=%s lang=%s initData=%d blocks sizes=%s",
            format.sampleMimeType,
            format.language,
            initData.size,
            initData.map { it.size }
        )
        ensureFontsLoaded()
        if (!inputFormatReceived && initData.isNotEmpty()) {
            val codecPrivate = buildCodecPrivate(initData)
            Timber.i(
                "subtitle: ASS codec private prepared %d bytes formatLine=%b headerBlocks=%d",
                codecPrivate.size,
                dialogueFormatLine != null,
                initData.size
            )
            libassRenderer.setTrackData(codecPrivate)
            inputFormatReceived = true
            onTrackInitialized()
        } else if (initData.isEmpty()) {
            // SRT/VTT tracks have no ASS header. Synthesize a minimal one so libass
            // creates a valid track and processChunk() calls are not silently dropped.
            if (!inputFormatReceived) {
                val syntheticHeader = buildSyntheticAssHeader()
                Timber.i("subtitle: SRT/VTT track — injecting synthetic ASS header (%d bytes, fontSize=%d)", syntheticHeader.size, srtFontSize)
                libassRenderer.setTrackData(syntheticHeader)
                inputFormatReceived = true
                onTrackInitialized()
            }
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        libassRenderer.clearCache()
        srtReadOrder = 0
    }

    private fun ensureFontsLoaded() {
        if (fontsLoaded) return
        fontsLoaded = true
        val fonts = runCatching { fontLoader?.invoke().orEmpty() }
            .onFailure { Timber.w(it, "subtitle: failed to load embedded ASS fonts") }
            .getOrDefault(emptyList())
        fonts.forEach { (name, data) ->
            libassRenderer.addFont(name, data)
        }
        Timber.i("subtitle: registered %d embedded ASS fonts", fonts.size)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Read available samples from the SampleStream
        // Each sample is a raw ASS dialogue line
        while (true) {
            buffer.clear()
            val result = readSource(formatHolder, buffer, /* readFlags= */ 0)
            if (result == C.RESULT_NOTHING_READ || result == C.RESULT_FORMAT_READ) break
            if (buffer.isEndOfStream) break

            buffer.flip()
            val data = buffer.data ?: continue
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val normalizedBytes = normalizeAssChunkForLibass(bytes)

            // Timestamps from readSource include the stream offset; subtract it to match 
            // the window-relative position used by the UI render polling loop.
            val startMs = (buffer.timeUs - getStreamOffsetUs()) / 1000
            val parsedDuration = parseDurationFromAssChunk(bytes)
            val isKaraoke = parsedDuration == null && hasKaraokeTags(normalizedBytes)
            val durationMs = parsedDuration
                ?: if (isKaraoke) 300_000L   // karaoke lines span entire song sections
                else 10_000L                 // regular dialogue — generous to handle long pauses

            val preview = String(bytes, Charsets.UTF_8)
                .take(140)
                .replace('\n', ' ')
                .replace('\r', ' ')
            val normalizedPreview = String(normalizedBytes, Charsets.UTF_8)
                .take(140)
                .replace('\n', ' ')
                .replace('\r', ' ')
            val prefix = bytes.take(24).joinToString(" ") { "%02x".format(it) }
            val hasDialoguePrefix = preview.startsWith("Dialogue:")
            Timber.d(
                "chunk: start=%dms dur=%dms size=%d→%d karaoke=%b parsed=%b dialoguePrefix=%b hex=%s | raw=%s | normalized=%s",
                startMs,
                durationMs,
                bytes.size,
                normalizedBytes.size,
                isKaraoke,
                parsedDuration != null,
                hasDialoguePrefix,
                prefix,
                preview,
                normalizedPreview
            )

            libassRenderer.processChunk(normalizedBytes, startMs, durationMs)
            buffer.clear()
        }
    }

    /**
     * Minimal ASS script header for SRT/VTT tracks that have no codec private data.
     * Required so ass_new_track produces a valid track; without this ctx->track stays NULL
     * and every ass_process_chunk call is silently dropped.
     *
     * Alignment 2 = bottom-center (standard subtitle position).
     * PlayRes matches common 1080p so font sizes scale correctly.
     */
    private fun buildSyntheticAssHeader(): ByteArray = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080
        WrapStyle: 0

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,$srtFontSize,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2.5,1.5,2,10,10,40,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun buildCodecPrivate(initializationData: List<ByteArray>): ByteArray {
        if (initializationData.size == 1) {
            return initializationData[0]
        }
        val formatLine = initializationData[0].toString(Charsets.UTF_8)
        val header = initializationData[1].toString(Charsets.UTF_8)
        if (!formatLine.startsWith("Format:")) {
            return initializationData[1]
        }

        val normalizedHeader = header.replace("\r\n", "\n")
        val mergedHeader = when {
            normalizedHeader.contains("\n$formatLine") -> normalizedHeader
            normalizedHeader.contains("[Events]") ->
                normalizedHeader.replaceFirst("[Events]", "[Events]\n$formatLine")
            normalizedHeader.endsWith("\n") -> normalizedHeader + formatLine
            else -> "$normalizedHeader\n$formatLine"
        }
        return mergedHeader.toByteArray(Charsets.UTF_8)
    }

    /**
     * Returns true when the chunk text contains karaoke override tags (\k, \kf, \ko, \K).
     * Karaoke lines in OP/ED intros can span 30–180 s; they need a much longer duration
     * fallback than regular dialogue to avoid expiring mid-song.
     */
    private fun hasKaraokeTags(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.UTF_8)
        return Regex("""\\[kK][fo]?""").containsMatchIn(text)
    }

    /**
     * ASS chunks from ExoPlayer/MKV demux contain the dialogue line text, which for
     * full-format lines includes embedded Start/End timestamps. When present, the duration
     * is derived from them so ass_process_chunk receives the correct active window and
     * does not render overlapping events.
     */
    private fun parseDurationFromAssChunk(bytes: ByteArray): Long? {
        val text = String(bytes, Charsets.UTF_8)
        parseDialogueDuration(text)?.let { return it }

        val pattern = Regex("""(\d+):(\d{2}):(\d{2})[:.，,](\d{2,3})""")
        val matches = pattern.findAll(text).toList()
        if (matches.size >= 2) {
            val startMs = parseAssTimestamp(matches[0].groupValues[0]) ?: return null
            val endMs = parseAssTimestamp(matches[1].groupValues[0]) ?: return null
            if (endMs > startMs) return endMs - startMs
        }
        return null
    }

    private fun parseDialogueDuration(text: String): Long? {
        if (!text.startsWith("Dialogue:")) return null

        val fields = text.removePrefix("Dialogue:").split(',', limit = 3)
        if (fields.size < 2) return null

        val startMs = parseAssTimestamp(fields[0].trim()) ?: return null
        val endMs = parseAssTimestamp(fields[1].trim()) ?: return null
        return (endMs - startMs).takeIf { it > 0 }
    }

    private fun parseAssTimestamp(value: String): Long? {
        val match = Regex("""^(\d+):(\d{2}):(\d{2})[:.，,](\d{2,3})$""").matchEntire(value)
            ?: return null
        val h = match.groupValues[1].toLong()
        val m = match.groupValues[2].toLong()
        val s = match.groupValues[3].toLong()
        val fractionalMs = match.groupValues[4].let {
            if (it.length == 2) it.toLong() * 10 else it.toLong()
        }
        return h * 3600_000 + m * 60_000 + s * 1000 + fractionalMs
    }

    /**
     * Strips the SRT index number and timestamp arrow line from a raw SRT/VTT block,
     * converts HTML inline tags to ASS override codes, and returns only the subtitle text.
     *
     * SRT block example:
     *   "1\n00:00:01,000 --> 00:00:03,000\n* dramatic music *"
     * VTT block example (no index, dot separator):
     *   "00:00:01.000 --> 00:00:03.000\nHello world"
     */
    private fun parseSrtOrVttText(raw: String): String {
        val lines = raw.trim().lines()
        val textLines = lines.filter { line ->
            val trimmed = line.trim()
            // Drop pure-integer index line
            if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) return@filter false
            // Drop SRT/VTT timestamp arrow line: contains " --> "
            if (trimmed.contains(" --> ")) return@filter false
            true
        }
        val joined = textLines.joinToString("\n").trim()
        return convertHtmlToAss(joined)
    }

    /** Converts common HTML inline tags to ASS override codes. */
    private fun convertHtmlToAss(text: String): String {
        return text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\\N")
            .replace(Regex("<i>", RegexOption.IGNORE_CASE), "{\\i1}")
            .replace(Regex("</i>", RegexOption.IGNORE_CASE), "{\\i0}")
            .replace(Regex("<b>", RegexOption.IGNORE_CASE), "{\\b1}")
            .replace(Regex("</b>", RegexOption.IGNORE_CASE), "{\\b0}")
            .replace(Regex("<u>", RegexOption.IGNORE_CASE), "{\\u1}")
            .replace(Regex("</u>", RegexOption.IGNORE_CASE), "{\\u0}")
            // Strip any remaining unknown HTML tags
            .replace(Regex("<[^>]+>"), "")
    }

    private fun normalizeAssChunkForLibass(bytes: ByteArray): ByteArray {
        val text = String(bytes, Charsets.UTF_8)
        if (text.startsWith("Dialogue:")) {
            val body = text.removePrefix("Dialogue:").trimStart()
            val commaPositions = ArrayList<Int>(3)
            body.forEachIndexed { index, c ->
                if (c == ',') {
                    commaPositions += index
                    if (commaPositions.size == 3) return@forEachIndexed
                }
            }
            if (commaPositions.size < 3) return bytes

            // Embedded ASS packets include: Start,End,ReadOrder,... while ass_process_chunk
            // receives timecode/duration separately and expects only the event payload.
            val normalized = body.substring(commaPositions[1] + 1)
            return normalized.toByteArray(Charsets.UTF_8)
        } else {
            // SRT or VTT raw block. Strip the index number and timestamp line, convert
            // HTML tags, then return the MKV event body format that ass_process_chunk expects:
            // ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            // ReadOrder must be unique per event — libass deduplicates events with the same value.
            val cleanText = parseSrtOrVttText(text)
            val readOrder = srtReadOrder++
            return "$readOrder,0,Default,,0,0,0,,$cleanText".toByteArray(Charsets.UTF_8)
        }
    }

    override fun isReady(): Boolean = true
    override fun isEnded(): Boolean = false
}
