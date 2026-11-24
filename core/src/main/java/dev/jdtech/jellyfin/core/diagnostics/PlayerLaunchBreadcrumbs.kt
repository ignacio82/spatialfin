package dev.jdtech.jellyfin.core.diagnostics

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import timber.log.Timber

object PlayerLaunchBreadcrumbs {
    private const val FILE_NAME = "player_launch_breadcrumb.txt"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private fun breadcrumbFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun markPending(context: Context, phase: String) {
        runCatching {
            val target = breadcrumbFile(context)
            val tmp = File(target.parentFile, "$FILE_NAME.${android.os.Process.myPid()}.tmp")
            val payload = "${System.currentTimeMillis()}\n${phase.replace('\n', ' ')}"
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.onFailure {
            Timber.w(it, "Failed to persist player launch breadcrumb")
        }
    }

    fun clear(context: Context) {
        runCatching {
            breadcrumbFile(context).delete()
        }
    }

    fun consumePending(context: Context): String? {
        val target = breadcrumbFile(context)
        if (!target.exists()) return null
        val content = runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
        clear(context)
        if (content.isNullOrBlank()) return null
        val lines = content.split('\n', limit = 2)
        val timestampMs = lines.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        val phase = lines.getOrNull(1)?.trim().orEmpty()
        if (phase.isBlank() || timestampMs <= 0L) return null
        val ageMs = System.currentTimeMillis() - timestampMs
        if (ageMs !in 0..MAX_AGE_MS) return null
        return "$phase (${ageMs}ms before relaunch)"
    }
}
