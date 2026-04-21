package dev.spatialfin

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsExport {
    private const val DOWNLOADS_SUBDIR = "SpatialFin"
    private const val MIME_TYPE_TEXT = "text/plain"

    data class TextTarget(
        val writer: BufferedWriter,
        val destination: String,
        val closeable: Closeable,
    )

    fun openLogTarget(context: Context): TextTarget =
        openTextTarget(
            context = context,
            fileName = "spatialfin-log-${timestamp()}.txt",
        )

    fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val target =
            openTextTarget(
                context = context,
                fileName = "spatialfin-crash-${timestamp()}.txt",
            )
        target.writer.use { writer ->
            writer.appendLine("SpatialFin crash")
            writer.appendLine("Timestamp: ${timestamp()}")
            writer.appendLine("Thread: ${thread.name}")
            writer.appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            writer.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine()
            writer.appendLine(throwable.stackTraceToString())
            writer.flush()
        }
        target.closeable.close()
    }

    private fun openTextTarget(context: Context, fileName: String): TextTarget {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createMediaStoreTarget(context, fileName)
            } else {
                createLegacyDownloadsTarget(fileName)
            }
        }.recoverCatching {
            createAppExternalFallback(context, fileName)
        }.getOrThrow()
    }

    private fun createLegacyDownloadsTarget(fileName: String): TextTarget {
        val downloadsRoot =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOADS_SUBDIR,
            )
        if (!downloadsRoot.exists() && !downloadsRoot.mkdirs()) {
            throw IOException("Unable to create ${downloadsRoot.absolutePath}")
        }
        val file = File(downloadsRoot, fileName)
        val writer = file.bufferedWriter()
        return TextTarget(
            writer = writer,
            destination = "Downloads/$DOWNLOADS_SUBDIR/$fileName",
            closeable = Closeable { writer.close() },
        )
    }

    private fun createAppExternalFallback(context: Context, fileName: String): TextTarget {
        val root =
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.resolve("diagnostics")
                ?: context.filesDir.resolve("diagnostics")
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Unable to create ${root.absolutePath}")
        }
        val file = File(root, fileName)
        val writer = file.bufferedWriter()
        return TextTarget(
            writer = writer,
            destination = file.absolutePath,
            closeable = Closeable { writer.close() },
        )
    }

    private fun createMediaStoreTarget(context: Context, fileName: String): TextTarget {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE_TEXT)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBDIR",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri =
            resolver.insert(collection, values)
                ?: throw IOException("Unable to create MediaStore entry for $fileName")
        try {
            val stream =
                resolver.openOutputStream(uri)
                    ?: throw IOException("Unable to open MediaStore stream for $fileName")
            val writer = BufferedWriter(OutputStreamWriter(stream))
            return TextTarget(
                writer = writer,
                destination = "Downloads/$DOWNLOADS_SUBDIR/$fileName",
                closeable =
                    Closeable {
                        writer.close()
                        publishPendingDownload(resolver = resolver, uri = uri)
                    },
            )
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun publishPendingDownload(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
}
