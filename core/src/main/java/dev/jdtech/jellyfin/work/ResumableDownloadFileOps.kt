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
     * AES-CTR-encrypt [sourceFile] into [targetTmpFile] using [dek] and [iv] in chunks,
     * checking [shouldAbort] between chunks and calling `fd.sync()` before closing.
     *
     * Leaves [sourceFile] untouched. On any failure or abort, [targetTmpFile] is deleted
     * and `false` is returned. Callers are responsible for zeroing [dek] in `finally`.
     */
    fun encryptStreamToFile(
        sourceFile: File,
        targetTmpFile: File,
        dek: ByteArray,
        iv: ByteArray,
        shouldAbort: () -> Boolean = { false },
    ): Boolean {
        val cipher = Cipher.getInstance(AES_CTR_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(iv))
        return try {
            targetTmpFile.parentFile?.mkdirs()
            sourceFile.inputStream().use { input ->
                targetTmpFile.outputStream().use { raw ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (shouldAbort()) {
                            return false
                        }
                        val read = input.read(buf)
                        if (read <= 0) break
                        val out = cipher.update(buf, 0, read)
                        if (out != null && out.isNotEmpty()) raw.write(out)
                    }
                    if (shouldAbort()) {
                        return false
                    }
                    val tail = cipher.doFinal()
                    if (tail != null && tail.isNotEmpty()) raw.write(tail)
                    raw.flush()
                    runCatching { raw.fd.sync() }
                }
            }
            if (shouldAbort()) {
                targetTmpFile.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "encryptStreamToFile: failed for %s -> %s", sourceFile, targetTmpFile)
            targetTmpFile.delete()
            false
        } finally {
            if (shouldAbort()) {
                targetTmpFile.delete()
            }
        }
    }

    /**
     * AES-CTR-encrypt [finalFile] in place using [dek] and [iv]. Writes the
     * ciphertext to a sibling `.enc` file first, then replaces the original via
     * [replaceFile]. On failure the `.enc` file is removed and [finalFile] is
     * left untouched in its plaintext form; returns `false`.
     */
    fun encryptFileInPlace(finalFile: File, dek: ByteArray, iv: ByteArray): Boolean {
        val encFile = File(finalFile.parent, finalFile.name + ".enc")
        if (!encryptStreamToFile(finalFile, encFile, dek, iv)) {
            encFile.delete()
            return false
        }
        return replaceFile(encFile, finalFile)
    }
}
