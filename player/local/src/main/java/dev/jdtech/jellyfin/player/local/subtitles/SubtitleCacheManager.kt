package dev.jdtech.jellyfin.player.local.subtitles

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Manages a local disk cache of subtitle dialogue lines for AI context.
 *
 * Lines are stored as tab-separated pairs: {timestampMs}\t{text}
 * with one entry per subtitle event, sorted by timestamp.
 *
 * The cache is populated by downloading the subtitle file at video start and
 * is used as a fallback when the in-memory subtitle ring-buffer does not cover
 * the time window requested by the AI (e.g. "what happened an hour ago").
 */
class SubtitleCacheManager(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, "subtitle_ai").also { it.mkdirs() }

    /** Returns true if a non-empty cache file exists for [itemId]. */
    fun hasCache(itemId: UUID): Boolean {
        val f = cacheFile(itemId)
        return f.exists() && f.length() > 100L
    }

    /**
     * Saves (overwrites) all subtitle lines for [itemId].
     * Lines are sorted by timestamp before writing.
     */
    fun saveLines(itemId: UUID, lines: List<Pair<Long, String>>) {
        if (lines.isEmpty()) return
        val sorted = lines.sortedBy { it.first }
        cacheFile(itemId).bufferedWriter(Charsets.UTF_8).use { writer ->
            sorted.forEach { (ts, text) ->
                val clean = text.replace('\t', ' ').replace('\n', ' ').trim()
                if (clean.isNotBlank()) writer.write("$ts\t$clean\n")
            }
        }
        Timber.i("SubtitleCache: saved %d lines for item %s", sorted.size, itemId)
    }

    /**
     * Returns subtitle lines in the [fromMs]..[toMs] window from the disk cache.
     * Returns an empty list if no cache exists for [itemId].
     */
    fun loadWindow(itemId: UUID, fromMs: Long, toMs: Long): List<Pair<Long, String>> {
        val file = cacheFile(itemId)
        Timber.d("SubtitleCache: loadWindow %s exists=%b fromMs=%d toMs=%d", itemId, file.exists(), fromMs, toMs)
        if (!file.exists()) return emptyList()
        return file.bufferedReader(Charsets.UTF_8).useLines { seq ->
            seq.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) return@mapNotNull null
                val ts = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
                if (ts < fromMs || ts > toMs) return@mapNotNull null
                ts to line.substring(tab + 1)
            }.toList()
        }
    }

    /**
     * Downloads the subtitle file at [uri], parses it, and stores it to disk.
     * Supports SRT, WebVTT, and ASS/SSA formats.
     *
     * @param accessToken  Optional Jellyfin access token added as X-Emby-Token header.
     *                     Many Jellyfin subtitle URLs already embed the api_key query parameter,
     *                     so this is only needed as a fallback.
     * @return Number of subtitle lines cached, or 0 on failure.
     */
    fun downloadAndCache(itemId: UUID, uri: Uri, accessToken: String?): Int {
        val content = fetchText(uri.toString(), accessToken) ?: return 0
        val lines = parseSubtitleContent(content)
        if (lines.isNotEmpty()) {
            saveLines(itemId, lines)
        }
        return lines.size
    }

    /** Removes the cache file for [itemId]. */
    fun evict(itemId: UUID) {
        cacheFile(itemId).delete()
    }

    // ─── Subtitle parsing ────────────────────────────────────────────────────

    /**
     * Auto-detects the subtitle format and parses it into (timestampMs, text) pairs.
     * Exposed for testing.
     */
    fun parseSubtitleContent(content: String): List<Pair<Long, String>> {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("[Script Info", ignoreCase = true) -> parseAss(content)
            trimmed.contains("WEBVTT") -> parseVtt(content)
            else -> parseSrt(content)
        }
    }

    private fun parseSrt(content: String): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        // SRT blocks are separated by one or more blank lines
        val blocks = content.split(Regex("\r?\n\r?\n+"))
        for (block in blocks) {
            val lines = block.trim().lines()
            val tsLine = lines.firstOrNull { "-->" in it } ?: continue
            val startMs = parseSrtTimestamp(tsLine.substringBefore("-->").trim()) ?: continue
            val text = lines.dropWhile { "-->" !in it }.drop(1)
                .joinToString(" ")
                .stripHtml()
                .trim()
            if (text.isNotBlank()) result.add(startMs to text)
        }
        return result
    }

    private fun parseVtt(content: String): List<Pair<Long, String>> {
        // VTT format is structurally similar to SRT after stripping the WEBVTT header
        val withoutHeader = content.lines()
            .dropWhile { "WEBVTT" in it || it.isBlank() }
            .joinToString("\n")
        return parseSrt(withoutHeader)
    }

    private fun parseAss(content: String): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        // Match both compact form (Dialogue: layer,start,end,style,...,,text) and
        // the full form with all fields — just need start time and text portion.
        val dialogueRegex = Regex(
            """^Dialogue:\s*\d+,(\d+:\d{2}:\d{2}[.:]\d{2}),\d+:\d{2}:\d{2}[.:]\d{2},[^,]*,[^,]*,\d+,\d+,\d+,[^,]*,(.*)$"""
        )
        for (line in content.lines()) {
            val match = dialogueRegex.matchEntire(line.trim()) ?: continue
            val startMs = parseAssTimestamp(match.groupValues[1]) ?: continue
            val text = match.groupValues[2]
                .replace(Regex("\\{[^}]*\\}"), "")  // strip {\i1} style overrides
                .replace("\\N", " ")
                .replace("\\n", " ")
                .replace("\\h", " ")
                .trim()
            if (text.isNotBlank()) result.add(startMs to text)
        }
        return result
    }

    private fun parseSrtTimestamp(ts: String): Long? {
        val match = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""").find(ts) ?: return null
        val h = match.groupValues[1].toLong()
        val m = match.groupValues[2].toLong()
        val s = match.groupValues[3].toLong()
        val ms = match.groupValues[4].toLong()
        return h * 3_600_000L + m * 60_000L + s * 1_000L + ms
    }

    private fun parseAssTimestamp(ts: String): Long? {
        // ASS uses H:MM:SS.cc (centiseconds, not milliseconds)
        val match = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{2})""").matchEntire(ts) ?: return null
        val h = match.groupValues[1].toLong()
        val m = match.groupValues[2].toLong()
        val s = match.groupValues[3].toLong()
        val cs = match.groupValues[4].toLong()
        return h * 3_600_000L + m * 60_000L + s * 1_000L + cs * 10L
    }

    private fun String.stripHtml(): String =
        this.replace(Regex("<[^>]+>"), "")

    // ─── HTTP fetch ──────────────────────────────────────────────────────────

    private fun fetchText(urlStr: String, accessToken: String?): String? {
        return runCatching {
            // Normalize double slashes in the path that arise when baseUrl has a trailing slash
            // and deliveryUrl has a leading slash (e.g. "http://host:8096//Videos/...").
            val protocolEnd = urlStr.indexOf("://")
            val normalized = if (protocolEnd >= 0) {
                urlStr.substring(0, protocolEnd + 3) +
                    urlStr.substring(protocolEnd + 3).replace("//", "/")
            } else urlStr
            val connection = URL(normalized).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 20_000
            // Jellyfin subtitle URLs often embed ?api_key=... already; add the header as a backup.
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("X-Emby-Token", accessToken)
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                Timber.w("SubtitleCache: HTTP %d fetching %s", code, normalized)
                return@runCatching null
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }.getOrElse { e ->
            Timber.w(e, "SubtitleCache: fetch failed for %s", urlStr.take(120))
            null
        }
    }

    private fun cacheFile(itemId: UUID): File = File(cacheDir, "$itemId.stt")
}
