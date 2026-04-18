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

    /**
     * When false, subtitle cues are decoded and fed to [onSubtitleText] for AI context but
     * are NOT forwarded to the LibASS renderer — nothing is displayed visually.
     * Defaults to true (display enabled). Set to false for silent/shadow tracks.
     */
    @Volatile var displayEnabled: Boolean = true

    /**
     * Called on each subtitle cue that is decoded, regardless of [displayEnabled].
     * Invoked from ExoPlayer's rendering thread — callers must dispatch to the main thread
     * before touching any Compose state.
     * Parameters: (timestampMs, plainText)
     */
    var onSubtitleText: ((Long, String) -> Unit)? = null

    private var inputFormatReceived = false
    private var dialogueFormatLine: String? = null
    private val formatHolder = FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    // Monotonically increasing ReadOrder for SRT events. libass deduplicates events that share
    // the same ReadOrder value, so every SRT chunk must get a unique counter. Reset on seek.
    private var srtReadOrder = 0
    private var fontsLoaded = false
    private var isSrtOrVtt = false

    override fun getName(): String = "LibassTextRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (usagePref == "never") {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        return if (mimeType == MimeTypes.TEXT_SSA || mimeType == "text/x-ssa" ||
            mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            if (mimeType == "application/x-media3-cues") {
                // This means Media3's in-pipeline subtitle transcoding is still active.
                // Raw ASS bytes were decoded before reaching us — libass cannot process them.
                // Fix: ensure experimentalParseSubtitlesDuringExtraction(false) is set on MediaSourceFactory.
                Timber.e("subtitle: LibassTextRenderer got application/x-media3-cues — subtitle transcoding is active! libass will NOT work. Check MediaSourceFactory setup.")
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
        val mimeType = format.sampleMimeType
        isSrtOrVtt = mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT
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
        } else if (initData.isEmpty() && isSrtOrVtt) {
            // SRT/VTT tracks have no ASS header of their own. Synthesize a minimal one
            // so libass creates a valid track and processChunk() calls are not silently
            // dropped. ASS tracks fall through deliberately: sideloaded .ass files arrive
            // as a single full-file sample where initializationData is empty but the
            // first render() buffer contains [Script Info] + [V4+ Styles]. processFullAssFile
            // parses the real header and calls setTrackData() itself. Injecting a synthetic
            // header here would replace every real style (colors, fonts, PlayRes,
            // alignment, margins) with generic Arial 72 at 1920x1080 — the exact cause
            // of "anime subtitles render as plain white Arial, wrong position/size".
            if (!inputFormatReceived) {
                val syntheticHeader = buildSyntheticAssHeader()
                Timber.i("subtitle: SRT/VTT track — injecting synthetic ASS header (%d bytes, fontSize=%d)", syntheticHeader.size, srtFontSize)
                libassRenderer.setTrackData(syntheticHeader)
                inputFormatReceived = true
                onTrackInitialized()
            }
        } else if (initData.isEmpty()) {
            Timber.i("subtitle: ASS track with empty initData — deferring header; expecting full-file sample")
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, isReset: Boolean) {
        super.onPositionReset(positionUs, joining, isReset)
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
        while (true) {
            buffer.clear()
            val result = readSource(formatHolder, buffer, /* readFlags= */ 0)
            if (result == C.RESULT_NOTHING_READ || result == C.RESULT_FORMAT_READ) break
            if (buffer.isEndOfStream) break

            buffer.flip()
            val data = buffer.data ?: continue
            val bytes = ByteArray(data.remaining())
            data.get(bytes)

            // Timestamps from readSource include the stream offset; subtract it to match
            // the window-relative position used by the UI render polling loop.
            val sampleStartMs = (buffer.timeUs - getStreamOffsetUs()) / 1000

            if (isFullAssFile(bytes)) {
                processFullAssFile(bytes)
            } else if (isSrtOrVtt && isFullSrtOrVttFile(bytes)) {
                processFullSrtOrVttFile(bytes)
            } else {
                if (!inputFormatReceived) {
                    Timber.w("subtitle: ASS track stream missing header and not a full file — injecting synthetic fallback")
                    libassRenderer.setTrackData(buildSyntheticAssHeader())
                    inputFormatReceived = true
                    onTrackInitialized()
                }
                processSingleChunk(bytes, sampleStartMs)
            }
            buffer.clear()
        }
    }

    private fun processSingleChunk(bytes: ByteArray, startMs: Long) {
        val normalizedBytes = normalizeAssChunkForLibass(bytes)
        val parsedDuration = parseDurationFromAssChunk(bytes)
        val isKaraoke = parsedDuration == null && hasKaraokeTags(normalizedBytes)
        val durationMs = parsedDuration
            ?: if (isKaraoke) 300_000L else 10_000L

        val plainText = extractPlainText(normalizedBytes)
        if (plainText.isNotBlank()) {
            onSubtitleText?.invoke(startMs, plainText)
        }
        if (displayEnabled) {
            libassRenderer.processChunk(normalizedBytes, startMs, durationMs)
        }
    }

    private fun isFullAssFile(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8)
        return head.contains("[Script Info]", ignoreCase = true) ||
            head.contains("[V4+ Styles]", ignoreCase = true) ||
            head.contains("[V4 Styles]", ignoreCase = true)
    }

    /**
     * A sideloaded ASS file arrives as one sample containing header + all Dialogue lines.
     * Split it: pass the script header to libass via setTrackData(), then dispatch each
     * Dialogue line as its own chunk with timestamps extracted from the file.
     */
    private fun processFullAssFile(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        val eventsIdx = text.indexOf("[Events]", ignoreCase = true)
        if (eventsIdx < 0) {
            Timber.w("subtitle: full-file ASS has no [Events] section (%d bytes)", bytes.size)
            return
        }

        val eventsBlock = text.substring(eventsIdx)
        val lines = eventsBlock.lines()
        val formatLine = lines.firstOrNull { it.trimStart().startsWith("Format:", ignoreCase = true) }
        val fieldOrder = formatLine?.let(::parseEventFieldOrder) ?: DEFAULT_ASS_EVENT_FIELDS

        // Header = everything before [Events] + the Format line inside [Events].
        val headerBuilder = StringBuilder(text.substring(0, eventsIdx))
        headerBuilder.append("[Events]\n")
        if (formatLine != null) {
            headerBuilder.append(formatLine.trimEnd()).append('\n')
        } else {
            headerBuilder.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        }
        if (!inputFormatReceived) {
            libassRenderer.setTrackData(headerBuilder.toString().toByteArray(Charsets.UTF_8))
            inputFormatReceived = true
            dialogueFormatLine = formatLine
            onTrackInitialized()
            Timber.i("subtitle: full-file ASS header loaded (%d bytes)", headerBuilder.length)
        }

        var dispatched = 0
        for (line in lines) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = trimmed.substringAfter(':').trimStart()
            val chunk = assEventToChunk(body, fieldOrder) ?: continue
            val plainText = extractPlainText(chunk.body.toByteArray(Charsets.UTF_8))
            if (plainText.isNotBlank()) {
                onSubtitleText?.invoke(chunk.startMs, plainText)
            }
            if (displayEnabled) {
                libassRenderer.processChunk(
                    chunk.body.toByteArray(Charsets.UTF_8),
                    chunk.startMs,
                    chunk.durationMs,
                )
            }
            dispatched++
        }
        Timber.i("subtitle: full-file ASS dispatched %d events", dispatched)
    }

    private data class AssChunk(val body: String, val startMs: Long, val durationMs: Long)

    private fun parseEventFieldOrder(formatLine: String): List<String> {
        return formatLine.substringAfter(':').split(',').map { it.trim() }
    }

    private fun assEventToChunk(eventBody: String, fieldOrder: List<String>): AssChunk? {
        val fields = eventBody.split(',', limit = fieldOrder.size)
        if (fields.size < fieldOrder.size) return null
        val get = { name: String ->
            val idx = fieldOrder.indexOfFirst { it.equals(name, ignoreCase = true) }
            if (idx < 0) null else fields[idx]
        }
        val startMs = parseAssTime(get("Start") ?: return null) ?: return null
        val endMs = parseAssTime(get("End") ?: return null) ?: return null
        val durationMs = (endMs - startMs).coerceAtLeast(0L)

        // Libass chunk format: ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        val layer = get("Layer") ?: "0"
        val style = get("Style") ?: "Default"
        val name = get("Name") ?: ""
        val marginL = get("MarginL") ?: "0"
        val marginR = get("MarginR") ?: "0"
        val marginV = get("MarginV") ?: "0"
        val effect = get("Effect") ?: ""
        val textField = fields.drop(fieldOrder.indexOfFirst { it.equals("Text", ignoreCase = true) })
            .joinToString(",")
        val readOrder = srtReadOrder++
        val body = "$readOrder,$layer,$style,$name,$marginL,$marginR,$marginV,$effect,$textField"
        return AssChunk(body, startMs, durationMs)
    }

    /** Parses `H:MM:SS.cc` (ASS timestamp) into milliseconds. */
    private fun parseAssTime(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size != 3) return null
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val secPart = parts[2].replace(',', '.').split('.')
            val s = secPart[0].toLong()
            val cs = if (secPart.size > 1) secPart[1].padEnd(2, '0').take(2).toLong() else 0L
            ((h * 3600 + m * 60 + s) * 1000) + (cs * 10)
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun isFullSrtOrVttFile(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8)
        return head.startsWith("WEBVTT", ignoreCase = true) ||
            Regex("""^\s*\d+\s*\r?\n\s*\d{2}:\d{2}""", RegexOption.MULTILINE).containsMatchIn(head)
    }

    /**
     * Explode a full-file SRT or VTT sample into ASS Dialogue chunks. The synthetic ASS header
     * set in onStreamChanged() provides the styling context, so each cue is emitted as a
     * standard dialogue chunk that libass can render.
     */
    private fun processFullSrtOrVttFile(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
            .replace("\r\n", "\n")
            .removePrefix("\uFEFF")
        val timecodeRegex = Regex("""(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{1,3})""")
        var dispatched = 0
        val blocks = text.split(Regex("\\n{2,}"))
        for (block in blocks) {
            val match = timecodeRegex.find(block) ?: continue
            val startMs = parseSrtTime(match.groupValues[1]) ?: continue
            val endMs = parseSrtTime(match.groupValues[2]) ?: continue
            val lineEnd = block.indexOf('\n', match.range.last)
            if (lineEnd < 0) continue
            val body = block.substring(lineEnd + 1)
                .trim()
                .replace("\n", "\\N")
                .replace(Regex("<[^>]+>"), "")
            if (body.isEmpty()) continue

            val readOrder = srtReadOrder++
            val chunk = "$readOrder,0,Default,,0,0,0,,$body"
            onSubtitleText?.invoke(startMs, body.replace("\\N", " "))
            if (displayEnabled) {
                libassRenderer.processChunk(chunk.toByteArray(Charsets.UTF_8), startMs, (endMs - startMs).coerceAtLeast(0L))
            }
            dispatched++
        }
        Timber.i("subtitle: full-file SRT/VTT dispatched %d cues", dispatched)
    }

    private fun parseSrtTime(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size != 3) return null
        return try {
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val secParts = parts[2].split('.')
            val s = secParts[0].toLong()
            val ms = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
            ((h * 3600 + m * 60 + s) * 1000) + ms
        } catch (_: NumberFormatException) {
            null
        }
    }

    private companion object {
        val DEFAULT_ASS_EVENT_FIELDS = listOf(
            "Layer", "Start", "End", "Style", "Name",
            "MarginL", "MarginR", "MarginV", "Effect", "Text",
        )
    }

    /**
     * Extracts plain human-readable text from a normalised ASS event body.
     * The normalised format is: ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
     * (9 comma-delimited fields; the last field may itself contain commas).
     */
    private fun extractPlainText(normalized: ByteArray): String {
        val raw = String(normalized, Charsets.UTF_8)
        val parts = raw.split(",", limit = 9)
        val textField = if (parts.size >= 9) parts[8] else raw
        return textField
            .replace(Regex("\\{[^}]*\\}"), "") // strip ASS override codes e.g. {\i1}
            .replace("\\N", " ")               // ASS hard line-break
            .replace("\\n", " ")               // ASS soft line-break
            .trim()
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

    private fun zlibDecompressIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // Strict zlib header check: first byte must be 0x78 AND second byte must
        // have CM=8 (deflate) and a valid compression level. The CMF/FLG checksum
        // alone is not specific enough — ASCII byte pairs like "xX" or "0 " can
        // satisfy (CMF*256+FLG)%31==0 and feed garbage into the Inflater.
        if (b0 != 0x78 || (b0 * 256 + b1) % 31 != 0) return bytes
        // b1 low-5 bits = FCHECK (already validated), bit 5 = FDICT, bits 6-7 = FLEVEL.
        // For raw ASCII text starting with '0,' etc., b0 ≠ 0x78, so those don't get here.
        // Additional sanity: if FDICT=1 we don't support external dictionaries — bail.
        if ((b1 and 0x20) != 0) return bytes
        return try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(bytes)
            val out = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(tmp)
                if (n <= 0) break
                out.write(tmp, 0, n)
            }
            val finished = inflater.finished()
            val remaining = inflater.remaining
            inflater.end()
            // Accept output even when finished()==false: some MKV encoders omit
            // the Adler32 trailer, so Inflater reports not-finished even after
            // successfully decoding a BFINAL deflate block. The downstream
            // UTF-8 validity filter rejects garbage output, so trusting the
            // partial result here is safe.
            if (out.size() > 0) {
                if (!finished) {
                    Timber.d(
                        "subtitle: zlib decompressed %d→%d bytes (no trailer, remaining=%d)",
                        bytes.size, out.size(), remaining,
                    )
                } else {
                    Timber.d("subtitle: zlib decompressed %d→%d bytes", bytes.size, out.size())
                }
                out.toByteArray()
            } else {
                val preview = bytes.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                Timber.w(
                    "subtitle: zlib produced zero output %d bytes finished=%b remaining=%d head=%s",
                    bytes.size, finished, remaining, preview,
                )
                bytes
            }
        } catch (e: Exception) {
            val preview = bytes.take(32).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            Timber.w(e, "subtitle: zlib decompression failed head=%s", preview)
            bytes
        }
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
        if (isSrtOrVtt) {
            val text = String(bytes, Charsets.UTF_8)
            val cleanText = parseSrtOrVttText(text)
            return "${srtReadOrder++},0,Default,,0,0,0,,$cleanText".toByteArray(Charsets.UTF_8)
        }

        // Media3 with experimentalParseSubtitlesDuringExtraction(false) still prepends
        // "Dialogue: Start,End," before the raw MKV block even for ContentEncoding-compressed
        // tracks. We must strip those two comma-delimited fields first, then decompress the
        // remaining body. Checking for zlib on the full bytes would fail because the first
        // byte is 'D' (0x44), not the zlib CMF byte 0x78.
        val payloadBytes: ByteArray
        if (bytes.size >= 9 &&
            bytes[0] == 'D'.code.toByte() && bytes[1] == 'i'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'l'.code.toByte() &&
            bytes[4] == 'o'.code.toByte() && bytes[5] == 'g'.code.toByte() &&
            bytes[6] == 'u'.code.toByte() && bytes[7] == 'e'.code.toByte() &&
            bytes[8] == ':'.code.toByte()
        ) {
            // Scan past "Dialogue:" and skip the first two comma-delimited fields (Start, End).
            var commaCount = 0
            var bodyOffset = 9
            while (bodyOffset < bytes.size && commaCount < 2) {
                if (bytes[bodyOffset] == ','.code.toByte()) commaCount++
                bodyOffset++
            }
            if (commaCount < 2) return ByteArray(0)
            val rawBlock = bytes.copyOfRange(bodyOffset, bytes.size)
            payloadBytes = zlibDecompressIfNeeded(rawBlock)
        } else {
            // No "Dialogue:" prefix — raw MKV event block, possibly zlib-compressed.
            payloadBytes = zlibDecompressIfNeeded(bytes)
        }

        val text = String(payloadBytes, Charsets.UTF_8)

        // Discard binary/corrupt content. UTF-8 replacement chars (U+FFFD) appear when binary
        // bytes survived intact after a failed decompression — sending them to libass produces
        // random white-text artifacts.
        if (text.any { it == '\uFFFD' || it.code < 0x09 || it.code in 0x0E..0x1F }) {
            Timber.w("subtitle: discarding binary/corrupt chunk (%d bytes)", payloadBytes.size)
            return ByteArray(0)
        }

        return replaceReadOrder(text)
    }

    private fun replaceReadOrder(block: String): ByteArray {
        val firstComma = block.indexOf(',')
        if (firstComma < 0) return ByteArray(0)
        val normalized = "${srtReadOrder++},${block.substring(firstComma + 1)}"
        return normalized.toByteArray(Charsets.UTF_8)
    }

    override fun isReady(): Boolean = true
    override fun isEnded(): Boolean = false
}
