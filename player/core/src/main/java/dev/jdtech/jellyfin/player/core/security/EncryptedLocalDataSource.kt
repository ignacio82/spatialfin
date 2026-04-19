package dev.jdtech.jellyfin.player.core.security

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.security.ContentKeyManager
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import timber.log.Timber

/**
 * A [DataSource] that transparently AES-CTR-decrypts a downloaded file whose
 * row in `downloadtasks` is flagged `isEncrypted = true`. The per-file IV is
 * read from that row. Non-encrypted URIs and non-`file://` URIs cause this
 * source to return `openError` — callers should use
 * [EncryptedLocalDataSourceFactory] which delegates unencrypted paths to a
 * plain [androidx.media3.datasource.DefaultDataSource].
 *
 * Seek support: AES-CTR is a stream cipher over fixed-size blocks. To decrypt
 * starting at byte offset P we advance the CTR counter by P/16 and drop the
 * first P%16 bytes of the first produced block. Java's `Cipher.init` resets
 * the counter on every init, so we re-init on each open/seek.
 */
internal class EncryptedLocalDataSource(
    private val dek: ByteArray,
    private val database: ServerDatabaseDao,
    transferListener: TransferListener?,
) : BaseDataSource(/* isNetwork = */ false) {

    init {
        transferListener?.let { addTransferListener(it) }
    }

    private var raf: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var dataSpec: DataSpec? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0L
    // Bytes to discard from the next decrypted chunk (partial first block).
    private var skipBytesInNextBlock: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        val filePath = dataSpec.uri.path
            ?: throw IllegalArgumentException("EncryptedLocalDataSource: missing path in ${dataSpec.uri}")
        val file = File(filePath)
        val task = database.getDownloadTaskByFinalPath(filePath)
            ?: throw IllegalStateException("EncryptedLocalDataSource: no download row for $filePath")
        if (!task.isEncrypted) {
            throw IllegalStateException("EncryptedLocalDataSource: row is not flagged encrypted; use plain DataSource")
        }
        val ivB64 = task.encryptionIv
            ?: throw IllegalStateException("EncryptedLocalDataSource: encrypted row is missing IV")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        if (iv.size != BLOCK_SIZE) {
            throw IllegalStateException("EncryptedLocalDataSource: IV must be $BLOCK_SIZE bytes, got ${iv.size}")
        }

        transferInitializing(dataSpec)
        val rf = RandomAccessFile(file, "r")
        raf = rf

        val fileLength = rf.length()
        val start = dataSpec.position
        if (start > fileLength) {
            throw IllegalArgumentException("Position $start beyond file length $fileLength")
        }
        val remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            fileLength - start
        } else {
            minOf(dataSpec.length, fileLength - start)
        }
        rf.seek(start)

        val blockIndex = start / BLOCK_SIZE
        val byteOffsetInBlock = (start % BLOCK_SIZE).toInt()
        val advancedIv = incrementIv(iv, blockIndex)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), IvParameterSpec(advancedIv))
        cipher = c
        skipBytesInNextBlock = byteOffsetInBlock
        // If we start mid-block, we must feed the cipher the preceding ciphertext bytes
        // of that block so its internal keystream position stays aligned. Seek `raf` back
        // to the block boundary and feed the discarded bytes into the cipher.
        if (byteOffsetInBlock > 0) {
            rf.seek(blockIndex * BLOCK_SIZE)
            val pad = ByteArray(byteOffsetInBlock)
            rf.readFully(pad)
            // Decrypt and discard — advances the cipher past the padding.
            c.update(pad)
        }

        bytesRemaining = remaining
        this.dataSpec = dataSpec
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(readLength.toLong(), bytesRemaining).toInt()
        val ct = ByteArray(want)
        val raf = raf ?: return C.RESULT_END_OF_INPUT
        val cipher = cipher ?: return C.RESULT_END_OF_INPUT
        val n = raf.read(ct, 0, want)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        val pt = cipher.update(ct, 0, n) ?: ByteArray(0)
        System.arraycopy(pt, 0, buffer, offset, pt.size)
        bytesRemaining -= pt.size
        bytesTransferred(pt.size)
        return pt.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val hadSpec = dataSpec != null
        runCatching { raf?.close() }
        raf = null
        cipher = null
        uri = null
        dataSpec = null
        if (hadSpec) transferEnded()
    }

    private fun incrementIv(iv: ByteArray, blockCount: Long): ByteArray {
        if (blockCount == 0L) return iv.copyOf()
        // CTR treats the IV as a big-endian integer; add blockCount, truncate to 16 bytes.
        val asInt = BigInteger(1, iv).add(BigInteger.valueOf(blockCount))
        val raw = asInt.toByteArray()
        val out = ByteArray(BLOCK_SIZE)
        val src = if (raw.size > BLOCK_SIZE) raw.copyOfRange(raw.size - BLOCK_SIZE, raw.size) else raw
        System.arraycopy(src, 0, out, BLOCK_SIZE - src.size, src.size)
        return out
    }

    companion object {
        private const val TRANSFORMATION = "AES/CTR/NoPadding"
        private const val BLOCK_SIZE = 16
    }
}

/**
 * [DataSource.Factory] that routes requests at open-time:
 *  - encrypted local downloads → [EncryptedLocalDataSource]
 *  - everything else (http, non-encrypted file, content://) → the delegate.
 *
 * The routing check happens in a thin dispatching DataSource so that Media3's
 * machinery (track selectors, preload manager) only sees a single factory.
 */
class EncryptedLocalDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val contentKeyManager: ContentKeyManager,
    private val database: ServerDatabaseDao,
) : DataSource.Factory {
    private var transferListener: TransferListener? = null

    fun setTransferListener(listener: TransferListener?) {
        transferListener = listener
    }

    override fun createDataSource(): DataSource = DispatchingDataSource(
        delegateFactory = delegate,
        contentKeyManager = contentKeyManager,
        database = database,
        transferListener = transferListener,
    )
}

private class DispatchingDataSource(
    private val delegateFactory: DataSource.Factory,
    private val contentKeyManager: ContentKeyManager,
    private val database: ServerDatabaseDao,
    private val transferListener: TransferListener?,
) : DataSource {
    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val isEncryptedLocal = runCatching {
            if (dataSpec.uri.scheme != null && dataSpec.uri.scheme != "file") return@runCatching false
            val path = dataSpec.uri.path ?: return@runCatching false
            database.getDownloadTaskByFinalPath(path)?.isEncrypted == true
        }.getOrElse {
            Timber.w(it, "DispatchingDataSource: DB lookup failed for %s", dataSpec.uri)
            false
        }
        val source: DataSource = if (isEncryptedLocal) {
            val dek = contentKeyManager.getDekOrNull()
                ?: throw java.io.IOException(
                    "App is locked — encrypted downloads are unplayable until the user authenticates"
                )
            EncryptedLocalDataSource(
                dek = dek,
                database = database,
                transferListener = transferListener,
            )
        } else {
            delegateFactory.createDataSource().also { ds ->
                transferListener?.let { ds.addTransferListener(it) }
            }
        }
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun addTransferListener(transferListener: TransferListener) {
        active?.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = active?.uri

    override fun close() {
        runCatching { active?.close() }
        active = null
    }
}
