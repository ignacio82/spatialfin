package dev.jdtech.jellyfin.network

data class SmbConnectionTarget(
    val host: String,
    val shareName: String,
)

object SmbPathNormalizer {
    fun normalizeConnectionTarget(
        host: String,
        shareName: String,
    ): SmbConnectionTarget {
        val normalizedHost = normalizeHost(host)
        val normalizedShare = normalizeShareName(shareName, normalizedHost)

        return SmbConnectionTarget(
            host = normalizedHost.ifBlank {
                extractHostFromReference(shareName)
            },
            shareName = normalizedShare,
        )
    }

    fun normalizeRelativePath(path: String): String =
        path.trim().replace('\\', '/').trim('/')

    private fun normalizeHost(host: String): String {
        val segments = host.toSegments()
        return when {
            host.hasExplicitHostReference() -> segments.firstOrNull().orEmpty()
            else -> host.trim().substringBefore('/').substringBefore('\\').trim()
        }
    }

    private fun normalizeShareName(
        shareName: String,
        expectedHost: String,
    ): String {
        val segments = shareName.toSegments()
        if (segments.isEmpty()) return ""

        return when {
            shareName.hasExplicitHostReference() &&
                expectedHost.isNotBlank() &&
                segments.first().equals(expectedHost, ignoreCase = true) ->
                segments.getOrNull(1).orEmpty()

            shareName.hasExplicitHostReference() ->
                segments.getOrNull(1).orEmpty()

            else -> segments.first()
        }
    }

    private fun extractHostFromReference(shareName: String): String {
        if (!shareName.hasExplicitHostReference()) return ""
        return shareName.toSegments().firstOrNull().orEmpty()
    }

    private fun String.toSegments(): List<String> =
        trim()
            .removePrefixIgnoreCase("smb://")
            .removePrefixIgnoreCase("cifs://")
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

    private fun String.hasExplicitHostReference(): Boolean {
        val trimmed = trim()
        return trimmed.startsWith("smb://", ignoreCase = true) ||
            trimmed.startsWith("cifs://", ignoreCase = true) ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("\\\\")
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
}
