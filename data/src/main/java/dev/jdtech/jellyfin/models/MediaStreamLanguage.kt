package dev.jdtech.jellyfin.models

import java.util.Locale

/**
 * Language resolution for Jellyfin media streams.
 *
 * Containers lie. A Matroska file can carry a perfectly good English audio
 * track whose EBML `Language` element was never written, so Jellyfin reports
 * `language = ""` (or `"und"`) while `title`/`displayTitle` plainly say
 * "English". Matching on [SpatialFinMediaStream.language] alone therefore
 * misses those tracks and playback falls back to whichever stream the
 * container marked default — frequently the wrong one.
 *
 * [matchesLanguage] closes that gap by falling back to the human-readable
 * title fields when the tag is missing. This is the single implementation:
 * detail-screen hero chips, pre-playback stream picking, and the in-player
 * track selector all route through it, so a track the hero chip claims will
 * play is the same track the server is actually asked for.
 */
object MediaStreamLanguage {

    /**
     * Canonical ISO 639-2/T code for [value], accepting 2-letter codes,
     * 3-letter codes (both /B and /T variants), locale tags ("pt-BR"), and
     * English display names. Returns null for blank input and for the
     * explicit "undefined" tag, which carries no information.
     */
    fun normalize(value: String?): String? {
        val raw = value?.trim()?.lowercase(Locale.US)?.replace('_', '-')?.takeIf { it.isNotBlank() }
            ?: return null
        if (raw == "und" || raw == "undefined") return null
        val base = raw.substringBefore('-')
        return ALIASES[raw] ?: ALIASES[base] ?: base.takeIf { it.length >= 2 }
    }

    /**
     * True when [stream] is in [preferred].
     *
     * Checked in order of trustworthiness: the language tag first, then the
     * title fields. The title check requires a whole-word match on the
     * language's names and codes — a substring test would match "Portuguese"
     * inside "Brazilian Portuguese Commentary" (fine) but also "ita" inside
     * "Digital" (not fine).
     */
    fun matchesLanguage(stream: SpatialFinMediaStream, preferred: String?): Boolean {
        val target = normalize(preferred) ?: return false
        normalize(stream.language)?.let { return it == target }

        val names = NAMES_BY_CODE[target] ?: setOf(target)
        val haystack = listOfNotNull(stream.title.takeIf { it.isNotBlank() }, stream.displayTitle)
            .joinToString(" ")
            .lowercase(Locale.US)
        if (haystack.isBlank()) return false
        val words = haystack.split(NON_WORD).filter { it.isNotBlank() }
        return words.any { it in names }
    }

    /**
     * Short uppercase label for [stream] — the language tag when present,
     * otherwise inferred from the title. Falls back to the first word of the
     * display title so an untagged "English - Dolby Digital Plus" track still
     * reads as something, rather than "UND".
     */
    fun displayCode(stream: SpatialFinMediaStream): String? {
        normalize(stream.language)?.let { return it.uppercase(Locale.US) }
        NAMES_BY_CODE.entries
            .firstOrNull { (_, names) ->
                val haystack = listOfNotNull(stream.title.takeIf { it.isNotBlank() }, stream.displayTitle)
                    .joinToString(" ")
                    .lowercase(Locale.US)
                haystack.split(NON_WORD).any { it in names }
            }
            ?.let { return it.key.uppercase(Locale.US) }
        return stream.displayTitle
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore(' ')
            ?.substringBefore('-')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.US)
    }

    private val NON_WORD = Regex("[^\\p{L}]+")

    /**
     * Alias -> ISO 639-2/T. Deliberately small: these are the languages whose
     * tracks SpatialFin actually has to disambiguate. Anything else falls
     * through to its own 2/3-letter base code, which still matches itself.
     */
    private val ALIASES: Map<String, String> = buildMap {
        // Named `alias`, not `put`: a local `put` here would shadow
        // MutableMap.put and recurse into itself.
        fun alias(code: String, vararg names: String) {
            this[code] = code
            names.forEach { this[it] = code }
        }
        alias("eng", "en", "english")
        alias("por", "pt", "portuguese", "brazilian", "português")
        alias("spa", "es", "spanish", "castilian", "español", "espanol")
        alias("fra", "fr", "fre", "french", "français", "francais")
        alias("deu", "de", "ger", "german", "deutsch")
        alias("ita", "it", "italian", "italiano")
        alias("jpn", "ja", "japanese", "日本語")
        alias("zho", "zh", "chi", "chinese", "mandarin", "cantonese")
        alias("kor", "ko", "korean")
        alias("rus", "ru", "russian")
        alias("nld", "nl", "dut", "dutch")
        alias("pol", "pl", "polish")
        alias("swe", "sv", "swe", "swedish")
        alias("dan", "da", "danish")
        alias("nor", "no", "nob", "norwegian")
        alias("fin", "fi", "finnish")
        alias("tur", "tr", "turkish")
        alias("ara", "ar", "arabic")
        alias("hin", "hi", "hindi")
        alias("tha", "th", "thai")
        alias("ces", "cs", "cze", "czech")
        alias("ell", "el", "gre", "greek")
        alias("heb", "he", "hebrew")
        alias("hun", "hu", "hungarian")
        alias("ron", "ro", "rum", "romanian")
        alias("ukr", "uk", "ukrainian")
        alias("vie", "vi", "vietnamese")
        alias("ind", "id", "indonesian")
    }

    /** Every token that identifies a language, keyed by its canonical code. */
    private val NAMES_BY_CODE: Map<String, Set<String>> =
        ALIASES.entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (code, aliases) -> (aliases + code).toSet() }
}

/** @see MediaStreamLanguage.matchesLanguage */
fun SpatialFinMediaStream.matchesLanguage(preferred: String?): Boolean =
    MediaStreamLanguage.matchesLanguage(this, preferred)
