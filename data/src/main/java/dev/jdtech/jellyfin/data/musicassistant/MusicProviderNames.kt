package dev.jdtech.jellyfin.data.musicassistant

/**
 * Maps Music Assistant provider identifiers to friendly, user-facing source
 * labels ("YouTube Music", "Local", "Audible", …). Shared by every MA surface
 * so the same track reads the same way on a tile, a home row, and the queue.
 *
 * A saved item reports `provider == "library"` and carries the real source in
 * its provider mappings or URI scheme, so callers pass all three and we resolve
 * the most specific non-"library" value.
 */
object MusicProviderNames {

    /** Friendly name for a single `provider_instance` such as `youtube_music--abc123`. */
    fun friendlyName(instance: String): String {
        // Instances are "<domain>--<instance-id>"; the domain identifies the source.
        val domain = instance.substringBefore("--").trim().lowercase()
        return when (domain) {
            "youtube", "ytmusic", "youtube_music" -> "YouTube Music"
            "spotify", "spotify_connect" -> "Spotify"
            "tidal" -> "Tidal"
            "qobuz" -> "Qobuz"
            "deezer" -> "Deezer"
            "apple_music", "applemusic" -> "Apple Music"
            "soundcloud" -> "SoundCloud"
            "audible" -> "Audible"
            "audiobookshelf" -> "Audiobookshelf"
            "tunein" -> "TuneIn"
            "radiobrowser", "radio_browser" -> "Radio Browser"
            "filesystem_local", "filesystem_smb", "filesystem", "builtin" -> "Local"
            "jellyfin" -> "Jellyfin"
            "plex" -> "Plex"
            "subsonic", "opensubsonic" -> "Subsonic"
            "library", "" -> "Library"
            else -> domain.split('_', ' ').filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
                .ifBlank { domain }
        }
    }

    /**
     * Resolves the friendly source for an item from its top-level provider, its
     * first provider mapping, and its URI scheme — in that order of specificity.
     * Returns null when the only thing known is the generic "library" provider.
     */
    fun fromProviderOrUri(provider: String?, providerInstance: String?, uri: String?): String? {
        val raw = provider?.takeIf { it.isNotBlank() && !it.equals("library", ignoreCase = true) }
            ?: providerInstance?.takeIf { it.isNotBlank() }
            ?: uri?.substringBefore("://", missingDelimiterValue = "")
                ?.takeIf { it.isNotBlank() && !it.equals("library", ignoreCase = true) }
        return raw?.let(::friendlyName)
    }
}
