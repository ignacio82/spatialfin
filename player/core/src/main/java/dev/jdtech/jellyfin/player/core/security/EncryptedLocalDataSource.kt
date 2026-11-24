package dev.jdtech.jellyfin.player.core.security

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadTaskDto
import dev.jdtech.jellyfin.security.ContentKeyManager
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [DataSource] that transparently AES-CTR-decrypts a downloaded file whose
 * row in `downloadtasks` is flagged `isEncrypted = true`. The per-file IV is
 * read from that row.
 */
internal class EncryptedLocalDataSource(
    private val dek: ByteArray,
    private val task: DownloadTaskDto,
    private val canonicalFile: File,
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
    private var skipBytesInNextBlock: Int = 0

    override fun open(dataSpec: DataSpec): Long {
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
        val rf = RandomAccessFile(canonicalFile, "r")
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
        if (byteOffsetInBlock > 0) {
            rf.seek(blockIndex * BLOCK_SIZE)
            val pad = ByteArray(byteOffsetInBlock)
            rf.readFully(pad)
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
        dek.fill(0)
        if (hadSpec) transferEnded()
    }

    private fun incrementIv(iv: ByteArray, blockCount: Long): ByteArray {
        if (blockCount == 0L) return iv.copyOf()
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
 *  - `file://` or scheme-less paths are canonicalized and guarded against private app-directory traversal
 *  - encrypted local downloads → [EncryptedLocalDataSource]
 *  - everything else (http, allowed non-encrypted file, content://) → the delegate.
 */
class EncryptedLocalDataSourceFactory(
    private val context: Context,
    private val delegate: DataSource.Factory,
    private val contentKeyManager: ContentKeyManager,
    private val database: ServerDatabaseDao,
) : DataSource.Factory {
    private var transferListener: TransferListener? = null

    fun setTransferListener(listener: TransferListener?) {
        transferListener = listener
    }

    override fun createDataSource(): DataSource = DispatchingDataSource(
        context = context.applicationContext,
        delegateFactory = delegate,
        contentKeyManager = contentKeyManager,
        database = database,
        transferListener = transferListener,
    )
}

private class DispatchingDataSource(
    private val context: Context,
    private val delegateFactory: DataSource.Factory,
    private val contentKeyManager: ContentKeyManager,
    private val database: ServerDatabaseDao,
    private val transferListener: TransferListener?,
) : DataSource {
    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val isLocalFile = scheme == null || scheme.equals("file", ignoreCase = true)

        var resolvedTask: DownloadTaskDto? = null
        var canonicalFile: File? = null

        if (isLocalFile) {
            val rawPath = dataSpec.uri.path
                ?: throw IOException("Local file URI is missing path: ${dataSpec.uri}")
            val resolved = File(rawPath).canonicalFile
            canonicalFile = resolved

            val task = try {
                database.getDownloadTaskByFinalPath(resolved.path)
                    ?: database.getDownloadTaskByFinalPath(rawPath)
            } catch (t: Throwable) {
                throw IOException("Failed to resolve download metadata for local file: ${resolved.name}", t)
            }
            resolvedTask = task

            if (isForbiddenPrivateAppPath(resolved, isRegisteredDownload = task != null)) {
                throw SecurityException("Access to private application directory blocked: ${resolved.name}")
            }
        }

        val source: DataSource = if (resolvedTask?.isEncrypted == true && canonicalFile != null) {
            val dek = contentKeyManager.getDekOrNull()
                ?: throw IOException(
                    "App is locked — encrypted downloads are unplayable until the user authenticates",
                )
            EncryptedLocalDataSource(
                dek = dek,
                task = resolvedTask,
                canonicalFile = canonicalFile,
                transferListener = transferListener,
            )
        } else {
            delegateFactory.createDataSource().also { ds ->
                transferListener?.let { ds.addTransferListener(it) }
            }
        }

        val effectiveSpec = if (canonicalFile != null && resolvedTask?.isEncrypted != true) {
            dataSpec.buildUpon().setUri(Uri.fromFile(canonicalFile)).build()
        } else {
            dataSpec
        }

        active = source
        return try {
            source.open(effectiveSpec)
        } catch (t: Throwable) {
            runCatching { source.close() }
            active = null
            throw t
        }
    }

    private fun isForbiddenPrivateAppPath(canonicalFile: File, isRegisteredDownload: Boolean): Boolean {
        val canonicalCacheDir = runCatching { context.cacheDir.canonicalFile }.getOrNull()
        if (
            canonicalCacheDir != null &&
            canonicalFile.parentFile == canonicalCacheDir &&
            TRANSCODE_OFFLINE_REGEX.matches(canonicalFile.name)
        ) {
            return false
        }

        val canonicalExternalFilesDir = runCatching {
            context.getExternalFilesDir(null)?.canonicalFile
        }.getOrNull()
        if (isRegisteredDownload && canonicalExternalFilesDir != null && isInsideDir(canonicalFile, canonicalExternalFilesDir)) {
            return false
        }

        val protectedDirs = buildList {
            runCatching { context.dataDir?.canonicalFile }.getOrNull()?.let(::add)
            runCatching { context.filesDir.canonicalFile }.getOrNull()?.let(::add)
            canonicalCacheDir?.let(::add)
            canonicalExternalFilesDir?.let(::add)
            runCatching { context.externalCacheDir?.canonicalFile }.getOrNull()?.let(::add)
        }
        return protectedDirs.any { dir -> isInsideDir(canonicalFile, dir) }
    }

    private fun isInsideDir(file: File, dir: File): Boolean {
        val filePath = file.path
        val dirPath = dir.path.trimEnd(File.separatorChar)
        return filePath == dirPath || filePath.startsWith("$dirPath${File.separatorChar}")
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

    private companion object {
        private val TRANSCODE_OFFLINE_REGEX = Regex("^transcode_offline_\\d+\\.mp4$")
    }
}
