package dev.jdtech.jellyfin.network

import org.junit.Assert.assertEquals
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
}
