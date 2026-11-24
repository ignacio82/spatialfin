package dev.jdtech.jellyfin.session

import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compile-and-CI architectural guard tests:
 * - R84 M8: every `AppPreferences.setValue` / `writeXrPlayerPrefsBatch` reachable from `:xrplayer`
 *   writes only keys in `AppPreferences.XR_PLAYER_PREF_KEYS` (or goes through a main-process
 *   `XrProcessPreferenceBridge` guard).
 * - R84 M5: `SessionActivationCoordinator` is the sole mutator of `jellyfinApi.api.update(...)`
 *   and `jellyfinApi.userId = ...` across all main source modules.
 */
class ArchitectureGuardsTest {

    private fun findRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repository root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
    }

    private fun loadAppPreferencePropertyMap(root: File): Map<String, String> {
        val file = File(root, "settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppPreferences.kt")
        assertTrue("Missing AppPreferences.kt at ${file.absolutePath}", file.isFile)
        val text = file.readText()
        val declRegex = Regex(
            """val\s+([A-Za-z0-9_]+)\b[^=]*?(?:=\s*|get\(\)\s*=\s*)Preference(?:<[^>]+>)?\(\s*"([^"]+)"""",
        )
        val map = LinkedHashMap<String, String>()
        for (match in declRegex.findAll(text)) {
            map[match.groupValues[1]] = match.groupValues[2]
        }
        assertTrue("Expected parsed AppPreferences properties to be non-empty", map.isNotEmpty())
        val missingXrKeys = AppPreferences.XR_PLAYER_PREF_KEYS - map.values.toSet()
        assertTrue(
            "AppPreferences.XR_PLAYER_PREF_KEYS contains unmapped keys: $missingXrKeys",
            missingXrKeys.isEmpty(),
        )
        return map
    }

    private fun extractBalancedCallArgs(text: String, openParenIndex: Int): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openParenIndex until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(openParenIndex + 1, i)
                    }
                }
            }
        }
        return text.substring(openParenIndex + 1)
    }

    @Test
    fun `xrplayer preference writes stay strictly inside AppPreferences 15-key XR allowlist`() {
        val root = findRepoRoot()
        val propMap = loadAppPreferencePropertyMap(root)
        val propRefRegex = Regex("""(?:appPreferences|prefs|preferences)\.(?!isMainProcess\b|isDebuggable\b)([A-Za-z0-9_]+)""")
        val writeCallRegex = Regex("""\b(?:setValue|writeXrPlayerPrefsBatch)\s*\(""")

        val violations = mutableListOf<String>()

        val xrDir = File(root, "player/xr/src/main/java")
        assertTrue("Missing ${xrDir.absolutePath}", xrDir.isDirectory)
        xrDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val relPath = file.relativeTo(root).path
                for (match in writeCallRegex.findAll(text)) {
                    val openParen = text.indexOf('(', match.range.first)
                    val argsText = extractBalancedCallArgs(text, openParen)
                    val refs = propRefRegex.findAll(argsText).map { it.groupValues[1] }.toList()
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    if (refs.isEmpty()) {
                        violations.add("$relPath:$line -> unresolved preference write in: ${argsText.trim()}")
                        continue
                    }
                    for (prop in refs) {
                        val key = propMap[prop]
                        if (key == null) {
                            violations.add("$relPath:$line -> unknown AppPreferences property '$prop'")
                        } else if (key !in AppPreferences.XR_PLAYER_PREF_KEYS) {
                            violations.add(
                                "$relPath:$line -> property '$prop' ('$key') is NOT in AppPreferences.XR_PLAYER_PREF_KEYS; route via XrProcessPreferenceBridge",
                            )
                        }
                    }
                }
            }

        // Shared player/local writes must also either target XR_PLAYER_PREF_KEYS or be gated by isMainProcess + XrProcessPreferenceBridge.NoOp
        val localDir = File(root, "player/local/src/main/java")
        assertTrue("Missing ${localDir.absolutePath}", localDir.isDirectory)
        localDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                val relPath = file.relativeTo(root).path
                for (match in writeCallRegex.findAll(text)) {
                    val openParen = text.indexOf('(', match.range.first)
                    val argsText = extractBalancedCallArgs(text, openParen)
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    val refs = propRefRegex.findAll(argsText).map { it.groupValues[1] }.toList()
                    for (prop in refs) {
                        val key = propMap[prop] ?: continue
                        if (key !in AppPreferences.XR_PLAYER_PREF_KEYS) {
                            val precedingContext = text.substring((match.range.first - 250).coerceAtLeast(0), match.range.first)
                            val hasMainGuard =
                                precedingContext.contains("isMainProcess") &&
                                    precedingContext.contains("XrProcessPreferenceBridge.NoOp")
                            if (!hasMainGuard) {
                                violations.add(
                                    "$relPath:$line -> unguarded non-XR preference write '$prop' ('$key') reachable from :xrplayer",
                                )
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Found disallowed AppPreferences writes from :xrplayer code paths:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `SessionActivationCoordinator is the sole mutator of jellyfinApi active session and userId`() {
        val root = findRepoRoot()
        val modules = listOf(
            "app/unified",
            "companion/host",
            "core",
            "data",
            "fcast",
            "fcast/session-ui",
            "modes/audio",
            "modes/film",
            "modes/music",
            "player/beam",
            "player/core",
            "player/local",
            "player/session",
            "player/tv",
            "player/xr",
            "plugins",
            "sendspin",
            "settings",
            "setup",
            "shell/beam",
            "shell/tv",
        )
        val allowedPaths = setOf(
            "core/src/main/java/dev/jdtech/jellyfin/session/SessionActivationCoordinator.kt",
            "data/src/main/java/dev/jdtech/jellyfin/api/JellyfinApi.kt",
        )
        val mutationRegex = Regex("""\.api\.update\s*\(|\bjellyfinApi\.userId\s*=(?!=)""")
        val violations = mutableListOf<String>()

        for (mod in modules) {
            val srcDir = File(root, "$mod/src/main/java")
            if (!srcDir.isDirectory) continue
            srcDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relPath = file.relativeTo(root).path
                    if (relPath in allowedPaths) return@forEach
                    file.readLines().forEachIndexed { idx, rawLine ->
                        val trimmed = rawLine.trim()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                        val codePart = trimmed.substringBefore("//")
                        if (mutationRegex.containsMatchIn(codePart)) {
                            violations.add("$relPath:${idx + 1} -> $trimmed")
                        }
                    }
                }
        }

        assertTrue(
            "Found unauthorized JellyfinApi active session mutations outside SessionActivationCoordinator:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
