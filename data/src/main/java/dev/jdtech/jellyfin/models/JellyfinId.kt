package dev.jdtech.jellyfin.models

import java.util.UUID

private val DASHLESS_UUID_REGEX = Regex("^[0-9a-fA-F]{32}$")

/**
 * Parses either a canonical 36-character hyphenated UUID or a 32-character
 * hyphenless Jellyfin hex UUID into a [UUID], returning `null` if blank or invalid.
 */
fun parseJellyfinUuid(raw: String?): UUID? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return runCatching {
        if (DASHLESS_UUID_REGEX.matches(trimmed)) {
            UUID.fromString(
                "${trimmed.substring(0, 8)}-${trimmed.substring(8, 12)}-" +
                    "${trimmed.substring(12, 16)}-${trimmed.substring(16, 20)}-" +
                    trimmed.substring(20, 32),
            )
        } else {
            UUID.fromString(trimmed)
        }
    }.getOrNull()
}

/**
 * Normalizes a Jellyfin server or item ID for case- and hyphen-insensitive comparison
 * (trim, remove hyphens, lowercase).
 */
fun normalizeJellyfinId(raw: String): String =
    raw.trim().replace("-", "").lowercase()
