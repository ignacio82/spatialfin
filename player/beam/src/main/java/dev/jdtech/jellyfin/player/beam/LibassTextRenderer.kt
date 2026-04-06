package dev.jdtech.jellyfin.player.beam

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import timber.log.Timber

@UnstableApi
class LibassTextRenderer(
    private val libassRenderer: LibassRenderer,
    private val onTrackInitialized: () -> Unit,
    private val usagePref: String = "auto",
    private val srtFontSize: Int = 52,
    private val subtitleTextColor: Int = android.graphics.Color.WHITE,
    private val subtitleBackgroundColor: Int = android.graphics.Color.TRANSPARENT,
) : BaseRenderer(C.TRACK_TYPE_TEXT) {
    private var inputFormatReceived = false
    private var dialogueFormatLine: String? = null
    private val formatHolder = FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private var srtReadOrder = 0

    override fun getName(): String = "BeamLibassTextRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (!LibassRenderer.isAvailable()) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (usagePref == "never") return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        return if (
            mimeType == MimeTypes.TEXT_SSA ||
            mimeType == "text/x-ssa" ||
            mimeType == MimeTypes.APPLICATION_SUBRIP ||
            mimeType == MimeTypes.TEXT_VTT
        ) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            if (mimeType == "application/x-media3-cues") {
                Timber.e("beam subtitle: media3 subtitle transcoding is active, libass cannot claim cues")
            }
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        val format = formats[0]
        val initData = format.initializationData
        dialogueFormatLine = initData.firstOrNull()?.toString(Charsets.UTF_8)?.takeIf { it.startsWith("Format:") }
        if (!inputFormatReceived && initData.isNotEmpty()) {
            libassRenderer.setTrackData(buildCodecPrivate(initData))
            inputFormatReceived = true
            onTrackInitialized()
        } else if (initData.isEmpty() && !inputFormatReceived) {
            libassRenderer.setTrackData(buildSyntheticAssHeader())
            inputFormatReceived = true
            onTrackInitialized()
        }
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, isReset: Boolean) {
        super.onPositionReset(positionUs, joining, isReset)
        libassRenderer.clearCache()
        srtReadOrder = 0
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        while (true) {
            buffer.clear()
            val result = readSource(formatHolder, buffer, 0)
            if (result == C.RESULT_NOTHING_READ || result == C.RESULT_FORMAT_READ) break
            if (buffer.isEndOfStream) break

            buffer.flip()
            val data = buffer.data ?: continue
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            val normalizedBytes = normalizeAssChunkForLibass(bytes)
            val startMs = (buffer.timeUs - getStreamOffsetUs()) / 1000
            val parsedDuration = parseDurationFromAssChunk(bytes)
            val isKaraoke = parsedDuration == null && hasKaraokeTags(normalizedBytes)
            val durationMs = parsedDuration ?: if (isKaraoke) 300_000L else 10_000L
            libassRenderer.processChunk(normalizedBytes, startMs, durationMs)
            buffer.clear()
        }
    }

    private fun buildSyntheticAssHeader(): ByteArray = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080
        WrapStyle: 0

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,$srtFontSize,${toAssColor(subtitleTextColor)},&H000000FF,&H00000000,${toAssColor(subtitleBackgroundColor)},-1,0,0,0,100,100,0,0,1,2.5,1.5,2,10,10,40,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun buildCodecPrivate(initializationData: List<ByteArray>): ByteArray {
        if (initializationData.size == 1) return initializationData[0]
        val formatLine = initializationData[0].toString(Charsets.UTF_8)
        val header = initializationData[1].toString(Charsets.UTF_8)
        if (!formatLine.startsWith("Format:")) return initializationData[1]
        val normalizedHeader = header.replace("\r\n", "\n")
        val mergedHeader =
            when {
                normalizedHeader.contains("\n$formatLine") -> normalizedHeader
                normalizedHeader.contains("[Events]") -> normalizedHeader.replaceFirst("[Events]", "[Events]\n$formatLine")
                normalizedHeader.endsWith("\n") -> normalizedHeader + formatLine
                else -> "$normalizedHeader\n$formatLine"
            }
        return mergedHeader.toByteArray(Charsets.UTF_8)
    }

    private fun hasKaraokeTags(bytes: ByteArray): Boolean =
        Regex("""\\[kK][fo]?""").containsMatchIn(String(bytes, Charsets.UTF_8))

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
        val match = Regex("""^(\d+):(\d{2}):(\d{2})[:.，,](\d{2,3})$""").matchEntire(value) ?: return null
        val h = match.groupValues[1].toLong()
        val m = match.groupValues[2].toLong()
        val s = match.groupValues[3].toLong()
        val fractionalMs = match.groupValues[4].let { if (it.length == 2) it.toLong() * 10 else it.toLong() }
        return h * 3600_000 + m * 60_000 + s * 1000 + fractionalMs
    }

    private fun parseSrtOrVttText(raw: String): String {
        val lines = raw.trim().lines()
        val textLines =
            lines.filter { line ->
                val trimmed = line.trim()
                if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) return@filter false
                if (trimmed.contains(" --> ")) return@filter false
                true
            }
        return convertHtmlToAss(textLines.joinToString("\n").trim())
    }

    private fun convertHtmlToAss(text: String): String =
        text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\\N")
            .replace(Regex("<i>", RegexOption.IGNORE_CASE), "{\\i1}")
            .replace(Regex("</i>", RegexOption.IGNORE_CASE), "{\\i0}")
            .replace(Regex("<b>", RegexOption.IGNORE_CASE), "{\\b1}")
            .replace(Regex("</b>", RegexOption.IGNORE_CASE), "{\\b0}")
            .replace(Regex("<u>", RegexOption.IGNORE_CASE), "{\\u1}")
            .replace(Regex("</u>", RegexOption.IGNORE_CASE), "{\\u0}")
            .replace(Regex("<[^>]+>"), "")

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
            return body.substring(commaPositions[1] + 1).toByteArray(Charsets.UTF_8)
        }
        val cleanText = parseSrtOrVttText(text)
        val readOrder = srtReadOrder++
        return "$readOrder,0,Default,,0,0,0,,$cleanText".toByteArray(Charsets.UTF_8)
    }

    private fun toAssColor(color: Int): String {
        val a = (color ushr 24) and 0xFF
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return "&H%02X%02X%02X%02X".format(a, b, g, r)
    }

    override fun isReady(): Boolean = true
    override fun isEnded(): Boolean = false
}
