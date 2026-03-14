package dev.spatialfin

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * Timber tree that writes every log line to a file in real time.
 * Extends DebugTree so that class-name tags are auto-extracted from the call stack,
 * the same way logcat entries work. flush() is called after each write so the file
 * is readable even if the process is killed.
 */
class LogFileTree(val file: File) : Timber.DebugTree() {

    private val writer: BufferedWriter = file.bufferedWriter()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG   -> "D"
            Log.INFO    -> "I"
            Log.WARN    -> "W"
            Log.ERROR   -> "E"
            Log.ASSERT  -> "A"
            else        -> "?"
        }
        val line = "${fmt.format(Date())} $level/$tag: $message"
        synchronized(writer) {
            try {
                writer.write(line)
                writer.newLine()
                if (t != null) {
                    writer.write(t.stackTraceToString())
                    writer.newLine()
                }
                writer.flush()
            } catch (_: Exception) {}
        }
    }

    fun close() {
        synchronized(writer) {
            try { writer.close() } catch (_: Exception) {}
        }
    }
}
