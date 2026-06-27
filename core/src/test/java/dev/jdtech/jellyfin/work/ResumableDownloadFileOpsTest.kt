package dev.jdtech.jellyfin.work

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloadFileOpsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // --- progressFor ---

    @Test
    fun `progress is zero when total is unknown or non-positive`() {
        assertEquals(0, ResumableDownloadFileOps.progressFor(500L, null))
        assertEquals(0, ResumableDownloadFileOps.progressFor(500L, 0L))
        assertEquals(0, ResumableDownloadFileOps.progressFor(500L, -10L))
    }

    @Test
    fun `progress is a clamped integer percentage`() {
        assertEquals(0, ResumableDownloadFileOps.progressFor(0L, 100L))
        assertEquals(50, ResumableDownloadFileOps.progressFor(50L, 100L))
        assertEquals(99, ResumableDownloadFileOps.progressFor(999L, 1000L))
        assertEquals(100, ResumableDownloadFileOps.progressFor(100L, 100L))
        // Over-100 (downloaded exceeds total) is clamped, never reported above 100.
        assertEquals(100, ResumableDownloadFileOps.progressFor(150L, 100L))
    }

    // --- replaceFile ---

    @Test
    fun `replaceFile overwrites the destination and consumes the source`() {
        val replacement = tempFolder.newFile("replacement").apply { writeText("new") }
        val finalFile = tempFolder.newFile("final").apply { writeText("old") }

        assertTrue(ResumableDownloadFileOps.replaceFile(replacement, finalFile))
        assertEquals("new", finalFile.readText())
        assertFalse("source must be moved, not left behind", replacement.exists())
    }

    @Test
    fun `replaceFile creates the destination when it does not exist`() {
        val replacement = tempFolder.newFile("replacement").apply { writeText("data") }
        val finalFile = File(tempFolder.root, "does-not-exist-yet")
        assertFalse(finalFile.exists())

        assertTrue(ResumableDownloadFileOps.replaceFile(replacement, finalFile))
        assertEquals("data", finalFile.readText())
    }

    @Test
    fun `replaceFile fails and leaves the destination untouched when the source is missing`() {
        val missing = File(tempFolder.root, "missing-source")
        val finalFile = tempFolder.newFile("final").apply { writeText("keep me") }

        assertFalse(ResumableDownloadFileOps.replaceFile(missing, finalFile))
        assertEquals("keep me", finalFile.readText())
    }

    // --- encryptFileInPlace ---

    @Test
    fun `encrypt then decrypt round-trips the original content`() {
        val dek = randomKey()
        val iv = randomIv()
        // Larger than DEFAULT_BUFFER_SIZE so the streaming update() loop runs more
        // than once, plus a partial tail.
        val plaintext = ByteArray(8192 * 3 + 17) { (it * 31).toByte() }
        val finalFile = tempFolder.newFile("video").apply { writeBytes(plaintext) }

        assertTrue(ResumableDownloadFileOps.encryptFileInPlace(finalFile, dek, iv))

        // The sibling .enc scratch file is consumed by the in-place replace.
        assertFalse(File(finalFile.parent, finalFile.name + ".enc").exists())
        // On-disk content is now ciphertext, not the plaintext.
        assertFalse(plaintext.contentEquals(finalFile.readBytes()))
        // And it decrypts back to the original with the same key/IV (matching the
        // AES-CTR shape Media3's AesCipherDataSource uses on playback).
        assertArrayEquals(plaintext, decryptCtr(finalFile.readBytes(), dek, iv))
    }

    @Test
    fun `encrypt round-trips an empty file`() {
        val dek = randomKey()
        val iv = randomIv()
        val finalFile = tempFolder.newFile("empty")

        assertTrue(ResumableDownloadFileOps.encryptFileInPlace(finalFile, dek, iv))
        assertArrayEquals(ByteArray(0), decryptCtr(finalFile.readBytes(), dek, iv))
    }

    @Test
    fun `encrypt fails cleanly when the source file is missing`() {
        val missing = File(tempFolder.root, "no-such-file")

        assertFalse(ResumableDownloadFileOps.encryptFileInPlace(missing, randomKey(), randomIv()))
        // No orphaned scratch file is left behind.
        assertFalse(File(missing.parent, missing.name + ".enc").exists())
    }

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun randomIv(): ByteArray =
        ByteArray(ResumableDownloadFileOps.AES_IV_BYTES).also { SecureRandom().nextBytes(it) }

    private fun decryptCtr(ciphertext: ByteArray, dek: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ResumableDownloadFileOps.AES_CTR_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }
}
