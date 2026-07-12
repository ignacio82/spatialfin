package dev.jdtech.jellyfin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbPathNormalizerTest {

    @Test
    fun `normalizes smb url entered as share reference`() {
        val target = SmbPathNormalizer.normalizeConnectionTarget(
            host = "",
            shareName = "smb://nas.local/Movies",
        )

        assertEquals("nas.local", target.host)
        assertEquals("Movies", target.shareName)
    }

    @Test
    fun `strips unc host prefix from share name`() {
        val target = SmbPathNormalizer.normalizeConnectionTarget(
            host = "nas.local",
            shareName = "\\\\nas.local\\Movies",
        )

        assertEquals("nas.local", target.host)
        assertEquals("Movies", target.shareName)
    }

    @Test
    fun `keeps first segment when share name includes nested path`() {
        val target = SmbPathNormalizer.normalizeConnectionTarget(
            host = "nas.local",
            shareName = "Movies/4K",
        )

        assertEquals("nas.local", target.host)
        assertEquals("Movies", target.shareName)
    }

    @Test
    fun `normalizes smb relative file paths`() {
        val path = SmbPathNormalizer.normalizeRelativePath("\\TV Shows\\Season 1\\Episode 1.mkv")

        assertEquals("TV Shows/Season 1/Episode 1.mkv", path)
    }

    @Test
    fun `preserves existing normalization for safe relative paths`() {
        mapOf(
            "" to "",
            "/" to "",
            "\\" to "",
            " /TV Shows//Season 1/Episode 1.mkv/ " to "TV Shows//Season 1/Episode 1.mkv",
            "TV Shows\\Season 1/Movie.mkv" to "TV Shows/Season 1/Movie.mkv",
            ".hidden/..also-hidden/name." to ".hidden/..also-hidden/name.",
            "%2E/%2E%2E/file.mkv" to "%2E/%2E%2E/file.mkv",
            "folder/.../file.mkv" to "folder/.../file.mkv",
        ).forEach { (input, expected) ->
            assertEquals(expected, SmbPathNormalizer.normalizeRelativePath(input))
        }
    }

    @Test
    fun `rejects dot segments in relative paths for either separator`() {
        listOf(
            ".",
            "..",
            "./file.mkv",
            "../file.mkv",
            "folder/./file.mkv",
            "folder/../file.mkv",
            "folder/.",
            "folder/..",
            "folder//../file.mkv",
            "\\folder\\.\\file.mkv",
            "\\folder\\..\\file.mkv",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SmbPathNormalizer.normalizeRelativePath(path)
            }
        }
    }

    @Test
    fun `rejects dot segment share names from plain url and unc references`() {
        listOf(
            ".",
            "..",
            "./Other",
            "../Other",
            "smb://nas.local/./Other",
            "smb://nas.local/../Other",
            "\\\\nas.local\\.\\Other",
            "\\\\nas.local\\..\\Other",
        ).forEach { shareName ->
            assertThrows(IllegalArgumentException::class.java) {
                SmbPathNormalizer.normalizeConnectionTarget(
                    host = "nas.local",
                    shareName = shareName,
                )
            }
        }
    }

    @Test
    fun `preserves valid dot-like share names`() {
        listOf(".hidden", "..also-hidden", "...", "Movies.").forEach { shareName ->
            val target = SmbPathNormalizer.normalizeConnectionTarget(
                host = "nas.local",
                shareName = shareName,
            )

            assertEquals("nas.local", target.host)
            assertEquals(shareName, target.shareName)
        }
    }
}
