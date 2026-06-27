package dev.jdtech.jellyfin.work

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import timber.log.Timber

/**
 * Pure file/crypto helpers for [ResumableDownloadWorker], split out so the
 * finalize/encryption flow (audit Bug 2) is unit-testable without a WorkManager
 * / Hilt / Room harness. Nothing here touches Android, the database, or the key
 * store — callers supply the raw DEK and IV and own all persistence.
 */
internal object ResumableDownloadFileOps {
    // AES-CTR: 16-byte block, 16-byte IV. The cipher is the same shape Media3's
    // AesCipherDataSource uses, so the file can be decrypted on playback.
    const val AES_CTR_TRANSFORMATION = "AES/CTR/NoPadding"
    const val AES_IV_BYTES = 16

    /** Percentage in 0..100, clamped; 0 when the total is unknown or non-positive. */
    fun progressFor(downloadedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) return 0
        return ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * Replace [finalFile] with [replacementFile], preferring an atomic move and
     * falling back to a non-atomic replace where the filesystem rejects atomic
     * moves. On any failure the (orphaned) [replacementFile] is deleted and
     * `false` is returned, leaving [finalFile] untouched.
     */
    fun replaceFile(replacementFile: File, finalFile: File): Boolean =
        try {
            try {
                Files.move(
                    replacementFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    replacementFile.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "replaceFile: failed moving %s -> %s", replacementFile, finalFile)
            replacementFile.delete()
            false
        }

    /**
     * AES-CTR-encrypt [finalFile] in place using [dek] and [iv]. Writes the
     * ciphertext to a sibling `.enc` file first, then replaces the original via
     * [replaceFile]. On failure the `.enc` file is removed and [finalFile] is
     * left untouched in its plaintext form; returns `false`.
     */
    fun encryptFileInPlace(finalFile: File, dek: ByteArray, iv: ByteArray): Boolean {
        val cipher = Cipher.getInstance(AES_CTR_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(iv))
        val encFile = File(finalFile.parent, finalFile.name + ".enc")
        return try {
            finalFile.inputStream().use { input ->
                encFile.outputStream().use { raw ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        val out = cipher.update(buf, 0, read)
                        if (out != null && out.isNotEmpty()) raw.write(out)
                    }
                    val tail = cipher.doFinal()
                    if (tail != null && tail.isNotEmpty()) raw.write(tail)
                    raw.flush()
                }
            }
            replaceFile(encFile, finalFile)
        } catch (e: Exception) {
            Timber.e(e, "encryptFileInPlace: failed for %s", finalFile)
            encFile.delete()
            false
        }
    }
}
