package dev.jdtech.jellyfin.core.presentation.components

import android.net.Uri
import java.util.UUID

/**
 * Builds the Jellyfin user's PrimaryImage URL given a server base address.
 * Returns null if either input is blank/null — callers should then fall back
 * to an initials badge or a default avatar.
 */
fun userPrimaryImageUri(serverAddress: String?, userId: UUID?): Uri? {
    if (serverAddress.isNullOrBlank() || userId == null) return null
    val base = serverAddress.trimEnd('/')
    return Uri.parse("$base/Users/$userId/Images/Primary")
}
