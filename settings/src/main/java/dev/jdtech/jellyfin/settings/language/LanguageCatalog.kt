package dev.jdtech.jellyfin.settings.language

import android.content.Context
import android.os.Build
import dev.jdtech.jellyfin.settings.R
import java.util.Locale

data class LanguageOption(
    val code: String,
    val displayName: String,
    val aliases: Set<String>,
)

object LanguageCatalog {
    @Volatile private var cachedOptions: List<LanguageOption>? = null

    fun all(context: Context): List<LanguageOption> {
        cachedOptions?.let { return it }

        return synchronized(this) {
            cachedOptions?.let { return@synchronized it }

            val names = context.resources.getStringArray(R.array.languages)
            val codes = context.resources.getStringArray(R.array.languages_values)
            val options =
                names.zip(codes).map { (name, code) ->
                    LanguageOption(
                        code = code.lowercase(Locale.US),
                        displayName = name,
                        aliases = buildAliases(code, name),
                    )
                }

            cachedOptions = options
            options
        }
    }

    fun defaultDeviceLanguageCode(context: Context): String {
        val locale =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.resources.configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale
            } ?: Locale.getDefault()

        return normalize(context, locale.toLanguageTag())
            ?: normalize(context, locale.language)
            ?: "eng"
    }

    fun normalize(context: Context, value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null

        val normalized = normalizeToken(raw)
        val candidates =
            buildSet {
                add(normalized)
                add(normalized.substringBefore('-'))
                add(normalized.substringBefore('_'))
                add(normalized.substringBefore('(').trim())
                add(normalized.substringBefore(',').trim())
            }.filter { it.isNotBlank() }

        return all(context).firstOrNull { option ->
            candidates.any { candidate -> option.aliases.contains(candidate) }
        }?.code
    }

    fun matches(context: Context, value: String?, targetCode: String?): Boolean {
        val normalizedTarget = normalize(context, targetCode) ?: return false
        return normalize(context, value) == normalizedTarget
    }

    fun displayName(context: Context, code: String?): String {
        val normalized = normalize(context, code) ?: return code?.uppercase(Locale.US) ?: "Unknown"
        return all(context).firstOrNull { it.code == normalized }?.displayName
            ?: normalized.uppercase(Locale.US)
    }

    fun summarize(context: Context, codes: List<String>, maxItems: Int = 3): String {
        if (codes.isEmpty()) return "No spoken languages selected"

        val names =
            codes.map { displayName(context, it) }.distinct().take(maxItems)
        val remaining = (codes.distinct().size - names.size).coerceAtLeast(0)

        return buildString {
            append(names.joinToString(", "))
            if (remaining > 0) {
                append(" +")
                append(remaining)
                append(" more")
            }
        }
    }

    private fun buildAliases(code: String, displayName: String): Set<String> {
        val normalizedCode = normalizeToken(code)
        return buildSet {
            add(normalizedCode)
            add(normalizedCode.substringBefore('-'))
            add(normalizeToken(displayName))
            displayName
                .split(",", ";", "(", ")")
                .map { normalizeToken(it) }
                .filter { it.isNotBlank() }
                .forEach(::add)

            val normalizedDisplayName = normalizeToken(displayName)
            Locale.getAvailableLocales().forEach { locale ->
                val iso3Language =
                    runCatching { locale.isO3Language.lowercase(Locale.US) }.getOrNull()
                val englishDisplayLanguage =
                    locale.getDisplayLanguage(Locale.ENGLISH)
                        .takeIf { it.isNotBlank() }
                        ?.let(::normalizeToken)
                val localDisplayLanguage =
                    locale.displayLanguage
                        .takeIf { it.isNotBlank() }
                        ?.let(::normalizeToken)

                if (
                    iso3Language == normalizedCode ||
                        englishDisplayLanguage == normalizedDisplayName ||
                        localDisplayLanguage == normalizedDisplayName
                ) {
                    locale.language
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(normalizeToken(it)) }
                    locale.toLanguageTag()
                        .takeIf { it.isNotBlank() }
                        ?.let { add(normalizeToken(it)) }
                    locale.getDisplayLanguage(Locale.ENGLISH)
                        .takeIf { it.isNotBlank() }
                        ?.let { add(normalizeToken(it)) }
                }
            }
        }
    }

    private fun normalizeToken(value: String): String {
        return value.trim().lowercase(Locale.US).replace('_', '-')
    }
}
